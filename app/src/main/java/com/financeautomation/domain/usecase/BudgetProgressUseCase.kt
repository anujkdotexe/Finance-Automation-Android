package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId

/** How far a category is through its monthly budget. */
enum class BudgetState {
    /** Under 80 percent used. */
    NORMAL,

    /** From 80 percent up to and including 100 percent used. */
    WARNING,

    /** More than 100 percent used. */
    OVER
}

data class BudgetProgress(
    val category: String,
    val budget: Double,
    val spent: Double,
    val percentUsed: Double,
    val state: BudgetState
)

/**
 * Compares the month's spending per category with its budget.
 * Spending uses the living burn rule from [LivingBurn]: My share, void rows left out, and categories excluded from
 * living burn plus Savings and Transfer count as zero spent (they never raise a budget alert).
 * Pure Kotlin with zero Android framework dependencies.
 */
class BudgetProgressUseCase(private val zone: ZoneId = LivingBurn.KOLKATA) {

    /** Progress for every budget of the month, in the order of [budgets]. */
    fun forMonth(
        year: Int,
        month: Int,
        budgets: List<Budget>,
        transactions: List<Transaction>,
        categories: List<Category>
    ): List<BudgetProgress> {
        val window = LivingBurn.monthWindow(year, month, zone)
        val excluded = LivingBurn.excludedNames(categories)
        val spentByCategory = LivingBurn.byCategory(transactions, window, excluded)
            .entries
            .groupBy({ it.key.trim().lowercase() }, { it.value })
            .mapValues { (_, v) -> v.sum() }
        return budgets
            .filter { !it.deleted && it.monthlyAmount > 0.0 }
            .map { progress(it, spentByCategory[it.category.trim().lowercase()] ?: 0.0) }
    }

    /** Progress of one category, or null when it has no budget. */
    fun forCategory(
        category: String,
        year: Int,
        month: Int,
        budgets: List<Budget>,
        transactions: List<Transaction>,
        categories: List<Category>
    ): BudgetProgress? {
        val key = category.trim().lowercase()
        return forMonth(year, month, budgets, transactions, categories)
            .firstOrNull { it.category.trim().lowercase() == key }
    }

    private fun progress(budget: Budget, spent: Double): BudgetProgress {
        val spentExact = BigDecimal.valueOf(spent)
        val budgetExact = BigDecimal.valueOf(budget.monthlyAmount)
        // Compare spent * 100 with budget * 80 and budget * 100 so 80 and 100 percent fall on the right side exactly
        val spentTimes100 = spentExact.multiply(HUNDRED)
        val state = when {
            spentTimes100 > budgetExact.multiply(HUNDRED) -> BudgetState.OVER
            spentTimes100 >= budgetExact.multiply(EIGHTY) -> BudgetState.WARNING
            else -> BudgetState.NORMAL
        }
        val percent = spentTimes100.divide(budgetExact, 2, RoundingMode.HALF_UP).toDouble()
        return BudgetProgress(
            category = budget.category,
            budget = budget.monthlyAmount,
            spent = spent,
            percentUsed = percent,
            state = state
        )
    }

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal(100)
        val EIGHTY: BigDecimal = BigDecimal(80)
    }
}
