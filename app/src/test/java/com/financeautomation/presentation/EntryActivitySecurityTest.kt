package com.financeautomation.presentation

import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.presentation.ui.EntryActivity
import com.financeautomation.presentation.ui.EntryIntentValidator
import com.financeautomation.presentation.ui.ModalBottomSheetActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryActivitySecurityTest {

    @Test
    fun testUnknownFlow_isIgnored() {
        assertNull("Unknown flow must be ignored", EntryIntentValidator.validateFlow("MALICIOUS_FLOW"))
        assertNull("Blank flow must be ignored", EntryIntentValidator.validateFlow("   "))
        assertNull("Null flow must be ignored", EntryIntentValidator.validateFlow(null))
        assertNull("Invalid action string must be ignored", EntryIntentValidator.validateFlow("DELETE_DATA"))
        assertNull("SQL injection attempt must be ignored", EntryIntentValidator.validateFlow("'; DROP TABLE transactions; --"))

        // Known flows must be accepted and normalized
        assertEquals("EXPENSE", EntryIntentValidator.validateFlow("EXPENSE"))
        assertEquals("INCOME", EntryIntentValidator.validateFlow("income"))
        assertEquals("INCOME_RETURN", EntryIntentValidator.validateFlow("income_return"))
        assertEquals("FRIEND_PAID", EntryIntentValidator.validateFlow("friend_paid"))
        assertEquals("REPAID", EntryIntentValidator.validateFlow("repaid"))
        assertEquals("REPAID_FRIEND", EntryIntentValidator.validateFlow("repaid_friend"))
        assertEquals("TRIP", EntryIntentValidator.validateFlow("trip"))
        assertEquals("TRIP_MODE", EntryIntentValidator.validateFlow("trip_mode"))
    }

    @Test
    fun testCaptureIdValidation_rejectsUnknownOrClosedCaptures() {
        // Unknown / null capture
        assertFalse("Null capture state must not be open", EntryIntentValidator.isCaptureOpen(null))
        assertFalse("Blank capture state must not be open", EntryIntentValidator.isCaptureOpen(""))

        // Closed captures
        assertFalse("DONE capture must not be open", EntryIntentValidator.isCaptureOpen(PendingCaptureEntity.STATE_DONE))
        assertFalse("DISMISSED capture must not be open", EntryIntentValidator.isCaptureOpen(PendingCaptureEntity.STATE_DISMISSED))

        // Open captures
        assertTrue("WAITING capture is open", EntryIntentValidator.isCaptureOpen(PendingCaptureEntity.STATE_WAITING))
        assertTrue("NEEDS_REVIEW capture is open", EntryIntentValidator.isCaptureOpen(PendingCaptureEntity.STATE_NEEDS_REVIEW))
        assertTrue("SHOWN capture is open", EntryIntentValidator.isCaptureOpen(PendingCaptureEntity.STATE_SHOWN))
        assertTrue("AWAITING_SUCCESS capture is open", EntryIntentValidator.isCaptureOpen(PendingCaptureEntity.STATE_AWAITING_SUCCESS))
    }

    @Test
    fun testSubscriptionExtraIgnored_andNoRawExtrasForwarded() {
        // Incoming set of raw extras keys from an untrusted caller
        val rawIncomingKeys = setOf(
            "com.financeautomation.extra.SUBSCRIPTION_CYCLE_ID",
            "injected_amount",
            "malicious_key",
            EntryActivity.EXTRA_CAPTURE_ID,
            EntryActivity.EXTRA_FLOW,
            ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH
        )

        val sanitizedKeys = EntryIntentValidator.sanitizeExtraKeys(rawIncomingKeys)

        // Ensure allowed keys survive
        assertTrue(sanitizedKeys.contains(EntryActivity.EXTRA_CAPTURE_ID))
        assertTrue(sanitizedKeys.contains(EntryActivity.EXTRA_FLOW))
        assertTrue(sanitizedKeys.contains(ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH))

        // Ensure malicious/unvalidated keys are completely stripped
        assertFalse("Subscription cycle extra must not survive sanitization",
            sanitizedKeys.contains("com.financeautomation.extra.SUBSCRIPTION_CYCLE_ID"))
        assertFalse("Injected amount must not survive sanitization",
            sanitizedKeys.contains("injected_amount"))
        assertFalse("Malicious extra must not survive sanitization",
            sanitizedKeys.contains("malicious_key"))
        assertEquals(3, sanitizedKeys.size)
    }

    @Test
    fun testEmptyRawExtras_producesEmptySanitizedKeys() {
        val sanitized = EntryIntentValidator.sanitizeExtraKeys(emptySet())
        assertTrue(sanitized.isEmpty())
    }
}
