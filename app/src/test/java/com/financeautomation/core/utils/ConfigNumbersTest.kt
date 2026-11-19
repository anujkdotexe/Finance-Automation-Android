package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigNumbersTest {

    @Test
    fun rejectsInfinityNaNAndHuge() {
        for (text in listOf("Infinity", "-Infinity", "+Infinity", "NaN", "1e308", "1E3", "10000000000.01", "99999999999999999999")) {
            assertNull("'$text' must be rejected", ConfigNumbers.parseMoney(text))
        }
    }

    @Test
    fun rejectsZeroNegativeBlankAndMoreThanTwoDecimals() {
        for (text in listOf("0", "0.00", "-5", "-0.5", "", "  ", "abc", "12abc", "1.234", "1,2,3.456", "0x10")) {
            assertNull("'$text' must be rejected", ConfigNumbers.parseMoney(text))
        }
        assertNull(ConfigNumbers.parseMoney(null))
    }

    @Test
    fun acceptsPlainAmounts() {
        assertEquals(5000.0, ConfigNumbers.parseMoney("5000")!!, 0.0)
        assertEquals(1234.5, ConfigNumbers.parseMoney(" 1,234.50 ")!!, 0.0)
        assertEquals(0.01, ConfigNumbers.parseMoney("0.01")!!, 0.0)
        assertEquals(10_000_000_000.0, ConfigNumbers.parseMoney("10000000000")!!, 0.0)
        assertEquals("trailing zeros are not extra decimals", 12.5, ConfigNumbers.parseMoney("12.500")!!, 0.0)
    }
}
