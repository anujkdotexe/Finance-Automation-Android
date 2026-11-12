package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import java.time.LocalDate
import java.time.ZoneId

/**
 * Computes due dates (epoch days) of a subscription.
 * MONTHLY keeps the anchor day and clamps to the month end. YEARLY keeps month and day and
 * clamps 29 Feb to 28 Feb in non-leap years. WEEKLY repeats every 7 days.
 * Never returns a date after today and never before the anchor.
 */
class SubscriptionScheduleUseCase {

    fun dueDates(
        sub: Subscription,
        fromEpochDay: Long,
        toEpochDay: Long,
        todayEpochDay: Long = LocalDate.now(IST).toEpochDay()
    ): List<Long> {
        if (!sub.active) return emptyList()
        val upper = minOf(toEpochDay, todayEpochDay)
        val lower = maxOf(fromEpochDay, sub.anchorEpochDay)
        if (lower > upper) return emptyList()

        val anchor = LocalDate.ofEpochDay(sub.anchorEpochDay)
        val result = mutableListOf<Long>()
        when (sub.frequency) {
            SubscriptionFrequency.WEEKLY -> {
                val first = if (lower <= sub.anchorEpochDay) sub.anchorEpochDay
                else sub.anchorEpochDay + ((lower - sub.anchorEpochDay + 6) / 7) * 7
                var d = first
                while (d <= upper) {
                    result.add(d)
                    d += 7
                }
            }
            SubscriptionFrequency.MONTHLY -> {
                var month = LocalDate.ofEpochDay(lower).withDayOfMonth(1)
                val last = LocalDate.ofEpochDay(upper).withDayOfMonth(1)
                while (!month.isAfter(last)) {
                    val day = minOf(anchor.dayOfMonth, month.lengthOfMonth())
                    val due = month.withDayOfMonth(day).toEpochDay()
                    if (due in lower..upper) result.add(due)
                    month = month.plusMonths(1)
                }
            }
            SubscriptionFrequency.YEARLY -> {
                var year = LocalDate.ofEpochDay(lower).year
                val lastYear = LocalDate.ofEpochDay(upper).year
                while (year <= lastYear) {
                    val firstOfMonth = LocalDate.of(year, anchor.monthValue, 1)
                    val day = minOf(anchor.dayOfMonth, firstOfMonth.lengthOfMonth())
                    val due = firstOfMonth.withDayOfMonth(day).toEpochDay()
                    if (due in lower..upper) result.add(due)
                    year++
                }
            }
        }
        return result
    }

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    }
}
