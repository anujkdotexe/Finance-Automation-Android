package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsDropCounterTest {

    @Before
    fun setUp() {
        Diagnostics.clear()
    }

    @Test
    fun testDropCountersIncrementByReason() {
        Diagnostics.recordDrop("Personal sender SMS dropped")
        Diagnostics.recordDrop("Personal sender SMS dropped")
        Diagnostics.recordDrop("OTP or auth alert suppressed")
        Diagnostics.recordDrop("Non-whitelisted package: com.random.app")

        assertEquals(2, Diagnostics.getDropCount("Personal sender SMS dropped"))
        assertEquals(1, Diagnostics.getDropCount("OTP or auth alert suppressed"))
        assertEquals(1, Diagnostics.getDropCount("Non-whitelisted package: com.random.app"))
        assertEquals(0, Diagnostics.getDropCount("Self notification dropped"))

        val allCounts = Diagnostics.getAllDropCounts()
        assertEquals(3, allCounts.size)
        assertEquals(2, allCounts["Personal sender SMS dropped"])
    }

    @Test
    fun testDropCountersNeverRecordMessageTextOrSecrets() {
        // Test with sensitive tokens and phone numbers in reason string
        val sensitiveDrop = "Dropped SMS from +919876543210 with OTP 123456 and token abcdef123456"
        Diagnostics.recordDrop(sensitiveDrop)

        val records = Diagnostics.getRecords()
        val dropRecord = records.firstOrNull { it.tag == "DropCounter" }
        assertTrue("DropCounter record must exist", dropRecord != null)

        // Scrubbing must remove phone numbers, OTPs, tokens
        assertFalse(dropRecord!!.reason.contains("9876543210"))
        assertFalse(dropRecord.reason.contains("123456"))
        assertFalse(dropRecord.reason.contains("abcdef123456"))
    }

    @Test
    fun testClearResetsAllDropCounters() {
        Diagnostics.recordDrop("Personal sender SMS dropped")
        assertEquals(1, Diagnostics.getDropCount("Personal sender SMS dropped"))

        Diagnostics.clear()
        assertEquals(0, Diagnostics.getDropCount("Personal sender SMS dropped"))
        assertTrue(Diagnostics.getAllDropCounts().isEmpty())
    }
}
