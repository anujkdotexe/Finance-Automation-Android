package com.financeautomation.domain.repository

import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.model.SubscriptionCycleWithSubscription
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for subscriptions and their due cycles.
 * Pure Kotlin, zero Android framework dependencies.
 */
interface SubscriptionRepository {
    fun observeSubscriptions(): Flow<List<Subscription>>

    /** Cycles in DUE or NEEDS_REVIEW with their subscription. */
    fun observeOpenCycles(): Flow<List<SubscriptionCycleWithSubscription>>

    /** Cycles in NEEDS_REVIEW with their subscription. */
    fun observeNeedsReviewCycles(): Flow<List<SubscriptionCycleWithSubscription>>

    suspend fun getSubscription(id: String): Subscription?
    suspend fun getActiveSubscriptions(): List<Subscription>

    /** Inserts or replaces a subscription and marks it dirty for the Config mirror. */
    suspend fun upsertSubscription(subscription: Subscription)

    /** Deletes a subscription by its id. */
    suspend fun deleteSubscription(id: String)

    suspend fun getCycle(id: String): SubscriptionCycle?
    suspend fun getCycleWithSubscription(id: String): SubscriptionCycleWithSubscription?

    /** Cycles of active subscriptions whose due day lies in [fromEpochDay, toEpochDay], any state. */
    suspend fun getCyclesInWindow(fromEpochDay: Long, toEpochDay: Long): List<SubscriptionCycleWithSubscription>

    /** Cycles in DUE state of active subscriptions due on or before [epochDay]. */
    suspend fun getDueCyclesUpTo(epochDay: Long): List<SubscriptionCycleWithSubscription>

    /** Inserts a DUE cycle when none exists for (subscription, due day). Returns true when created. */
    suspend fun createCycleIfMissing(subscriptionId: String, dueEpochDay: Long): Boolean

    suspend fun updateCycle(cycle: SubscriptionCycle)

    suspend fun setCycleState(cycleId: String, state: SubscriptionCycleState, remindCount: Int? = null)

    /**
     * Marks the open (DUE or NEEDS_REVIEW) cycle of [subscriptionId] nearest to [nowEpochDay]
     * as PAID with [transactionId]. Returns false when there was no open cycle.
     */
    suspend fun markCyclePaidForSubscription(subscriptionId: String, nowEpochDay: Long, transactionId: String): Boolean

    /** Marks the cycle PAID with [transactionId]. */
    suspend fun markCyclePaid(cycleId: String, transactionId: String)

    /**
     * Skips cycles that fell due while a subscription was paused within [fromEpochDay, toEpochDay].
     * Creates them as SKIPPED or transitions existing DUE cycles to SKIPPED.
     */
    suspend fun skipPausedCycles(subscriptionId: String, fromEpochDay: Long, toEpochDay: Long)
}
