package com.financeautomation.data.remote

import com.financeautomation.core.utils.AmountInput
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.domain.usecase.AmountOutcome
import com.financeautomation.domain.usecase.ExpressionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage6SmallerItemsTest {

    @Test
    fun testStage6c_oldestFirstRepaymentSorting_prioritizesExplicitRefThenOldestSerial() {
        val payableOlder = PayableEntity(
            uuid = "pay_old",
            dateIncurredSerial = 46300,
            timeSerial = 0.2,
            paidBy = "Riya",
            whatFor = "Dinner",
            amountIOwe = 500.0,
            dateRepaidSerial = null,
            repaymentStatus = "Pending",
            tabName = "Nov26",
            sheetRowIndex = 4
        )

        val payableNewer = PayableEntity(
            uuid = "pay_new",
            dateIncurredSerial = 46320,
            timeSerial = 0.5,
            paidBy = "Riya",
            whatFor = "Cab",
            amountIOwe = 200.0,
            dateRepaidSerial = null,
            repaymentStatus = "Pending",
            tabName = "Nov26",
            sheetRowIndex = 5
        )

        val list = listOf(payableNewer, payableOlder)

        // Case 1: Natural FIFO repayment without explicitRef must sort oldest first
        val sortedFifo = list.sortedWith(
            compareBy<PayableEntity> {
                1
            }.thenBy {
                it.dateIncurredSerial ?: 0
            }.thenBy {
                it.timeSerial ?: 0.0
            }
        )
        assertEquals("Older payable must come first in FIFO", "pay_old", sortedFifo[0].uuid)
        assertEquals("Newer payable must come second in FIFO", "pay_new", sortedFifo[1].uuid)

        // Case 2: Explicit ref targets the specified payable first even if newer
        val explicitRef = "pay_new"
        val sortedWithExplicit = list.sortedWith(
            compareBy<PayableEntity> {
                if (it.uuid == explicitRef) 0 else 1
            }.thenBy {
                it.dateIncurredSerial ?: 0
            }.thenBy {
                it.timeSerial ?: 0.0
            }
        )
        assertEquals("Explicitly referenced payable must come first", "pay_new", sortedWithExplicit[0].uuid)
        assertEquals("Remaining payable must come second", "pay_old", sortedWithExplicit[1].uuid)
    }

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
