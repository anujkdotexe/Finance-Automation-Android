package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.SubscriptionFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SubscriptionScheduleUseCaseTest {
    private val useCase = SubscriptionScheduleUseCase()
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day).toEpochDay()
    private fun days(list: List<Long>) = list.map { LocalDate.ofEpochDay(it).toString() }

    @Test
    fun monthlyAnchor31ClampsToMonthEnd() {
        val sub = testSubscription(anchorEpochDay = d(2026, 1, 31))
        val result = useCase.dueDates(sub, d(2026, 1, 1), d(2026, 5, 31), todayEpochDay = d(2026, 6, 1))
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31"), days(result))
    }

    @Test
    fun monthlyAnchor30ClampsInFebruary() {
        val sub = testSubscription(anchorEpochDay = d(2026, 1, 30))
        val result = useCase.dueDates(sub, d(2026, 2, 1), d(2026, 3, 31), todayEpochDay = d(2026, 6, 1))
        assertEquals(listOf("2026-02-28", "2026-03-30"), days(result))
    }

    @Test
    fun monthlyLeapYearFebruaryGets29() {
        val sub = testSubscription(anchorEpochDay = d(2027, 12, 31))
        val result = useCase.dueDates(sub, d(2028, 2, 1), d(2028, 2, 29), todayEpochDay = d(2030, 1, 1))
        assertEquals(listOf("2028-02-29"), days(result))
    }

    @Test
    fun weeklyRepeatsEverySevenDays() {
        val sub = testSubscription(frequency = SubscriptionFrequency.WEEKLY, anchorEpochDay = d(2026, 3, 2))
        val result = useCase.dueDates(sub, d(2026, 3, 2), d(2026, 3, 31), todayEpochDay = d(2026, 4, 30))
        assertEquals(listOf("2026-03-02", "2026-03-09", "2026-03-16", "2026-03-23", "2026-03-30"), days(result))
    }

    @Test
    fun weeklyWindowStartingMidCycleKeepsAnchorPhase() {
        val sub = testSubscription(frequency = SubscriptionFrequency.WEEKLY, anchorEpochDay = d(2026, 3, 2))
        val result = useCase.dueDates(sub, d(2026, 3, 10), d(2026, 3, 24), todayEpochDay = d(2026, 4, 30))
        assertEquals(listOf("2026-03-16", "2026-03-23"), days(result))
    }

    @Test
    fun yearlyKeepsMonthAndDay() {
        val sub = testSubscription(frequency = SubscriptionFrequency.YEARLY, anchorEpochDay = d(2024, 7, 9))
        val result = useCase.dueDates(sub, d(2024, 1, 1), d(2027, 12, 31), todayEpochDay = d(2027, 12, 31))
        assertEquals(listOf("2024-07-09", "2025-07-09", "2026-07-09", "2027-07-09"), days(result))
    }

    @Test
    fun yearlyFeb29ClampsTo28InNonLeapYears() {
        val sub = testSubscription(frequency = SubscriptionFrequency.YEARLY, anchorEpochDay = d(2024, 2, 29))
        val result = useCase.dueDates(sub, d(2024, 1, 1), d(2028, 12, 31), todayEpochDay = d(2028, 12, 31))
        assertEquals(listOf("2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"), days(result))
    }

    @Test
    fun neverReturnsDatesAfterToday() {
        val sub = testSubscription(anchorEpochDay = d(2026, 1, 15))
        val today = d(2026, 4, 14)
        val result = useCase.dueDates(sub, d(2026, 1, 1), d(2026, 12, 31), todayEpochDay = today)
        assertEquals(listOf("2026-01-15", "2026-02-15", "2026-03-15"), days(result))
        assertTrue(result.all { it <= today })
    }

    @Test
    fun dueTodayIsIncluded() {
        val sub = testSubscription(anchorEpochDay = d(2026, 1, 15))
        val result = useCase.dueDates(sub, d(2026, 4, 1), d(2026, 4, 30), todayEpochDay = d(2026, 4, 15))
        assertEquals(listOf("2026-04-15"), days(result))
    }

    @Test
    fun nothingBeforeAnchor() {
        val sub = testSubscription(anchorEpochDay = d(2026, 3, 15))
        val result = useCase.dueDates(sub, d(2026, 1, 1), d(2026, 3, 14), todayEpochDay = d(2026, 6, 1))
        assertTrue(result.isEmpty())
    }

    @Test
    fun inactiveSubscriptionProducesNothing() {
        val sub = testSubscription(active = false)
        val result = useCase.dueDates(sub, d(2026, 1, 1), d(2026, 12, 31), todayEpochDay = d(2026, 12, 31))
        assertTrue(result.isEmpty())
    }
}
