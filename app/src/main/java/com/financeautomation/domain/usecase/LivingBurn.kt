package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import java.time.YearMonth
import java.time.ZoneId

/**
 * The single definition of "living burn", shared by the Dashboard, budgets and the month burn widget so the
 * figures can never diverge.
 *
 * Rule: the sum of My share over non-void transactions in the month window, leaving out every category that is
 * excluded from living burn in Settings, plus Savings and Transfer which are always excluded.
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
        txn.timestamp in window && txn.status != TransactionStatus.VOID && !isExcluded(txn.category, excludedNames)

    /** Total living burn inside [window]. */
    fun total(transactions: List<Transaction>, window: LongRange, excludedNames: Set<String>): Double =
        transactions.filter { counts(it, window, excludedNames) }.sumOf { it.myShare }

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
