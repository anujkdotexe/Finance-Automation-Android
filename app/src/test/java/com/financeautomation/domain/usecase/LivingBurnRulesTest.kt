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

    @Test
    fun archivedExcludedCategoryStaysExcludedWhileArchivedIncludedCategoryCounts() {
        val rows = listOf(
            txn("1", 1000.0, category = "Rent"),
            txn("2", 200.0, category = "Groceries"),
            txn("3", 300.0, category = "Investments"),
            txn("4", 150.0, category = "Dining")
        )
        val categories = listOf(
            Category(id = "c1", name = "Rent", isDefault = false, isExcludedFromLivingBurn = true, isArchived = true, displayOrder = 0),
            Category(id = "c2", name = "Groceries", isDefault = false, isExcludedFromLivingBurn = false, isArchived = true, displayOrder = 1),
            Category(id = "c3", name = "Investments", isDefault = false, isExcludedFromLivingBurn = true, isArchived = false, displayOrder = 2),
            Category(id = "c4", name = "Dining", isDefault = false, isExcludedFromLivingBurn = false, isArchived = false, displayOrder = 3)
        )
        val excludedNames = LivingBurn.excludedNames(categories)

        // Rent (excluded + archived) is excluded.
        // Investments (excluded + active) is excluded.
        // Groceries (not excluded + archived) counts.
        // Dining (not excluded + active) counts.
        // Expected total = 200.0 (Groceries) + 150.0 (Dining) = 350.0.
        val totalBurn = LivingBurn.total(rows, window, excludedNames)
        assertEquals(350.0, totalBurn, 0.001)

        val byCategory = LivingBurn.byCategory(rows, window, excludedNames)
        assertEquals(2, byCategory.size)
        assertEquals(200.0, byCategory["Groceries"] ?: 0.0, 0.001)
        assertEquals(150.0, byCategory["Dining"] ?: 0.0, 0.001)
        assertEquals(null, byCategory["Rent"])
        assertEquals(null, byCategory["Investments"])

        // InsightsCalculator, MonthBurnSummaryUseCase, and BudgetProgressUseCase follow the same rule
        val month = YearMonth.of(2026, 11)
        assertEquals(350.0, InsightsCalculator().total(rows, categories, month), 0.001)

        val widgetSummary = MonthBurnSummaryUseCase()(rows, categories, 0, nowMillis = inMonth)
        assertEquals(350.0, widgetSummary.livingBurn, 0.001)

        val budgets = listOf(
            com.financeautomation.domain.model.Budget(category = "Rent", monthlyAmount = 1500.0, updatedAt = 0L),
            com.financeautomation.domain.model.Budget(category = "Groceries", monthlyAmount = 500.0, updatedAt = 0L)
        )
        val progress = BudgetProgressUseCase().forMonth(2026, 11, budgets, rows, categories)
        // Rent has 0 spent because it is excluded
        assertEquals(0.0, progress.first { it.category == "Rent" }.spent, 0.001)
        // Groceries has 200 spent because it is not excluded
        assertEquals(200.0, progress.first { it.category == "Groceries" }.spent, 0.001)
    }
}
