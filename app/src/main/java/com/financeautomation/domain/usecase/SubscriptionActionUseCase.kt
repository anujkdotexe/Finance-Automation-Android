package com.financeautomation.domain.usecase

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.repository.SubscriptionRepository
import java.time.LocalDate
import java.time.ZoneId

/** Outcome of answering "Yes" for a subscription cycle. */
sealed interface SubscriptionYesOutcome {
    /** The expense was logged and the cycle is PAID. */
    data class Logged(val transactionId: String) : SubscriptionYesOutcome

    /** The amount varies: a prefilled capture ([captureId]) must be opened in the expense sheet. */
    data class NeedsAmount(val captureId: String) : SubscriptionYesOutcome

    data object NotFound : SubscriptionYesOutcome
    data object AlreadyResolved : SubscriptionYesOutcome

    /**
     * The cycle is due in a previous month. No expense is created (it would belong to an older sheet
     * tab); the cycle is moved to NEEDS_REVIEW so the user can skip it.
     */
    data object PastMonth : SubscriptionYesOutcome
    data class Failed(val message: String) : SubscriptionYesOutcome
}

/**
 * Answers to a subscription reminder: Yes, Not yet, Skip this time.
 * [createCapture] stores a prefilled pending capture (name, category, account, due date, empty amount)
 * for subscriptions whose amount varies and returns its id.
 * Pure Kotlin.
 */
class SubscriptionActionUseCase(
    private val repository: SubscriptionRepository,
    private val logExpenseUseCase: LogExpenseUseCase,
    private val createCapture: suspend (Subscription, Long) -> String,
    private val zone: ZoneId = SubscriptionScheduleUseCase.IST
) {

    /** Timestamp for the expense of a cycle: the due date at 12:00, but never later than [nowMillis]. */
    fun expenseTimestamp(dueEpochDay: Long, nowMillis: Long): Long {
        val dueNoon = LocalDate.ofEpochDay(dueEpochDay).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return minOf(nowMillis, dueNoon)
    }

    /** True when the cycle belongs to a subscription whose amount varies, so Yes must open the expense sheet. */
    suspend fun amountVaries(cycleId: String): Boolean =
        repository.getCycleWithSubscription(cycleId)?.subscription?.amount == null

    suspend fun yes(cycleId: String, nowMillis: Long = System.currentTimeMillis()): SubscriptionYesOutcome {
        val item = repository.getCycleWithSubscription(cycleId) ?: return SubscriptionYesOutcome.NotFound
        val cycle = item.cycle
        val sub = item.subscription
        if (cycle.state == SubscriptionCycleState.PAID || cycle.state == SubscriptionCycleState.SKIPPED) {
            return SubscriptionYesOutcome.AlreadyResolved
        }
        val currentMonthStart = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            .withDayOfMonth(1).toEpochDay()
        if (cycle.dueEpochDay < currentMonthStart) {
            if (cycle.state != SubscriptionCycleState.NEEDS_REVIEW) {
                repository.setCycleState(cycle.id, SubscriptionCycleState.NEEDS_REVIEW)
            }
            return SubscriptionYesOutcome.PastMonth
        }
        val timestamp = expenseTimestamp(cycle.dueEpochDay, nowMillis)
        val amount = sub.amount
        if (amount == null) {
            return try {
                SubscriptionYesOutcome.NeedsAmount(createCapture(sub, timestamp))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SubscriptionYesOutcome.Failed(e.message ?: "Could not prepare the expense")
            }
        }
        val result = logExpenseUseCase(
            amount = amount,
            merchant = sub.name,
            category = sub.category,
            account = sub.account,
            timestamp = timestamp,
            afterInsert = { txn -> repository.markCyclePaid(cycle.id, txn.id) }
        )
        return when (result) {
            is Result.Success -> SubscriptionYesOutcome.Logged(result.data.id)
            is Result.Error -> SubscriptionYesOutcome.Failed(result.message ?: "Could not log the expense")
            is Result.Loading -> SubscriptionYesOutcome.Failed("Still saving")
        }
    }

    /**
     * Not yet: a DUE cycle stays DUE (the worker asks again next evening). A cycle that was moved to
     * Needs Review goes back to DUE with its reminder count reset to 2, so one more reminder follows.
     */
    suspend fun notYet(cycleId: String) {
        val cycle = repository.getCycle(cycleId) ?: return
        if (cycle.state == SubscriptionCycleState.NEEDS_REVIEW) {
            repository.setCycleState(cycleId, SubscriptionCycleState.DUE, REMIND_COUNT_AFTER_NOT_YET)
        }
    }

    suspend fun skip(cycleId: String) {
        val cycle = repository.getCycle(cycleId) ?: return
        if (cycle.state == SubscriptionCycleState.DUE || cycle.state == SubscriptionCycleState.NEEDS_REVIEW) {
            repository.setCycleState(cycleId, SubscriptionCycleState.SKIPPED)
        }
    }

    companion object {
        const val REMIND_COUNT_AFTER_NOT_YET: Int = 2
    }
}
