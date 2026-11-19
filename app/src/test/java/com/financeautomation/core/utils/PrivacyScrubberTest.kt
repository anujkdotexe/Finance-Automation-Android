package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyScrubberTest {

    @Test
    fun spreadsheetIdIsRemoved() {
        val out = PrivacyScrubber.scrub("Requested entity was not found: 1abcDEFghiJKLmnoPQRstuVWXyz1234567890_-ABCDEF")
        assertFalse(out.contains("1abcDEFghiJKL"))
        assertTrue(out.contains("[id]"))
    }

    @Test
    fun emailAddressIsRemoved() {
        val out = PrivacyScrubber.scrub("The caller test.user@example.com does not have permission")
        assertFalse(out.contains("@example.com"))
        assertTrue(out.contains("[email]"))
    }

    @Test
    fun rupeeAmountsAndLongNumbersAreRemoved() {
        val out = PrivacyScrubber.scrub("Invalid value Rs 1,250.50 for account 123456789012")
        assertFalse(out.contains("1,250"))
        assertFalse(out.contains("123456789012"))
    }

    @Test
    fun quotedCellContentIsRemoved() {
        val out = PrivacyScrubber.scrub("Invalid value at 'values' ('Dinner with Rahul at the cafe')")
        assertFalse(out.contains("Rahul"))
        assertTrue(out.contains("'values'"))
    }

    @Test
    fun shortTechnicalTextAndClassNamesStayReadable() {
        val text = "UnsupportedOperationException: sync item 42 failed with HTTP 429"
        assertEquals(text, PrivacyScrubber.scrub(text))
    }

    @Test
    fun outputIsCappedInLength() {
        assertEquals(PrivacyScrubber.MAX_LENGTH, PrivacyScrubber.scrub("a b ".repeat(200)).length)
    }

    @Test
    fun diagnosticsRecordsAreScrubbed() {
        Diagnostics.clear()
        Diagnostics.record("Test", "failure for test.user@example.com")
        val reason = Diagnostics.getRecords().last().reason
        assertFalse(reason.contains("@example.com"))
        Diagnostics.clear()
    }
}
