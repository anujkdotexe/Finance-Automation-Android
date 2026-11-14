package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Budget
import com.financeautomation.testutil.ParityFixture
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

/** Living burn, Insights, the widget and budgets read the shared fixture through LivingBurn; documented differences are asserted. */
class TotalsParityTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val ym = YearMonth.now(zone)
    private val window = LivingBurn.monthWindow(ym.year, ym.monthValue)
    private val none = emptySet<String>()

    @Test
    fun theSharedFunctionGivesTheOwnerFigure() {
        assertEquals(ParityFixture.LIVING_BURN, LivingBurn.total(ParityFixture.transactions, window, none, ParityFixture.inflows), 0.001)
    }

    @Test
    fun insightsAndTheWidgetAgreeWithIt() {
        assertEquals(ParityFixture.LIVING_BURN, InsightsCalculator().total(ParityFixture.transactions, emptyList(), ym, ParityFixture.inflows), 0.001)
        val widget = MonthBurnSummaryUseCase()(ParityFixture.transactions, emptyList(), 0, inflows = ParityFixture.inflows)
        assertEquals(ParityFixture.LIVING_BURN, widget.livingBurn, 0.001)
    }

    @Test
    fun budgetsSeeTheGrossCategoryNotTheNetTotal() {
        // Documented difference: a refund has no category, so a budget counts the 200 spent in Food & Drink
        val progress = BudgetProgressUseCase().forMonth(
            year = ym.year, month = ym.monthValue, budgets = listOf(Budget("Food & Drink", 1000.0, 0L)),
            transactions = ParityFixture.transactions, categories = emptyList()
        ).single()
        assertEquals(200.0, progress.spent, 0.001)
    }

    @Test
    fun theUnconfirmedLineIsTheReviewRowAndNothingElse() {
        val u = LivingBurn.unconfirmed(ParityFixture.transactions, none)
        assertEquals(ParityFixture.UNCONFIRMED_COUNT, u.count)
        assertEquals(ParityFixture.UNCONFIRMED_AMOUNT, u.amount, 0.001)
    }

    @Test
    fun categoryNamesAreMergedByOneNormaliser() {
        val base = ParityFixture.transactions.first { it.id == "plain" }
        val rows = listOf(base, base.copy(id = "x", category = "  FOOD & drink ", myShare = 5.0))
        val by = LivingBurn.byCategory(rows, window, none)
        assertEquals(1, by.size)
        assertEquals(105.0, by.values.single(), 0.001)
    }
}
