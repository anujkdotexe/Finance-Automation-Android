package com.financeautomation.core

import com.financeautomation.core.utils.formatAmountInput
import com.financeautomation.core.utils.formatRupees
import org.junit.Assert.assertEquals
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
    fun formatAmountInput_variousAmounts() {
        assertEquals("", formatAmountInput(0.0))
        assertEquals("", formatAmountInput(-10.0))
        assertEquals("150", formatAmountInput(150.0))
        assertEquals("150.50", formatAmountInput(150.50))
        assertEquals("1234.56", formatAmountInput(1234.56))
    }
}

