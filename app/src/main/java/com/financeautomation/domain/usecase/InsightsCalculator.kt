package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import java.time.YearMonth
import java.time.ZoneId

/** One slice of the spending donut. [isOther] marks the group made of the smallest categories. */
data class InsightsSlice(
    val category: String,
    val amount: Double,
    val percent: Double,
    val isOther: Boolean = false
)

/** Living burn of one calendar month, for the bar chart. */
data class MonthlyBurn(
    val month: YearMonth,
    val amount: Double
)

/**
 * Numbers behind the Insights screen. Everything goes through [LivingBurn], so these figures follow the same rule as
 * the Dashboard: My share, void rows left out, Savings, Transfer and user-excluded categories left out.
 * Pure Kotlin with zero Android framework dependencies.
 */
class InsightsCalculator(private val zone: ZoneId = LivingBurn.KOLKATA) {

    /**
     * Spending by category for [month], largest first. When there are more than [MAX_SLICES] categories the smallest
     * ones are grouped into a final "Other" slice, so the chart never needs more than [MAX_SLICES] colours.
     */
    fun slices(
        transactions: List<Transaction>,
        categories: List<Category>,
        month: YearMonth,
        otherLabel: String = OTHER_LABEL
    ): List<InsightsSlice> {
        val window = LivingBurn.monthWindow(month.year, month.monthValue, zone)
        val byCategory = LivingBurn.byCategory(transactions, window, LivingBurn.excludedNames(categories))
            .entries
            .groupBy({ it.key.trim().lowercase() }, { it })
            .map { (_, entries) -> entries.first().key.trim() to entries.sumOf { it.value } }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        val total = byCategory.sumOf { it.second }
        if (total <= 0.0) return emptyList()

        val shown = if (byCategory.size > MAX_SLICES) {
            val head = byCategory.take(MAX_SLICES - 1)
            head + (otherLabel to byCategory.drop(MAX_SLICES - 1).sumOf { it.second })
        } else {
            byCategory
        }
        return shown.mapIndexed { index, (name, amount) ->
            InsightsSlice(
                category = name,
                amount = amount,
                percent = amount / total * 100.0,
                isOther = byCategory.size > MAX_SLICES && index == shown.lastIndex
            )
        }
    }

    /** Total shown by the donut for [month]. */
    fun total(
        transactions: List<Transaction>,
        categories: List<Category>,
        month: YearMonth,
        inflows: List<com.financeautomation.domain.model.Inflow> = emptyList()
    ): Double {
        val window = LivingBurn.monthWindow(month.year, month.monthValue, zone)
        return LivingBurn.total(transactions, window, LivingBurn.excludedNames(categories), inflows)
    }

    /** Living burn of the [count] months ending at [endMonth], oldest first; months without spending are zero. */
    fun monthlySeries(
        transactions: List<Transaction>,
        categories: List<Category>,
        endMonth: YearMonth,
        count: Int = SERIES_MONTHS,
        inflows: List<com.financeautomation.domain.model.Inflow> = emptyList()
    ): List<MonthlyBurn> {
        val excluded = LivingBurn.excludedNames(categories)
        return (count - 1 downTo 0).map { back ->
            val month = endMonth.minusMonths(back.toLong())
            val window = LivingBurn.monthWindow(month.year, month.monthValue, zone)
            MonthlyBurn(month, LivingBurn.total(transactions, window, excluded, inflows))
        }
    }

    companion object {
        const val MAX_SLICES = 6
        const val SERIES_MONTHS = 6
        const val OTHER_LABEL = "Other"
    }
}
