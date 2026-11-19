package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.model.SubscriptionCycleWithSubscription
import com.financeautomation.domain.repository.SubscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/** In-memory [SubscriptionRepository] for unit tests. */
class FakeSubscriptionRepository : SubscriptionRepository {
    val subscriptions = MutableStateFlow<Map<String, Subscription>>(emptyMap())
    val cycles = MutableStateFlow<Map<String, SubscriptionCycle>>(emptyMap())
    private var idCounter = 0
    var clock: Long = 1_000L

    fun addSubscription(sub: Subscription) {
        subscriptions.value = subscriptions.value + (sub.id to sub)
    }

    fun addCycle(subscriptionId: String, dueEpochDay: Long, state: SubscriptionCycleState = SubscriptionCycleState.DUE,
                 transactionId: String? = null, remindCount: Int = 0): SubscriptionCycle {
        val c = SubscriptionCycle("cyc_${++idCounter}", subscriptionId, dueEpochDay, state, transactionId, remindCount, clock)
        cycles.value = cycles.value + (c.id to c)
        return c
    }

    private fun join(c: SubscriptionCycle): SubscriptionCycleWithSubscription? =
        subscriptions.value[c.subscriptionId]?.let { SubscriptionCycleWithSubscription(c, it) }

    override fun observeSubscriptions(): Flow<List<Subscription>> = subscriptions.map { it.values.toList() }

    override fun observeOpenCycles(): Flow<List<SubscriptionCycleWithSubscription>> = cycles.map { map ->
        map.values.filter { it.state == SubscriptionCycleState.DUE || it.state == SubscriptionCycleState.NEEDS_REVIEW }
            .mapNotNull { join(it) }
    }

    override fun observeNeedsReviewCycles(): Flow<List<SubscriptionCycleWithSubscription>> = cycles.map { map ->
        map.values.filter { it.state == SubscriptionCycleState.NEEDS_REVIEW }.mapNotNull { join(it) }
    }

    override suspend fun getSubscription(id: String): Subscription? = subscriptions.value[id]
    override suspend fun getActiveSubscriptions(): List<Subscription> = subscriptions.value.values.filter { it.active }
    override suspend fun upsertSubscription(subscription: Subscription) { addSubscription(subscription) }
    override suspend fun deleteSubscription(id: String) {
        subscriptions.value = subscriptions.value - id
        cycles.value = cycles.value.filterValues { it.subscriptionId != id }
    }
    override suspend fun getCycle(id: String): SubscriptionCycle? = cycles.value[id]
    override suspend fun getCycleWithSubscription(id: String): SubscriptionCycleWithSubscription? =
        cycles.value[id]?.let { join(it) }

    override suspend fun getCyclesInWindow(fromEpochDay: Long, toEpochDay: Long): List<SubscriptionCycleWithSubscription> =
        cycles.value.values.filter { it.dueEpochDay in fromEpochDay..toEpochDay }
            .mapNotNull { join(it) }.filter { it.subscription.active }

    override suspend fun getDueCyclesUpTo(epochDay: Long): List<SubscriptionCycleWithSubscription> =
        cycles.value.values.filter { it.state == SubscriptionCycleState.DUE && it.dueEpochDay <= epochDay }
            .sortedBy { it.dueEpochDay }.mapNotNull { join(it) }.filter { it.subscription.active }

    override suspend fun createCycleIfMissing(subscriptionId: String, dueEpochDay: Long): Boolean {
        if (cycles.value.values.any { it.subscriptionId == subscriptionId && it.dueEpochDay == dueEpochDay }) return false
        addCycle(subscriptionId, dueEpochDay)
        return true
    }

    override suspend fun updateCycle(cycle: SubscriptionCycle) { cycles.value = cycles.value + (cycle.id to cycle) }

    override suspend fun setCycleState(cycleId: String, state: SubscriptionCycleState, remindCount: Int?) {
        val c = cycles.value[cycleId] ?: return
        cycles.value = cycles.value + (cycleId to c.copy(state = state, remindCount = remindCount ?: c.remindCount, updatedAt = clock))
    }

    override suspend fun markCyclePaidForSubscription(subscriptionId: String, nowEpochDay: Long, transactionId: String): Boolean {
        val target = cycles.value.values
            .filter { it.subscriptionId == subscriptionId &&
                (it.state == SubscriptionCycleState.DUE || it.state == SubscriptionCycleState.NEEDS_REVIEW) }
            .minByOrNull { abs(it.dueEpochDay - nowEpochDay) } ?: return false
        markCyclePaid(target.id, transactionId)
        return true
    }

    override suspend fun markCyclePaid(cycleId: String, transactionId: String) {
        val c = cycles.value[cycleId] ?: return
        cycles.value = cycles.value + (cycleId to c.copy(state = SubscriptionCycleState.PAID, transactionId = transactionId, updatedAt = clock))
    }

    override suspend fun skipPausedCycles(subscriptionId: String, fromEpochDay: Long, toEpochDay: Long) {
        val sub = subscriptions.value[subscriptionId] ?: return
        val schedule = SubscriptionScheduleUseCase()
        val dueDays = schedule.dueDates(sub.copy(active = true), fromEpochDay, toEpochDay, todayEpochDay = toEpochDay + 1)
        for (day in dueDays) {
            val existing = cycles.value.values.firstOrNull { it.subscriptionId == subscriptionId && it.dueEpochDay == day }
            if (existing == null) {
                addCycle(subscriptionId, day, state = SubscriptionCycleState.SKIPPED)
            } else if (existing.state == SubscriptionCycleState.DUE) {
                setCycleState(existing.id, SubscriptionCycleState.SKIPPED)
            }
        }
    }

    fun cycle(id: String): SubscriptionCycle = cycles.value.getValue(id)
}

fun testSubscription(
    id: String = "sub_netflix",
    name: String = "Netflix",
    amount: Double? = 649.0,
    frequency: com.financeautomation.domain.model.SubscriptionFrequency = com.financeautomation.domain.model.SubscriptionFrequency.MONTHLY,
    anchorEpochDay: Long = java.time.LocalDate.of(2026, 1, 15).toEpochDay(),
    account: String = "HDFC",
    keywords: String = "",
    active: Boolean = true,
    remind: Boolean = true,
    createdAt: Long = 0L
) = Subscription(
    id = id, name = name, amount = amount, frequency = frequency, anchorEpochDay = anchorEpochDay,
    account = account, category = "Subscriptions", remind = remind, keywords = keywords, active = active, createdAt = createdAt
)
