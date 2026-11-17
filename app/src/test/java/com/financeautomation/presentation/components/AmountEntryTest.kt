package com.financeautomation.presentation.components

import com.financeautomation.domain.usecase.AmountOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AmountEntry is the state of the Friend paid, Repaid a friend, Income return and Add subscription amount boxes.
 * The Quick Log, custom share and Budget holders have the same checks in their own tests.
 */
class AmountEntryTest {

    private data class Case(val typed: String, val storedText: String, val canSave: Boolean, val amount: Double)

    private val cases = listOf(
        Case(" ".repeat(50) + "5", "5", true, 5.0),
        Case(" 12 34 ", "1234", true, 1234.0),
        Case("\u20B9 1,00,000.50", "100000.50", true, 100000.5),
        Case("1.2345", "1.23", true, 1.23),
        Case("5++3", "5+3", true, 8.0),
        Case("((((1))))", "(((1)))", true, 1.0),
        Case("1..2", "1.2", true, 1.2),
        Case("\u0661\u0662\u0663", "123", true, 123.0),
        Case("1234567890".repeat(4), "1234567890", true, 1234567890.0),
        Case("007", "7", true, 7.0),
        Case("*5", "5", true, 5.0),
        Case("5\u200B+\u200E3", "5+3", true, 8.0),
        Case("5+", "5+", false, 0.0),
        Case("0", "0", false, 0.0),
        Case("5/0", "5/0", false, 0.0),
        Case("2-9", "2-9", false, 0.0),
        Case("9999999999*99", "9999999999*99", false, 0.0),
        Case("", "", false, 0.0)
    )

    @Test
    fun hostileTextIsStoredCleanAndSaveIsAllowedOnlyForAReadableAmountAboveZero() {
        for (c in cases) {
            val entry = AmountEntry().onInput(c.typed)
            assertEquals(c.typed, c.storedText, entry.text)
            assertEquals(c.typed, c.canSave, entry.canSave)
            assertEquals(c.typed, c.amount, entry.amountOrZero, 0.0)
        }
    }

    @Test
    fun saveIsOffForEveryNonValueOutcome() {
        assertFalse(AmountEntry("").canSave)
        assertEquals(AmountOutcome.Incomplete, AmountEntry("5+").outcome)
        assertFalse(AmountEntry("5+").canSave)
        assertEquals(AmountOutcome.Unreadable, AmountEntry("5/0").outcome)
        assertFalse(AmountEntry("5/0").canSave)
        assertEquals(AmountOutcome.TooLarge, AmountEntry("9999999999*99").outcome)
        assertFalse(AmountEntry("9999999999*99").canSave)
        assertEquals(AmountOutcome.NotPositive, AmountEntry("0").outcome)
        assertFalse(AmountEntry("0").canSave)
        assertTrue(AmountEntry("120+80").canSave)
    }

    @Test
    fun aGroupedAmountSetByTheSheetItselfIsCleanedLikeTypedText() {
        // The Repaid sheet pre-fills the open balance formatted with Indian grouping
        assertEquals("1250.00", AmountEntry().onInput("1,250.00").text)
        assertEquals("125000.50", AmountEntry().onInput("1,25,000.50").text)
    }
}
