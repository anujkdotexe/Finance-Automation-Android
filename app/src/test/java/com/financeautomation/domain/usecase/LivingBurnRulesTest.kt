package com.financeautomation.domain.usecase

import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

/** Owner rules: living burn is the money actually burned. Refunds reduce it; rows awaiting review are not counted. */
class LivingBurnRulesTest {

    private val window = LivingBurn.monthWindow(2026, 11)
    private val inMonth = window.first + 86_400_000L * 3

    private fun txn(
        id: String, share: Double, category: String = "Food & Drink",
        status: TransactionStatus = TransactionStatus.PERSONAL, review: ReviewState = ReviewState.CONFIRMED
    ) = Transaction(
        id = id, timestamp = inMonth, dateSerial = 46000, timeSerial = 0.5, account = "SBI", merchant = id, tripTag = "",
        category = category, totalAmount = share, companions = "Personal", myShare = share, owedToMe = 0.0,
        status = status, sourcePackage = "manual", reviewState = review, syncState = SyncState.SYNCED,
        sheetRowIndex = null, isPossibleDuplicate = false
    )

    private fun inflow(type: String, amount: Double, status: String = "CONFIRMED", ts: Long = inMonth) =
        Inflow(id = "i_$type$amount", timestamp = ts, amount = amount, account = "SBI", inflowType = type, source = "", note = "", status = status)

    private val none = emptySet<String>()

    @Test
    fun rowsInReviewAreNotCounted() {
        val rows = listOf(txn("a", 100.0), txn("b", 40.0, review = ReviewState.NEEDS_REVIEW))
        assertEquals(100.0, LivingBurn.total(rows, window, none), 0.001)
        assertEquals(mapOf("Food & Drink" to 100.0), LivingBurn.byCategory(rows, window, none))
    }

    @Test
    fun unconfirmedReportsTheCountAndAmountOfReviewRows() {
        val rows = listOf(
            txn("a", 100.0),
            txn("b", 40.0, review = ReviewState.NEEDS_REVIEW),
            txn("c", 10.0, review = ReviewState.NEEDS_REVIEW),
            txn("v", 5.0, status = TransactionStatus.VOID, review = ReviewState.NEEDS_REVIEW),
            txn("s", 70.0, category = "Savings", review = ReviewState.NEEDS_REVIEW)
        )
        val u = LivingBurn.unconfirmed(rows, none)
        assertEquals(2, u.count)
        assertEquals(50.0, u.amount, 0.001)
    }

    @Test
    fun refundsReduceTheTotal() {
        val rows = listOf(txn("a", 500.0))
        val inflows = listOf(inflow("Refund / Return", 120.0), inflow("Income / Salary", 9000.0))
        assertEquals(380.0, LivingBurn.total(rows, window, none, inflows), 0.001)
    }

    @Test
    fun voidRefundsAndRefundsInOtherMonthsDoNotCount() {
        val rows = listOf(txn("a", 500.0))
        val inflows = listOf(
            inflow("Refund / Return", 120.0, status = "VOID"),
            inflow("Refund / Return", 80.0, ts = window.first - 1000L)
        )
        assertEquals(500.0, LivingBurn.total(rows, window, none, inflows), 0.001)
    }

    @Test
    fun theTotalNeverGoesBelowZeroButCategoryRowsKeepTheirOwnFigures() {
        val rows = listOf(txn("a", 100.0))
        val inflows = listOf(inflow("Refund / Return", 300.0))
        assertEquals(0.0, LivingBurn.total(rows, window, none, inflows), 0.001)
        assertEquals(mapOf("Food & Drink" to 100.0), LivingBurn.byCategory(rows, window, none))
    }

    @Test
    fun everyConsumerFollowsTheSameRules() {
        val rows = listOf(txn("a", 500.0), txn("b", 40.0, review = ReviewState.NEEDS_REVIEW))
        val inflows = listOf(inflow("Refund / Return", 100.0))
        val cats = emptyList<Category>()
        val month = YearMonth.of(2026, 11)
        assertEquals(400.0, InsightsCalculator().total(rows, cats, month, inflows), 0.001)
        assertEquals(
            400.0,
            MonthBurnSummaryUseCase()(rows, cats, 0, nowMillis = inMonth, inflows = inflows).livingBurn,
            0.001
        )
    }
}
