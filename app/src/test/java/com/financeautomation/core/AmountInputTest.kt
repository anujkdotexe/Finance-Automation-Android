package com.financeautomation.core

import com.financeautomation.core.utils.AmountInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountInputTest {

    private fun clean(s: String) = AmountInput.clean(s)

    @Test
    fun spacesAreRemovedNoMatterHowManyOrWhere() {
        assertEquals("5", clean(" ".repeat(50) + "5"))
        assertEquals("1234", clean(" 12 34 "))
        assertEquals("1234", clean("\t12\n34\r"))
        assertEquals("", clean(" ".repeat(500)))
    }

    @Test
    fun aPastedRupeeAmountWithIndianGroupingIsCleaned() {
        assertEquals("100000.50", clean("₹ 1,00,000.50"))
        assertEquals("100000", clean("1,00,000"))
    }

    @Test
    fun onlyTwoDecimalsCanBeTyped() {
        assertEquals("1.23", clean("1.2345"))
        assertEquals("0.12", clean("0.129"))
    }

    @Test
    fun aSecondDecimalPointInANumberIsDropped() {
        assertEquals("1.2", clean("1..2"))
        assertEquals("1.25", clean("1.2.5"))
        assertEquals("1.5+2.5", clean("1.5+2.5"))
    }

    @Test
    fun doubledOperatorsKeepTheLastOne() {
        assertEquals("5+3", clean("5++3"))
        assertEquals("5-3", clean("5+-3"))
        assertEquals("5*3", clean("5+*3"))
        assertEquals("5/3", clean("5*/3"))
    }

    @Test
    fun noLeadingMultiplyDivideOrPlus() {
        assertEquals("5", clean("*5"))
        assertEquals("5", clean("/5"))
        assertEquals("5", clean("+5"))
        assertEquals("-5", clean("-5"))
        assertEquals("-5", clean("--5"))
        assertEquals("-5", clean("-*5"))
    }

    @Test
    fun bracketsAreLimitedToDepthThreeAndNeverCloseMoreThanTheyOpen() {
        assertEquals("(((1)))", clean("((((1))))"))
        assertEquals("5", clean(")5"))
        assertEquals("(5)", clean("(5))"))
        assertEquals("(5)", clean("(*5)"))
        assertEquals("(1+2)*3", clean("(1+2)*3"))
        assertEquals("5", clean("5()"))
    }

    @Test
    fun anyUnicodeDigitBecomesAnAsciiDigit() {
        assertEquals("123", clean("١٢٣"))
        assertEquals("456", clean("۴۵۶"))
        assertEquals("789", clean("٧٨٩"))
    }

    @Test
    fun aVeryLongNumberIsCutAtTenIntegerDigits() {
        assertEquals("1234567890", clean("1234567890".repeat(4)))
        assertEquals("1234567890.12", clean("1234567890.123"))
    }

    @Test
    fun theWholeTextIsCutAtFourteenCharacters() {
        val result = clean("123456789+123456789")
        assertEquals("123456789+1234", result)
        assertEquals(AmountInput.MAX_LENGTH, result.length)
        assertTrue(clean("(((1+2)+(3+4))+((5+6)+(7+8)))").length <= AmountInput.MAX_LENGTH)
    }

    @Test
    fun leadingZerosAreCollapsed() {
        assertEquals("7", clean("007"))
        assertEquals("0", clean("000"))
        assertEquals("0.5", clean("00.5"))
        assertEquals("10", clean("010"))
        assertEquals("5+7", clean("5+007"))
    }

    @Test
    fun zeroWidthAndDirectionMarksAreRemoved() {
        assertEquals("5+3", clean("5​+‎3‏‮﻿"))
        assertEquals("12", clean("​1‌2‍"))
    }

    @Test
    fun timesAndDivideSignsBecomeOperators() {
        assertEquals("5*3", clean("5x3"))
        assertEquals("5*3", clean("5X3"))
        assertEquals("5*3", clean("5×3"))
        assertEquals("6/2", clean("6÷2"))
        assertEquals("6-2", clean("6−2"))
    }

    @Test
    fun theLetterXIsATimesSignOnlyBetweenTwoOperands() {
        assertEquals("5", clean("5 for tax"))
        assertEquals("5", clean("5x"))
        assertEquals("3", clean("x3"))
        assertEquals("5*3", clean("5 x 3"))
        assertEquals("(2+3)*4", clean("(2+3)x4"))
        assertEquals("5*3", clean("5xx3"))
        assertEquals("5", clean("max 5"))
        assertEquals("5", clean("5 max"))
    }

    @Test
    fun lettersCurrencyAndCommasAreDropped() {
        assertEquals("15", clean("1e5"))
        assertEquals("", clean("abc"))
        assertEquals("100", clean("Rs 1,0,0"))
    }

    @Test
    fun cleaningIsStableWhenRunTwice() {
        for (s in listOf("5++3", "((((1))))", "₹ 1,00,000.50", "1..2", "x5", "123456789+123456789", "0.5.5")) {
            val once = clean(s)
            assertEquals(s, once, clean(once))
        }
    }
}
