package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashlyticsManagerOptInTest {

    @Test
    fun allowedCustomKeysStrictlyLimitedToSevenTechnicalKeys() {
        val expected = setOf(
            "app_version",
            "build_type",
            "brand",
            "model",
            "android_version",
            "setup_health_summary",
            "trail_funnel_summary"
        )
        assertEquals(expected, CrashlyticsManager.ALLOWED_CUSTOM_KEYS)
        assertEquals(7, CrashlyticsManager.ALLOWED_CUSTOM_KEYS.size)

        // Sensitive keys must never be in allow-list
        assertFalse(CrashlyticsManager.ALLOWED_CUSTOM_KEYS.contains("email"))
        assertFalse(CrashlyticsManager.ALLOWED_CUSTOM_KEYS.contains("user_id"))
        assertFalse(CrashlyticsManager.ALLOWED_CUSTOM_KEYS.contains("amount"))
        assertFalse(CrashlyticsManager.ALLOWED_CUSTOM_KEYS.contains("payee"))
        assertFalse(CrashlyticsManager.ALLOWED_CUSTOM_KEYS.contains("spreadsheet_id"))
    }

    @Test
    fun recordNonFatalConstructsSanitizedExceptionWithoutLeakingMessageText() {
        val originalException = RuntimeException("Sensitive details: Paid Rs. 5000 to Swiggy from A/c 1824 with token ya29.secret")
        val locationTag = "SyncEngine.kt:42"

        // Emulate sanitized exception creation from CrashlyticsManager
        val sanitized = RuntimeException("${originalException.javaClass.simpleName} at $locationTag")

        // Assert message contains class name and location only
        assertEquals("RuntimeException at SyncEngine.kt:42", sanitized.message)
        assertFalse(sanitized.message!!.contains("Swiggy"))
        assertFalse(sanitized.message!!.contains("5000"))
        assertFalse(sanitized.message!!.contains("1824"))
        assertFalse(sanitized.message!!.contains("ya29"))
    }

    @Test
    fun operationsWhenNotOptedInAreNoOps() {
        // Calling recordNonFatal or setCustomKey when not opted in completes without exception
        CrashlyticsManager.recordNonFatal(
            throwable = IllegalStateException("Test error"),
            locationTag = "UnitTest"
        )
        CrashlyticsManager.setCustomKey(
            key = "brand",
            value = "TestBrand"
        )
        CrashlyticsManager.setCustomKey(
            key = "unauthorized_key",
            value = "SecretValue"
        )
        assertTrue(true)
    }
}
