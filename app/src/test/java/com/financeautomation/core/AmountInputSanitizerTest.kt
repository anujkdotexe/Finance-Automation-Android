package com.financeautomation.core

import com.financeautomation.core.utils.AmountInputSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountInputSanitizerTest {

    @Test
    fun testValidAmounts() {
        assertTrue(AmountInputSanitizer.isValidAmountInput(""))
        assertTrue(AmountInputSanitizer.isValidAmountInput("0"))
        assertTrue(AmountInputSanitizer.isValidAmountInput("150"))
        assertTrue(AmountInputSanitizer.isValidAmountInput("150."))
        assertTrue(AmountInputSanitizer.isValidAmountInput("150.5"))
        assertTrue(AmountInputSanitizer.isValidAmountInput("150.50"))
    }

    @Test
    fun testMultipleDotsRejected() {
        assertFalse(AmountInputSanitizer.isValidAmountInput("150.."))
        assertFalse(AmountInputSanitizer.isValidAmountInput("150.5.0"))
        assertFalse(AmountInputSanitizer.isValidAmountInput("1.2.3"))
    }

    @Test
    fun testMoreThanTwoDecimalsRejected() {
        assertFalse(AmountInputSanitizer.isValidAmountInput("150.555"))
        assertFalse(AmountInputSanitizer.isValidAmountInput("0.1234"))
    }

    @Test
    fun testSanitizeAmountInput() {
        assertEquals("150.50", AmountInputSanitizer.sanitizeAmountInput("150.50", "150"))
        assertEquals("150", AmountInputSanitizer.sanitizeAmountInput("150..", "150"))
    }
}

