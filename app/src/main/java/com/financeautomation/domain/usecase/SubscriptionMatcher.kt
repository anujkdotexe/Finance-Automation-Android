package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.repository.SubscriptionRepository
import kotlin.math.abs

/**
 * Result of matching a captured debit with a subscription.
 * When [duplicateOfTransactionId] is set, the cycle was already answered with an expense and the
 * capture must be flagged as a possible duplicate instead of being linked to the cycle.
 */
data class SubscriptionMatch(
    val subscription: Subscription,
    val cycle: SubscriptionCycle,
    val duplicateOfTransactionId: String? = null
) {
    val isDuplicate: Boolean get() = duplicateOfTransactionId != null
}

/**
 * Matches a captured payment with a subscription cycle.
 * A match needs an active subscription with an open (DUE) cycle due within [WINDOW_DAYS] days of today,
 * an amount equal within 0.01 (or a subscription with a varying amount), and the subscription name
 * or any keyword contained (case-insensitive) in the merchant or alert text.
 * Pure Kotlin.
 */
class SubscriptionMatcher(private val repository: SubscriptionRepository) {

    suspend fun match(amount: Double, merchantText: String, nowEpochDay: Long): SubscriptionMatch? {
        if (amount <= 0.0 || merchantText.isBlank()) return null
        val haystack = merchantText.lowercase()
        val candidates = repository.getCyclesInWindow(nowEpochDay - WINDOW_DAYS, nowEpochDay + WINDOW_DAYS)
            .filter { it.subscription.active }
            .filter { amountMatches(it.subscription, amount) }
            .filter { c -> c.subscription.matchTokens.any { haystack.contains(it) } }

        val open = candidates
            .filter { it.cycle.state == SubscriptionCycleState.DUE }
            .minByOrNull { abs(it.cycle.dueEpochDay - nowEpochDay) }
        if (open != null) return SubscriptionMatch(open.subscription, open.cycle)

        val answered = candidates
            .filter {
                it.cycle.state == SubscriptionCycleState.PAID &&
                    !it.cycle.transactionId.isNullOrBlank() &&
                    abs(it.cycle.dueEpochDay - nowEpochDay) <= DUPLICATE_WINDOW_DAYS
            }
            .minByOrNull { abs(it.cycle.dueEpochDay - nowEpochDay) }
        if (answered != null) {
            return SubscriptionMatch(answered.subscription, answered.cycle, answered.cycle.transactionId)
        }
        return null
    }

    private fun amountMatches(sub: Subscription, amount: Double): Boolean {
        val expected = sub.amount ?: return true
        return abs(expected - amount) <= AMOUNT_TOLERANCE
    }

    companion object {
        const val WINDOW_DAYS: Long = 3
        const val DUPLICATE_WINDOW_DAYS: Long = 1
        const val AMOUNT_TOLERANCE: Double = 0.01
    }
}
