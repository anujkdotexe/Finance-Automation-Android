package com.financeautomation.core

import com.financeautomation.core.utils.formatAmountInput
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.core.utils.formatRupeesCompact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FormatRupeesTest {

    @Test
    fun formatRupees_standardIndianGrouping() {
        assertEquals("₹0", formatRupees(0.0, includeDecimals = false))
        assertEquals("₹0.00", formatRupees(0.0, includeDecimals = true))
        assertEquals("₹500", formatRupees(500.0, includeDecimals = false))
        assertEquals("₹500.00", formatRupees(500.0, includeDecimals = true))
        assertEquals("₹1,500", formatRupees(1500.0, includeDecimals = false))
        assertEquals("₹1,23,456", formatRupees(123456.0, includeDecimals = false))
        assertEquals("₹1,23,456.78", formatRupees(123456.78, includeDecimals = true))
        assertEquals("₹10,00,000", formatRupees(1000000.0, includeDecimals = false))
        assertEquals("₹1,00,00,000", formatRupees(10000000.0, includeDecimals = false))
    }

    @Test
    fun formatRupees_negativeNumbers() {
        assertEquals("-₹500", formatRupees(-500.0, includeDecimals = false))
        assertEquals("-₹1,234.50", formatRupees(-1234.50, includeDecimals = true))
        assertEquals("-₹1,23,456", formatRupees(-123456.0, includeDecimals = false))
    }

    @Test
    fun formatRupeesAuto_reviewerTestCases() {
        // 0.996 rounds to 100 paise -> ₹1
        assertEquals("₹1", formatRupeesAuto(0.996))
        assertEquals("₹1.00", formatRupees(0.996, includeDecimals = true))

        // 19.999 rounds to 2000 paise -> ₹20
        assertEquals("₹20", formatRupeesAuto(19.999))
        assertEquals("₹20.00", formatRupees(19.999, includeDecimals = true))

        // 442.5 has 50 paise -> ₹442.50
        assertEquals("₹442.50", formatRupeesAuto(442.5))
        assertEquals("₹442.50", formatRupees(442.5, includeDecimals = true))
        assertEquals("₹442", formatRupees(442.5, includeDecimals = false))

        // 5121.5 has 50 paise and comma grouping -> ₹5,121.50
        assertEquals("₹5,121.50", formatRupeesAuto(5121.5))
        assertEquals("₹5,121.50", formatRupees(5121.5, includeDecimals = true))
        assertEquals("₹5,121", formatRupees(5121.5, includeDecimals = false))

        // Negative values
        assertEquals("-₹442.50", formatRupeesAuto(-442.5))
        assertEquals("-₹20", formatRupeesAuto(-19.999))
        assertEquals("-₹5,121.50", formatRupeesAuto(-5121.5))

        // 1,00,000 grouping
        assertEquals("₹1,00,000", formatRupeesAuto(100000.0))
        assertEquals("₹1,00,000.50", formatRupeesAuto(100000.5))
        assertEquals("₹1,00,000.00", formatRupees(100000.0, includeDecimals = true))
    }

    @Test
    fun formatAmountInput_variousAmounts() {
        assertEquals("", formatAmountInput(0.0))
        assertEquals("", formatAmountInput(-10.0))
        assertEquals("150", formatAmountInput(150.0))
        assertEquals("150.50", formatAmountInput(150.50))
        assertEquals("1234.56", formatAmountInput(1234.56))
    }

    @Test
    fun formatRupeesCompact_boundaries() {
        assertEquals("₹9,999", formatRupeesCompact(9999.0))
        assertEquals("₹10K", formatRupeesCompact(10000.0))
        assertEquals("₹50.0K", formatRupeesCompact(50003.0))
        assertEquals("₹99.9K", formatRupeesCompact(99999.0))
        assertEquals("₹1L", formatRupeesCompact(100000.0))
        assertEquals("₹12.3L", formatRupeesCompact(1234567.0))
        assertEquals("₹1.2Cr", formatRupeesCompact(12345678.0))
    }

    @Test
    fun testLongMoneyValues_neverUseEllipsis_andFormatAccurately() {
        val longVal = 9999999.99
        val exactWithDecimals = formatRupees(longVal, includeDecimals = true)
        assertEquals("₹99,99,999.99", exactWithDecimals)
        assertFalse(exactWithDecimals.contains("..."))

        val exactWithoutDecimals = formatRupees(longVal, includeDecimals = false)
        assertEquals("₹99,99,999", exactWithoutDecimals)
        assertFalse(exactWithoutDecimals.contains("..."))

        val exactAuto = formatRupeesAuto(longVal)
        assertEquals("₹99,99,999.99", exactAuto)
        assertFalse(exactAuto.contains("..."))

        val compact = formatRupeesCompact(longVal)
        assertEquals("₹99.9L", compact)
        assertFalse(compact.contains("..."))

        // Even larger: 1 Crore and beyond
        val oneCrore = 10000000.00
        assertEquals("₹1,00,00,000.00", formatRupees(oneCrore, includeDecimals = true))
        assertEquals("₹1Cr", formatRupeesCompact(oneCrore))
        assertFalse(formatRupeesCompact(oneCrore).contains("..."))

        val tenCrores = 99999999.99
        assertEquals("₹9,99,99,999.99", formatRupees(tenCrores, includeDecimals = true))
        assertEquals("₹9.9Cr", formatRupeesCompact(tenCrores))
        assertFalse(formatRupeesCompact(tenCrores).contains("..."))
    }
}
