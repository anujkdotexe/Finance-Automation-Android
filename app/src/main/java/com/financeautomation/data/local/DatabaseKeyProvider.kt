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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
class DatabaseKeyProvider(private val context: Context) {

    companion object {
        private const val TAG = "DatabaseKeyProvider"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "finance_database_master_key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val PASSPHRASE_BYTE_COUNT = 32
        private const val KEY_FILE_NAME = "database_passphrase.enc"
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
        if (file.exists() && file.length() > GCM_IV_LENGTH) {
            try {
                return decryptPassphrase(file)
            } catch (e: Exception) {
                if (e is KeyPermanentlyInvalidatedException || !keyStore.containsAlias(KEY_ALIAS)) {
                    Log.e(TAG, "Keystore master key permanently invalidated: ${e.javaClass.simpleName}. Regenerating key.")
                    file.delete()
                } else {
                    Log.e(TAG, "Transient Keystore error during passphrase decryption: ${e.javaClass.simpleName}. Preserving key file.")
                    throw e
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

        FileOutputStream(targetFile).use { fos ->
            fos.write(iv.size)
            fos.write(iv)
            fos.write(ciphertext)
            fos.flush()
        }

        return rawPassphrase
    }

    private fun decryptPassphrase(sourceFile: File): ByteArray {
        val fileBytes = FileInputStream(sourceFile).use { it.readBytes() }
        if (fileBytes.isEmpty()) {
            throw IllegalStateException("Passphrase file is empty")
        }

        val ivSize = fileBytes[0].toInt() and 0xFF
        if (ivSize <= 0 || fileBytes.size <= 1 + ivSize) {
            throw IllegalStateException("Invalid passphrase file structure")
        }

        val iv = fileBytes.copyOfRange(1, 1 + ivSize)
        val ciphertext = fileBytes.copyOfRange(1 + ivSize, fileBytes.size)

        val masterKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

        return cipher.doFinal(ciphertext)
    }
}
