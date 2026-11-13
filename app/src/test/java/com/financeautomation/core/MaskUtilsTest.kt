package com.financeautomation.core

import com.financeautomation.core.utils.MaskUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X-4: the diagnostics share text must not carry the full account email or spreadsheet id. */
class MaskUtilsTest {

    @Test
    fun emailKeepsOnlyTheFirstLetterAndTheDomain() {
        assertEquals("j***@example.com", MaskUtils.maskEmail("jane.doe@example.com"))
        assertEquals("Not set", MaskUtils.maskEmail(null))
        assertEquals("Not set", MaskUtils.maskEmail("  "))
    }

    @Test
    fun spreadsheetIdKeepsOnlyTheEnds() {
        val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
        val masked = MaskUtils.maskId(id)
        assertEquals("1AbC...6789", masked)
        assertFalse(masked.contains("MnOpQr"))
        assertEquals("Not set", MaskUtils.maskId(""))
        assertTrue(MaskUtils.maskId("short") == "***")
    }

    @Test
    fun diagnosticsShareTextContainsNeitherFullValue() {
        val text = MaskUtils.diagnosticsShareText("jane.doe@example.com", "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789")
        assertFalse(text.contains("jane.doe"))
        assertFalse(text.contains("MnOpQr"))
        assertTrue(text.contains("j***@example.com"))
    }
}
