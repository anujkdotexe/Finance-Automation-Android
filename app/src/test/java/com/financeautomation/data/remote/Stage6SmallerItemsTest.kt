package com.financeautomation.data.remote

import com.financeautomation.core.utils.AmountInput
import com.financeautomation.domain.usecase.AmountOutcome
import com.financeautomation.domain.usecase.ExpressionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage6SmallerItemsTest {

    @Test
    fun testStage6d_amountInputCleaner_keepsSlashOperatorForMathExpressions() {
        // AmountInput supports math expressions (+, -, *, /)
        val cleaned = AmountInput.clean("9100/")
        assertEquals("Slash operator must be preserved as expression division operator", "9100/", cleaned)

        // ExpressionEvaluator recognizes trailing operator as Incomplete (no error shown yet, Save disabled)
        val outcome = ExpressionEvaluator().evaluateDetailed(cleaned)
        assertEquals(AmountOutcome.Incomplete, outcome)
    }

    @Test
    fun testStage6f_nextDueSubscriptionFormula_filtersStrictlyFutureOrToday() {
        val formula = DashboardFormulaBuilder.buildNextDueSubscriptionFormula()
        // Must contain condition dateCol >= TODAY()
        assertTrue("Formula must filter with >=TODAY()", formula.contains(">=TODAY()"))
        assertTrue("Formula must fall back to None upcoming on error", formula.contains("\"None upcoming\""))
    }
}
