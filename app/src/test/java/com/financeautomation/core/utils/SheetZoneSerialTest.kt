package com.financeautomation.core.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** Date and time serials always describe the Asia/Kolkata wall clock, whatever zone the phone is set to. */
class SheetZoneSerialTest {

    private val original = TimeZone.getDefault()

    @Before
    fun phoneIsInLosAngeles() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After
    fun restore() {
        TimeZone.setDefault(original)
    }

    // 2026-11-14T20:00:00Z is 2026-11-15 01:30 in Kolkata and 2026-11-14 12:00 in Los Angeles
    private val instant = java.time.Instant.parse("2026-11-14T20:00:00Z").toEpochMilli()

    @Test
    fun dateSerialIsTheKolkataDayNotThePhoneDay() {
        assertEquals(DateSerialUtils.toDateSerial(LocalDate.of(2026, 11, 15)), DateSerialUtils.toDateSerial(instant))
        assertEquals(LocalDate.of(2026, 11, 15), DateSerialUtils.toLocalDate(instant))
    }

    @Test
    fun timeSerialIsTheKolkataClockNotThePhoneClock() {
        assertEquals(1.5 * 3600 / 86400.0, DateSerialUtils.toTimeSerial(instant), 1e-9)
    }

    @Test
    fun theSheetZoneIsOneNamedConstant() {
        assertEquals(ZoneId.of("Asia/Kolkata"), DateSerialUtils.SHEET_ZONE)
    }

    @Test
    fun noConversionStillTakesTheDeviceZone() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        for (f in sources) {
            assertFalse("${f.name} uses the device zone", f.readText().contains("ZoneId.systemDefault()"))
        }
    }
}
