package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.Transaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** A payment that looks like a subscription the owner is not tracking yet. */
data class RecurringSuggestion(
    val name: String,
    val amount: Double,
    val frequency: SubscriptionFrequency,
    val anchorEpochDay: Long,
    val account: String,
    val category: String,
    /** Normalised description; pass it to dismiss the suggestion. */
    val key: String = normalizeRecurringKey(name)
)

/** Lower-cased description with digits and white space removed, used to group repeating payments. */
fun normalizeRecurringKey(description: String): String =
    description.lowercase().filterNot { it.isDigit() || it.isWhitespace() }

/**
 * Finds payments that repeat and are not yet tracked as subscriptions.
 *
 * Rules:
 * - Looks at non-void transactions; weekly and monthly payments within the last 6 months, yearly payments within
 *   the last 15 months (two yearly payments cannot both fall inside 6 months).
 * - Payments are grouped by the normalised description (case, digits and spaces removed). Inside a group, amounts
 *   must be within 1 percent of each other. A group whose amounts vary more counts as a "varying bill" only when it
 *   has at least 4 occurrences and every amount is within 25 percent of the median.
 * - Payments on the same day count once. The gaps between consecutive occurrences must all be about 7 days
 *   (tolerance 3), 30 days (tolerance 4) or 365 days (tolerance 7).
 * - At least 3 occurrences for weekly and monthly, 2 for yearly.
 * - Anything whose description matches a subscription name or keyword, and anything in [dismissed], is skipped.
 * Pure Kotlin with zero Android framework dependencies.
 */
class RecurringDetector(private val zone: ZoneId = LivingBurn.KOLKATA) {

    fun detect(
        transactions: List<Transaction>,
        subscriptions: List<Subscription>,
        dismissed: Set<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<RecurringSuggestion> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val shortFrom = today.minusMonths(SHORT_LOOKBACK_MONTHS).toEpochDay()
        val longFrom = today.minusMonths(YEARLY_LOOKBACK_MONTHS).toEpochDay()
        val todayDay = today.toEpochDay()

        val candidates = transactions
            .filter { it.status != TransactionStatus.VOID && it.totalAmount > 0.0 }
            .map { Occurrence(it, Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toEpochDay()) }
            .filter { it.day in longFrom..todayDay }

        val tokens = subscriptions.flatMap { it.matchTokens }
        val keyedGroups = candidates
            .groupBy { normalizeRecurringKey(it.txn.merchant) }
            .filterKeys { it.isNotEmpty() && it !in dismissed }

        val suggestions = mutableListOf<RecurringSuggestion>()
        for ((_, group) in keyedGroups) {
            val latest = group.maxByOrNull { it.txn.timestamp } ?: continue
            val merchantText = latest.txn.merchant.lowercase()
            if (tokens.any { merchantText.contains(it) }) continue

            val series = amountSeries(group)
            for (occurrences in series) {
                val recent = occurrences.filter { it.day >= shortFrom }
                val suggestion = classify(recent, setOf(SubscriptionFrequency.WEEKLY, SubscriptionFrequency.MONTHLY))
                    ?: classify(occurrences, setOf(SubscriptionFrequency.YEARLY))
                    ?: continue
                suggestions.add(suggestion)
                break
            }
        }
        return suggestions.sortedWith(compareBy({ it.name.lowercase() }, { it.frequency }))
    }

    private class Occurrence(val txn: Transaction, val day: Long)

    /** Splits a group into amount clusters (within 1 percent); the whole group is offered last as a varying bill. */
    private fun amountSeries(group: List<Occurrence>): List<List<Occurrence>> {
        val sorted = group.sortedBy { it.txn.totalAmount }
        val clusters = mutableListOf<MutableList<Occurrence>>()
        for (occ in sorted) {
            val current = clusters.lastOrNull()
            if (current != null && occ.txn.totalAmount <= current.first().txn.totalAmount * (1.0 + AMOUNT_TOLERANCE)) {
                current.add(occ)
            } else {
                clusters.add(mutableListOf(occ))
            }
        }
        val result = clusters.sortedByDescending { it.size }.toMutableList<List<Occurrence>>()
        if (clusters.size > 1) result.add(sorted)
        return result
    }

    private fun classify(occurrences: List<Occurrence>, allowed: Set<SubscriptionFrequency>): RecurringSuggestion? {
        // Same-day payments count once
        val byDay = occurrences.groupBy { it.day }.mapNotNull { (_, list) -> list.minByOrNull { it.txn.timestamp } }
            .sortedBy { it.day }
        if (byDay.size < 2) return null

        val amounts = byDay.map { it.txn.totalAmount }
        val varying = amounts.max() > amounts.min() * (1.0 + AMOUNT_TOLERANCE)
        if (varying) {
            if (byDay.size < VARYING_MIN_OCCURRENCES) return null
            val median = amounts.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
            if (amounts.any { abs(it - median) > median * VARYING_BAND }) return null
        }

        val gaps = byDay.zipWithNext { a, b -> b.day - a.day }
        val frequency = when {
            gaps.all { abs(it - WEEK) <= WEEK_TOLERANCE } -> SubscriptionFrequency.WEEKLY
            gaps.all { abs(it - MONTH) <= MONTH_TOLERANCE } -> SubscriptionFrequency.MONTHLY
            gaps.all { abs(it - YEAR) <= YEAR_TOLERANCE } -> SubscriptionFrequency.YEARLY
            else -> return null
        }
        if (frequency !in allowed) return null
        val needed = if (frequency == SubscriptionFrequency.YEARLY) YEARLY_MIN_OCCURRENCES else MIN_OCCURRENCES
        if (byDay.size < needed) return null

        val last = byDay.last()
        val amount = amounts.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
        return RecurringSuggestion(
            name = last.txn.merchant.trim(),
            amount = Math.round(amount * 100.0) / 100.0,
            frequency = frequency,
            anchorEpochDay = last.day,
            account = last.txn.account,
            category = last.txn.category
        )
    }

    private companion object {
        const val SHORT_LOOKBACK_MONTHS = 6L
        const val YEARLY_LOOKBACK_MONTHS = 15L
        const val AMOUNT_TOLERANCE = 0.01
        const val VARYING_BAND = 0.25
        const val VARYING_MIN_OCCURRENCES = 4
        const val MIN_OCCURRENCES = 3
        const val YEARLY_MIN_OCCURRENCES = 2
        const val WEEK = 7L
        const val WEEK_TOLERANCE = 3L
        const val MONTH = 30L
        const val MONTH_TOLERANCE = 4L
        const val YEAR = 365L
        const val YEAR_TOLERANCE = 7L
    }
}
