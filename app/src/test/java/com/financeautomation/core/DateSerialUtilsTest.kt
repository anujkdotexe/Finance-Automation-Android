package com.financeautomation.core

import com.financeautomation.core.utils.DateSerialUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DateSerialUtilsTest {

    @Test
    fun testDateSerialForEpoch() {
        val epoch = LocalDate.of(1899, 12, 30)
        assertEquals(0, DateSerialUtils.toDateSerial(epoch))
        assertEquals(epoch, DateSerialUtils.fromDateSerial(0))
    }

    @Test
    fun testDateSerialForKnownDateNov2026() {
        // 2026-11-05 corresponds to integer serial 46331
        val date = LocalDate.of(2026, 11, 5)
        val serial = DateSerialUtils.toDateSerial(date)
        assertEquals(46331, serial)
        assertEquals(date, DateSerialUtils.fromDateSerial(46331))
    }

    @Test
    fun testTimeSerialNoon() {
        // 12:00:00 is exactly half a day = 0.5
        val noon = LocalTime.of(12, 0, 0)
        val serial = DateSerialUtils.toTimeSerial(noon)
        assertEquals(0.5, serial, 0.000001)

        val restored = DateSerialUtils.fromTimeSerial(0.5)
        assertEquals(12, restored.hour)
        assertEquals(0, restored.minute)
        assertEquals(0, restored.second)
    }

    @Test
    fun testTimeSerialSecondPrecision() {
        val time = LocalTime.of(15, 30, 45)
        val serial = DateSerialUtils.toTimeSerial(time)
        val restored = DateSerialUtils.fromTimeSerial(serial)

        assertEquals(time.hour, restored.hour)
        assertEquals(time.minute, restored.minute)
        assertEquals(time.second, restored.second)
    }

    @Test
    fun testIntegerSecondsConversion() {
        val time = LocalTime.of(10, 20, 30)
        val totalSeconds = DateSerialUtils.toIntegerSeconds(time)
        assertEquals(10 * 3600 + 20 * 60 + 30, totalSeconds)

        val restored = DateSerialUtils.fromIntegerSeconds(totalSeconds)
        assertEquals(time, restored)
    }
}
