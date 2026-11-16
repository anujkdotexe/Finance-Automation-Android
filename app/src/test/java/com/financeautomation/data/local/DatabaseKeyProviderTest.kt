package com.financeautomation.data.local

import android.security.keystore.KeyPermanentlyInvalidatedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

class DatabaseKeyProviderTest {

    @Test
    fun testIsPermanentKeyFailure_permanentExceptionsAreTrue() {
        // 1. AEADBadTagException (auth tag mismatch or file altered)
        val badTag = AEADBadTagException("Tag mismatch")
        assertTrue(
            "AEADBadTagException must be classified as permanent",
            DatabaseKeyProvider.isPermanentKeyFailure(badTag) { true }
        )

        // 2. KeyPermanentlyInvalidatedException (biometrics / lock screen changed)
        val invalidated = KeyPermanentlyInvalidatedException("Key invalidated")
        assertTrue(
            "KeyPermanentlyInvalidatedException must be classified as permanent",
            DatabaseKeyProvider.isPermanentKeyFailure(invalidated) { true }
        )

        // 3. CorruptKeyFileException (malformed / truncated IV or ciphertext file)
        val corrupt = CorruptKeyFileException("Truncated key file")
        assertTrue(
            "CorruptKeyFileException must be classified as permanent",
            DatabaseKeyProvider.isPermanentKeyFailure(corrupt) { true }
        )
    }

    @Test
    fun testIsPermanentKeyFailure_transientExceptionsAreFalse() {
        // 1. IOException (transient I/O error reading file)
        val ioEx = IOException("Read failed")
        assertFalse(
            "IOException must be classified as transient when alias is present",
            DatabaseKeyProvider.isPermanentKeyFailure(ioEx) { true }
        )

        // 2. Other GeneralSecurityException (transient cryptographic provider error)
        val secEx = GeneralSecurityException("Temporary provider error")
        assertFalse(
            "Generic GeneralSecurityException must be classified as transient when alias is present",
            DatabaseKeyProvider.isPermanentKeyFailure(secEx) { true }
        )

        // 3. ProviderException (Keystore daemon glitch / IPC failure)
        val provEx = ProviderException("Keystore daemon busy")
        assertFalse(
            "ProviderException must be classified as transient when alias is present",
            DatabaseKeyProvider.isPermanentKeyFailure(provEx) { true }
        )
    }

    @Test
    fun testIsPermanentKeyFailure_aliasMissingIsPermanent() {
        val ioEx = IOException("Failed")
        assertTrue(
            "When Keystore alias is confirmed missing, failure must be permanent",
            DatabaseKeyProvider.isPermanentKeyFailure(ioEx) { false }
        )
    }

    @Test
    fun testIsPermanentKeyFailure_aliasCheckThatThrowsIsTransient() {
        val ioEx = IOException("Failed")
        assertFalse(
            "When Keystore alias check throws an exception, failure must be treated as transient",
            DatabaseKeyProvider.isPermanentKeyFailure(ioEx) {
                throw KeyStoreException("Keystore IPC temporarily unavailable")
            }
        )
    }
}
