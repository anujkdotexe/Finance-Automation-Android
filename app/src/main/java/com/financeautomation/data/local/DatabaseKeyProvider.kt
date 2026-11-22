package com.financeautomation.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CorruptKeyFileException(message: String) : Exception(message)

/**
 * Manages the generation, secure storage, and retrieval of the 256-bit
 * database encryption passphrase using Android Keystore AES-GCM.
 *
 * Guarantees:
 * 1. The master key never leaves the Android Keystore secure hardware.
 * 2. Only the AES-GCM encrypted passphrase and its random IV are stored in app-private storage.
 * 3. The raw passphrase is never logged, never stored in SharedPreferences, and never synced.
 * 4. To prevent key mismatch between `ATTACH ... KEY` and `SupportOpenHelperFactory`,
 *    the key is uniformly formatted as SQLCipher's raw hex key format: `x'<64 hex chars>'`.
 *    This ensures SQLCipher treats the key as a direct raw 256-bit AES key rather than passing
 *    it through PBKDF2 derivation.
 * 5. Transient Keystore errors never delete the passphrase file. Only permanently invalidated keys
 *    trigger regeneration.
 */
class DatabaseKeyProvider(
    private val context: Context,
    /** Pause between read attempts; injected so a test does not have to wait. */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {

    companion object {
        private const val TAG = "DatabaseKeyProvider"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "finance_database_master_key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val PASSPHRASE_BYTE_COUNT = 32
        private const val KEY_FILE_NAME = "database_passphrase.enc"

        /** A miss right after boot or an app update can be brief; it is permanent only if it persists. */
        private const val PERMANENT_CHECK_ATTEMPTS = 3
        private const val PERMANENT_CHECK_PAUSE_MILLIS = 200L

        /**
         * True only when the stored passphrase can never be read again: the master key was invalidated, is gone from
         * the Keystore, the file is damaged, or the key does not match the file (authentication tag mismatch).
         * Any other failure, including a Keystore that cannot answer right now, is treated as transient so the
         * passphrase file is kept and the caller retries. Regenerating the key makes the encrypted database
         * unreadable, so it must not happen on a glitch.
         */
        internal fun isPermanentKeyFailure(error: Exception, aliasPresent: () -> Boolean): Boolean {
            if (error is KeyPermanentlyInvalidatedException ||
                error is CorruptKeyFileException ||
                error is AEADBadTagException
            ) {
                return true
            }
            return try {
                !aliasPresent()
            } catch (_: Exception) {
                false
            }
        }
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
        }
    }

    private val keyFile: File
        get() = File(context.filesDir, KEY_FILE_NAME)

    /**
     * Retrieves the 32 raw random bytes wrapped by the Keystore.
     */
    @Synchronized
    fun getRawPassphrase(): ByteArray {
        val file = keyFile
        if (file.exists()) {
            // The key file and the Keystore alias can be briefly unreadable after boot or an update. Read again a few
            // times before a miss counts as permanent, and never delete the file on the first miss.
            for (attempt in 1..PERMANENT_CHECK_ATTEMPTS) {
                try {
                    if (file.length() <= GCM_IV_LENGTH) {
                        throw CorruptKeyFileException("Passphrase file is too short")
                    }
                    return decryptPassphrase(file)
                } catch (e: Exception) {
                    val isPermanent = isPermanentKeyFailure(e) { keyStore.containsAlias(KEY_ALIAS) }
                    if (!isPermanent) {
                        Log.e(TAG, "Transient Keystore error during passphrase decryption: ${e.javaClass.simpleName}. Preserving key file.")
                        throw e
                    }
                    if (attempt < PERMANENT_CHECK_ATTEMPTS) {
                        Log.w(TAG, "Key read failed (${e.javaClass.simpleName}), attempt $attempt of $PERMANENT_CHECK_ATTEMPTS. Trying again.")
                        sleeper(PERMANENT_CHECK_PAUSE_MILLIS)
                        continue
                    }
                    Log.e(TAG, "Keystore master key invalidated or key file corrupt: ${e.javaClass.simpleName}. Regenerating key.")
                    file.delete()
                    try {
                        keyStore.deleteEntry(KEY_ALIAS)
                    } catch (ignored: Exception) {}
                }
            }
        }

        return generateAndStorePassphrase(file)
    }

    /**
     * Returns the raw hex key formatted for SQLCipher: `x'<64 hex chars>'`.
     * This exact string must be used in `ATTACH ... KEY` statements.
     */
    @Synchronized
    fun getDatabaseHexKey(): String {
        val raw = getRawPassphrase()
        val hex = raw.joinToString("") { "%02x".format(it) }
        return "x'$hex'"
    }

    /**
     * Returns the UTF-8 byte array of the formatted hex key: `"x'<hex>'".toByteArray(Charsets.UTF_8)`.
     * This is passed to `SupportOpenHelperFactory` and `SQLiteDatabase.openDatabase` so that
     * SQLCipher parses the raw hex key uniformly everywhere without PBKDF2 derivation.
     */
    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        return getDatabaseHexKey().toByteArray(Charsets.UTF_8)
    }

    private fun getExistingMasterKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            throw KeyPermanentlyInvalidatedException("Keystore does not contain alias $KEY_ALIAS")
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw KeyPermanentlyInvalidatedException("Keystore does not contain a SecretKeyEntry for alias $KEY_ALIAS")
        return entry.secretKey
    }

    private fun getOrCreateMasterKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(parameterSpec)
            keyGenerator.generateKey()
        }

        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Keystore does not contain a SecretKeyEntry for alias $KEY_ALIAS")
        return entry.secretKey
    }

    private fun generateAndStorePassphrase(targetFile: File): ByteArray {
        val rawPassphrase = ByteArray(PASSPHRASE_BYTE_COUNT)
        SecureRandom().nextBytes(rawPassphrase)

        val masterKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(rawPassphrase)

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp_${System.currentTimeMillis()}")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(iv.size)
                fos.write(iv)
                fos.write(ciphertext)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (ignored: Exception) {}
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        return rawPassphrase
    }

    private fun decryptPassphrase(sourceFile: File): ByteArray {
        val fileBytes = FileInputStream(sourceFile).use { it.readBytes() }
        if (fileBytes.isEmpty()) {
            throw CorruptKeyFileException("Passphrase file is empty")
        }

        val ivSize = fileBytes[0].toInt() and 0xFF
        if (ivSize <= 0 || fileBytes.size <= 1 + ivSize) {
            throw CorruptKeyFileException("Invalid passphrase file structure")
        }

        val iv = fileBytes.copyOfRange(1, 1 + ivSize)
        val ciphertext = fileBytes.copyOfRange(1 + ivSize, fileBytes.size)

        val masterKey = getExistingMasterKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

        return cipher.doFinal(ciphertext)
    }
}
