package com.financeautomation.presentation

import com.financeautomation.presentation.ui.EntryIntentValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C-6: external callers of the exported EntryActivity reach only the plain expense or choose-action screen. */
class EntryCallerRestrictionTest {

    @Test
    fun externalCallerLosesTheCaptureIdAndTheFlow() {
        val result = EntryIntentValidator.restrictForCaller(
            isInternal = false, validatedCaptureId = "cap_secret", validatedFlow = "INCOME"
        )
        assertNull(result.captureId)
        assertNull(result.flow)
    }

    @Test
    fun internalCallerKeepsTheCaptureIdAndTheFlow() {
        val result = EntryIntentValidator.restrictForCaller(
            isInternal = true, validatedCaptureId = "cap_1", validatedFlow = "REPAID"
        )
        assertEquals("cap_1", result.captureId)
        assertEquals("REPAID", result.flow)
    }

    @Test
    fun externalCallerWithNothingExtraStaysOnTheDefaultScreen() {
        val result = EntryIntentValidator.restrictForCaller(false, null, null)
        assertNull(result.captureId)
        assertNull(result.flow)
    }
}
