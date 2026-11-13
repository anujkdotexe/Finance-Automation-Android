package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import java.time.YearMonth
import java.time.ZoneId

/**
 * The single definition of "living burn", shared by the Dashboard, budgets and the month burn widget so the
 * figures can never diverge.
 *
 * Rule: the money actually burned in the month window. It is the sum of My share over non-void transactions, leaving
 * out every category excluded from living burn in Settings plus Savings and Transfer, which are always excluded.
 * Rows still waiting in review are not counted (they are reported separately by [unconfirmed]). Refund inflows of
 * the month are subtracted from the total, which never goes below zero. Inflows carry no category, so a refund
 * reduces the total only, not the per category figures.
 * Pure Kotlin with zero Android framework dependencies.
 */
object LivingBurn {

    val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")

    /** Lower-cased, trimmed names of the categories the owner excluded from living burn. */
    fun excludedNames(categories: List<Category>): Set<String> =
        categories.filter { it.isExcludedFromLivingBurn }.map { it.name.trim().lowercase() }.toSet()

    /** True for user-excluded categories and for Savings and Transfer. */
    fun isExcluded(category: String, excludedNames: Set<String>): Boolean {
        val trimmed = category.trim().lowercase()
        return excludedNames.contains(trimmed) ||
            trimmed == CategoryConstants.CATEGORY_SAVINGS.lowercase() ||
            trimmed == "transfer"
    }

    /** Start (inclusive) and end (exclusive) epoch milliseconds of a calendar month in [zone]. */
    fun monthWindow(year: Int, month: Int, zone: ZoneId = KOLKATA): LongRange {
        val ym = YearMonth.of(year, month)
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until end
    }

    /** True when the transaction counts toward living burn inside [window]. */
    fun counts(txn: Transaction, window: LongRange, excludedNames: Set<String>): Boolean =
        txn.timestamp in window && txn.status != TransactionStatus.VOID &&
            txn.reviewState != ReviewState.NEEDS_REVIEW && !isExcluded(txn.category, excludedNames)

    /** Total living burn inside [window]. */
    fun total(
        transactions: List<Transaction>,
        window: LongRange,
        excludedNames: Set<String>,
        inflows: List<com.financeautomation.domain.model.Inflow> = emptyList()
    ): Double {
        val spent = transactions.filter { counts(it, window, excludedNames) }.sumOf { it.myShare }
        return maxOf(0.0, spent - refundTotal(inflows, window))
    }

    /** True for an income entry of the refund kind ("Refund / Return" or "Refund"). */
    fun isRefund(inflowType: String): Boolean {
        val t = inflowType.trim()
        return t.equals("Refund / Return", ignoreCase = true) || t.equals("Refund", ignoreCase = true)
    }

    /** Refund inflows inside [window] that are not void. */
    fun refundTotal(inflows: List<com.financeautomation.domain.model.Inflow>, window: LongRange): Double =
        inflows.filter {
            it.timestamp in window && !it.status.equals("VOID", ignoreCase = true) && isRefund(it.inflowType)
        }.sumOf { it.amount }

    /** Rows waiting in review: how many and how much. Not part of burn; shown as their own line. */
    data class Unconfirmed(val count: Int, val amount: Double)

    fun unconfirmed(transactions: List<Transaction>, excludedNames: Set<String>): Unconfirmed {
        val rows = transactions.filter {
            it.reviewState == ReviewState.NEEDS_REVIEW && it.status != TransactionStatus.VOID &&
                !isExcluded(it.category, excludedNames)
        }
        return Unconfirmed(rows.size, rows.sumOf { it.myShare })
    }

    /** Average living burn per elapsed day; zero when there is no burn or no elapsed day. */
    fun dailyAverage(burn: Double, daysElapsed: Int): Double =
        if (daysElapsed > 0 && burn > 0.0) burn / daysElapsed else 0.0

    /** Living burn per category inside [window]; keys are the category names as stored on the transactions. */
    fun byCategory(transactions: List<Transaction>, window: LongRange, excludedNames: Set<String>): Map<String, Double> {
        val sums = LinkedHashMap<String, Double>()
        for (txn in transactions) {
            if (counts(txn, window, excludedNames)) {
                sums[txn.category] = (sums[txn.category] ?: 0.0) + txn.myShare
            }
        }
        return sums
    }
}
