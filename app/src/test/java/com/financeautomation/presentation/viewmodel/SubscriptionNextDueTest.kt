package com.financeautomation.presentation.viewmodel

import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.usecase.FakeSubscriptionRepository
import com.financeautomation.domain.usecase.SubscriptionScheduleUseCase
import com.financeautomation.domain.usecase.testSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** R2-2, R2-3, R2-20, R2-25: next due comes from the schedule, the soonest one is shown, unknown amounts are not zero. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionNextDueTest {

    private val schedule = SubscriptionScheduleUseCase()
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day).toEpochDay()

    @Test
    fun monthlyNextDueIsTheFirstDateOnOrAfterToday() {
        val sub = testSubscription(anchorEpochDay = d(2026, 1, 15))
        assertEquals(d(2026, 4, 15), schedule.nextDue(sub, d(2026, 4, 15)))
        assertEquals(d(2026, 5, 15), schedule.nextDue(sub, d(2026, 4, 16)))
        assertEquals(d(2026, 4, 15), schedule.nextDue(sub, d(2026, 4, 1)))
    }

    @Test
    fun monthlyClampsToTheMonthEnd() {
        val sub = testSubscription(anchorEpochDay = d(2026, 1, 31))
        assertEquals(d(2026, 2, 28), schedule.nextDue(sub, d(2026, 2, 1)))
        assertEquals(d(2026, 3, 31), schedule.nextDue(sub, d(2026, 3, 1)))
    }

    @Test
    fun weeklyAndYearlyAndFutureAnchor() {
        val weekly = testSubscription(frequency = SubscriptionFrequency.WEEKLY, anchorEpochDay = d(2026, 3, 2))
        assertEquals(d(2026, 3, 16), schedule.nextDue(weekly, d(2026, 3, 10)))
        val yearly = testSubscription(frequency = SubscriptionFrequency.YEARLY, anchorEpochDay = d(2024, 7, 9))
        assertEquals(d(2027, 7, 9), schedule.nextDue(yearly, d(2026, 7, 10)))
        val future = testSubscription(anchorEpochDay = d(2026, 9, 5))
        assertEquals(d(2026, 9, 5), schedule.nextDue(future, d(2026, 4, 1)))
    }

    @Test
    fun inactiveHasNoNextDue() {
        assertNull(schedule.nextDue(testSubscription(active = false), d(2026, 4, 1)))
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun millisIst(y: Int, m: Int, day: Int, hour: Int) =
        LocalDate.of(y, m, day).atTime(hour, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    @Test
    fun summaryShowsTheSoonestSubscriptionNotTheFirstInTheList() = runTest {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(id = "a", name = "Alpha", anchorEpochDay = d(2026, 1, 28)))
        repo.addSubscription(testSubscription(id = "b", name = "Bravo", anchorEpochDay = d(2026, 1, 17)))
        val vm = SubscriptionsViewModel(repo, clock = { millisIst(2026, 4, 15, 10) })
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals("Bravo", state.nextDueSubscription?.name)
        assertEquals(d(2026, 4, 17), state.nextDueByDay["b"])
        assertEquals(d(2026, 4, 28), state.nextDueByDay["a"])
    }

    @Test
    fun aSubscriptionWithoutAnAmountIsLeftOutOfTheTotalAndCounted() = runTest {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(id = "a", name = "Fixed", amount = 100.0))
        repo.addSubscription(testSubscription(id = "b", name = "Varies", amount = null))
        val vm = SubscriptionsViewModel(repo, clock = { millisIst(2026, 4, 15, 10) })
        advanceUntilIdle()
        assertEquals(100.0, vm.uiState.value.totalMonthlySpend, 0.001)
        assertEquals(1, vm.uiState.value.variesAmountCount)
    }

    @Test
    fun defaultAnchorIsTodayInKolkataNotInTheDeviceZone() = runTest {
        val repo = FakeSubscriptionRepository()
        // 01:30 in Kolkata on 15 April is still 14 April in UTC
        val now = millisIst(2026, 4, 15, 1) + 30 * 60_000L
        val vm = SubscriptionsViewModel(repo, clock = { now })
        vm.addSubscription(name = "Gym", amount = 500.0)
        advanceUntilIdle()
        assertEquals(d(2026, 4, 15), repo.subscriptions.value.values.single().anchorEpochDay)
    }
}
