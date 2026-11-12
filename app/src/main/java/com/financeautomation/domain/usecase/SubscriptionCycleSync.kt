package com.financeautomation.domain.usecase

import com.financeautomation.domain.repository.SubscriptionRepository
import java.time.Instant
import java.time.LocalDate

/**
 * Creates missing subscription cycles. Cycles never reach into a previous month: the earliest due
 * date is the first day of the current month (Asia/Kolkata), and never earlier than the day the
 * subscription was created. Past-month expenses belong to older sheet tabs and are a later feature.
 * Pure Kotlin.
 */
object SubscriptionCycleSync {

    /**
     * Ensures cycles exist for every active subscription with due dates from the lower bound up to
     * [nowEpochDay] + [aheadDays]. [lookBackDays] further limits the lower bound (null means the
     * start of the current month). Cycles ahead of today are only for matching a payment that arrives
     * early; they are never reminded.
     */
    suspend fun ensure(
        repository: SubscriptionRepository,
        scheduleUseCase: SubscriptionScheduleUseCase,
        nowEpochDay: Long,
        aheadDays: Long = 0,
        lookBackDays: Long? = null
    ) {
        val monthStart = LocalDate.ofEpochDay(nowEpochDay).withDayOfMonth(1).toEpochDay()
        val upper = nowEpochDay + aheadDays
        for (sub in repository.getActiveSubscriptions()) {
            val createdDay = Instant.ofEpochMilli(sub.createdAt)
                .atZone(SubscriptionScheduleUseCase.IST).toLocalDate().toEpochDay()
            var lower = maxOf(monthStart, createdDay)
            if (lookBackDays != null) lower = maxOf(lower, nowEpochDay - lookBackDays)
            for (day in scheduleUseCase.dueDates(sub, lower, upper, todayEpochDay = upper)) {
                repository.createCycleIfMissing(sub.id, day)
            }
        }
    }
}
