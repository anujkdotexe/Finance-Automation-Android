package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BudgetProgressUseCaseTest {

    private val useCase = BudgetProgressUseCase()
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun ts(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 12, 0, 0, 0, kolkata).toInstant().toEpochMilli()

    private fun txn(
        category: String,
        share: Double,
        at: Long = ts(2026, 11, 10),
        status: TransactionStatus = TransactionStatus.PERSONAL,
        total: Double = share
    ) = Transaction(
        id = "t_${category}_${share}_$at", timestamp = at, dateSerial = 0, timeSerial = 0.0, account = "SBI",
        merchant = "m", category = category, totalAmount = total, myShare = share, owedToMe = total - share,
        status = status
    )

    private fun budget(category: String, amount: Double) = Budget(category, amount, 0L)

    private fun category(name: String, excluded: Boolean = false) = Category(
        id = name, name = name, isDefault = false, isExcludedFromLivingBurn = excluded, isArchived = false, displayOrder = 0
    )

    private fun stateAtPercent(percent: Int): BudgetState {
        val progress = useCase.forCategory(
            "Food", 2026, 11, listOf(budget("Food", 100.0)), listOf(txn("Food", percent.toDouble())), emptyList()
        )!!
        return progress.state
    }

    @Test
    fun statesAt79_80_100_101Percent() {
        assertEquals(BudgetState.NORMAL, stateAtPercent(79))
        assertEquals(BudgetState.WARNING, stateAtPercent(80))
        assertEquals(BudgetState.WARNING, stateAtPercent(100))
        assertEquals(BudgetState.OVER, stateAtPercent(101))
    }

    @Test
    fun boundariesAreExactEvenWhenThePercentIsNotRepresentableInBinary() {
        // 0.8 * 1234.5 in binary floating point is slightly off; the state must still be WARNING at exactly 80 percent
        val budget = budget("Food", 1234.5)
        val exact80 = useCase.forCategory("Food", 2026, 11, listOf(budget), listOf(txn("Food", 987.6)), emptyList())!!
        assertEquals(BudgetState.WARNING, exact80.state)
        val under = useCase.forCategory("Food", 2026, 11, listOf(budget), listOf(txn("Food", 987.5)), emptyList())!!
        assertEquals(BudgetState.NORMAL, under.state)
    }

    @Test
    fun progressMathSumsMyShareAndRoundsPercentToTwoDecimals() {
        val progress = useCase.forCategory(
            "Food", 2026, 11,
            listOf(budget("Food", 3000.0)),
            listOf(txn("Food", 500.0, total = 1000.0), txn("Food", 250.5), txn("Transit", 900.0)),
            emptyList()
        )!!
        assertEquals("Food", progress.category)
        assertEquals(3000.0, progress.budget, 0.0)
        assertEquals(750.5, progress.spent, 0.0001)
        assertEquals(25.02, progress.percentUsed, 0.0)
        assertEquals(BudgetState.NORMAL, progress.state)
    }

    @Test
    fun aCategoryWithNoBudgetReturnsNull() {
        val result = useCase.forCategory("Transit", 2026, 11, listOf(budget("Food", 100.0)), listOf(txn("Transit", 50.0)), emptyList())
        assertNull(result)
    }

    @Test
    fun removedBudgetsAndZeroBudgetsAreIgnored() {
        val budgets = listOf(
            Budget("Food", 100.0, 0L, deleted = true),
            Budget("Transit", 0.0, 0L),
            budget("Health", 100.0)
        )
        val result = useCase.forMonth(2026, 11, budgets, emptyList(), emptyList())
        assertEquals(listOf("Health"), result.map { it.category })
    }

    @Test
    fun aBudgetWithNoSpendingIsZeroPercentNormal() {
        val progress = useCase.forCategory("Food", 2026, 11, listOf(budget("Food", 100.0)), emptyList(), emptyList())!!
        assertEquals(0.0, progress.spent, 0.0)
        assertEquals(0.0, progress.percentUsed, 0.0)
        assertEquals(BudgetState.NORMAL, progress.state)
    }

    @Test
    fun voidRowsAndOtherMonthsDoNotCount() {
        val progress = useCase.forCategory(
            "Food", 2026, 11, listOf(budget("Food", 100.0)),
            listOf(
                txn("Food", 40.0),
                txn("Food", 500.0, status = TransactionStatus.VOID),
                txn("Food", 500.0, at = ts(2026, 10, 31)),
                txn("Food", 500.0, at = ts(2026, 12, 1))
            ),
            emptyList()
        )!!
        assertEquals(40.0, progress.spent, 0.0001)
    }

    @Test
    fun categoryNamesMatchIgnoringCaseAndSpaces() {
        val progress = useCase.forCategory(
            " food ", 2026, 11, listOf(budget("Food", 100.0)), listOf(txn("FOOD", 30.0), txn("food ", 20.0)), emptyList()
        )!!
        assertEquals(50.0, progress.spent, 0.0001)
    }

    @Test
    fun savingsTransferAndUserExcludedCategoriesCountAsZeroSpentLikeLivingBurn() {
        val budgets = listOf(budget("Savings", 100.0), budget("Transfer", 100.0), budget("Rent", 100.0), budget("Food", 100.0))
        val result = useCase.forMonth(
            2026, 11, budgets,
            listOf(txn("Savings", 500.0), txn("Transfer", 500.0), txn("Rent", 500.0), txn("Food", 90.0)),
            listOf(category("Rent", excluded = true), category("Food"))
        ).associateBy { it.category }
        assertEquals(0.0, result.getValue("Savings").spent, 0.0)
        assertEquals(0.0, result.getValue("Transfer").spent, 0.0)
        assertEquals(0.0, result.getValue("Rent").spent, 0.0)
        assertEquals(BudgetState.NORMAL, result.getValue("Rent").state)
        assertEquals(BudgetState.WARNING, result.getValue("Food").state)
    }

    @Test
    fun theMonthWindowFollowsAsiaKolkata() {
        val lastSecond = ZonedDateTime.of(2026, 11, 30, 23, 59, 59, 0, kolkata).toInstant().toEpochMilli()
        val nextMonth = ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        val progress = useCase.forCategory(
            "Food", 2026, 11, listOf(budget("Food", 100.0)),
            listOf(txn("Food", 10.0, at = lastSecond), txn("Food", 99.0, at = nextMonth)), emptyList()
        )!!
        assertEquals(10.0, progress.spent, 0.0001)
    }
}
