package com.financeautomation.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountOutcomeTest {

    private val evaluator = ExpressionEvaluator()
    private fun outcome(s: String) = evaluator.evaluateDetailed(s)

    @Test
    fun validExpressionsGiveTheirValueRoundedHalfUp() {
        assertEquals(AmountOutcome.Value(100000.5), outcome("100000.50"))
        assertEquals(AmountOutcome.Value(2.0), outcome("5-3"))
        assertEquals(AmountOutcome.Value(1.0), outcome("(((1)))"))
        assertEquals(AmountOutcome.Value(1.67), outcome("5/3"))
        assertEquals(AmountOutcome.Value(0.63), outcome("5/8"))
        assertEquals(AmountOutcome.Value(0.13), outcome("1/8"))
    }

    @Test
    fun emptyAndUnfinishedTextShowNoError() {
        assertEquals(AmountOutcome.Empty, outcome(""))
        for (s in listOf("5+", "5*", "(5", "((5+3)", ".", "-")) {
            assertEquals(s, AmountOutcome.Incomplete, outcome(s))
            assertFalse(s, outcome(s).isError)
        }
    }

    @Test
    fun unreadableTextIsAnErrorAndNeverAValue() {
        assertEquals(AmountOutcome.Unreadable, outcome("5/0"))
        assertEquals(AmountOutcome.Unreadable, outcome("5)"))
        assertEquals(AmountOutcome.Unreadable, outcome("5(3)"))
        assertTrue(outcome("5/0").isError)
    }

    @Test
    fun zeroAndNegativeResultsAreNotPositive() {
        assertEquals(AmountOutcome.NotPositive, outcome("0"))
        assertEquals(AmountOutcome.NotPositive, outcome("0.00"))
        assertEquals(AmountOutcome.NotPositive, outcome("-5"))
        assertEquals(AmountOutcome.NotPositive, outcome("5-5"))
        assertEquals(AmountOutcome.NotPositive, outcome("2-9"))
    }

    @Test
    fun aResultAboveTheMaximumIsTooLarge() {
        assertEquals(AmountOutcome.TooLarge, outcome("9999999999*99"))
        assertEquals(AmountOutcome.Value(9999999999.0), outcome("9999999999"))
    }

    /**
     * The Friend paid, Repaid, Income return and Add subscription sheets keep the amount as plain local text and
     * call exactly this pair: AmountField cleans every edit with AmountInput, the sheet enables Save only for a
     * Value outcome. The Quick Log, custom share and Budget holders are covered in their own tests.
     */
    @Test
    fun sheetsThatKeepLocalAmountTextStoreCleanTextAndOnlySaveAValue() {
        val cases = listOf(
            Triple(" ".repeat(50) + "5", "5", true),
            Triple(" 12 34 ", "1234", true),
            Triple("₹ 1,00,000.50", "100000.50", true),
            Triple("1.2345", "1.23", true),
            Triple("5++3", "5+3", true),
            Triple("((((1))))", "(((1)))", true),
            Triple("1..2", "1.2", true),
            Triple("١٢٣", "123", true),
            Triple("1234567890".repeat(4), "1234567890", true),
            Triple("007", "7", true),
            Triple("*5", "5", true),
            Triple("5​+‎3", "5+3", true),
            Triple("5+", "5+", false),
            Triple("0", "0", false),
            Triple("5/0", "5/0", false),
            Triple("9999999999*99", "9999999999*99", false),
            Triple("", "", false)
        )
        for ((typed, expectedText, canSave) in cases) {
            val stored = com.financeautomation.core.utils.AmountInput.clean(typed)
            assertEquals(typed, expectedText, stored)
            assertEquals(typed, canSave, evaluator.evaluateDetailed(stored) is AmountOutcome.Value)
        }
    }

    @Test
    fun evaluateKeepsItsOldContract() {
        assertEquals(0.0, evaluator.evaluate("0")!!, 0.0)
        assertNull(evaluator.evaluate("-5"))
        assertNull(evaluator.evaluate("5/0"))
        assertEquals(200.0, evaluator.evaluate("120+80")!!, 0.0)
    }
}
