package com.financeautomation.service

import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.usecase.FakeSubscriptionRepository
import com.financeautomation.domain.usecase.SubscriptionScheduleUseCase
import com.financeautomation.domain.usecase.testSubscription
import com.financeautomation.service.worker.SubscriptionReminderNotifier
import com.financeautomation.service.worker.SubscriptionReminderWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SubscriptionReminderWorkerTest {

    private class RecordingNotifier : SubscriptionReminderNotifier {
        val posted = mutableListOf<Pair<Subscription, SubscriptionCycle>>()
        override fun post(subscription: Subscription, cycle: SubscriptionCycle) { posted.add(subscription to cycle) }
    }

    private val schedule = SubscriptionScheduleUseCase()
    private val today = LocalDate.of(2026, 4, 15).toEpochDay()

    private fun run(repo: FakeSubscriptionRepository, notifier: RecordingNotifier, day: Long = today) = runBlocking {
        SubscriptionReminderWorker.runCheck(repo, schedule, notifier, day)
    }

    @Test
    fun createsMissingCyclesUpToTodayAndNotMoreThanThreeMonthsBack() {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(anchorEpochDay = LocalDate.of(2025, 1, 15).toEpochDay()))
        run(repo, RecordingNotifier())

        val days = repo.cycles.value.values.map { LocalDate.ofEpochDay(it.dueEpochDay).toString() }.sorted()
        assertEquals(listOf("2026-01-15", "2026-02-15", "2026-03-15", "2026-04-15"), days)
    }

    @Test
    fun runningTwiceDoesNotDuplicateCycles() {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(anchorEpochDay = LocalDate.of(2026, 3, 15).toEpochDay()))
        run(repo, RecordingNotifier())
        run(repo, RecordingNotifier())
        assertEquals(2, repo.cycles.value.size)
    }

    @Test
    fun postsOneNotificationPerDueCyclePerRun() {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(anchorEpochDay = LocalDate.of(2026, 3, 15).toEpochDay()))
        val notifier = RecordingNotifier()
        val posted = run(repo, notifier)

        assertEquals(2, posted)
        assertEquals(2, notifier.posted.size)
        assertEquals(setOf(LocalDate.of(2026, 3, 15).toEpochDay(), today), notifier.posted.map { it.second.dueEpochDay }.toSet())
        assertTrue(repo.cycles.value.values.all { it.remindCount == 1 && it.state == SubscriptionCycleState.DUE })
    }

    @Test
    fun thirdReminderMovesCycleToNeedsReviewAndStopsReminding() {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(anchorEpochDay = today))
        val notifier = RecordingNotifier()

        run(repo, notifier)
        run(repo, notifier)
        assertEquals(SubscriptionCycleState.DUE, repo.cycles.value.values.single().state)
        run(repo, notifier)

        val cycle = repo.cycles.value.values.single()
        assertEquals(3, notifier.posted.size)
        assertEquals(3, cycle.remindCount)
        assertEquals(SubscriptionCycleState.NEEDS_REVIEW, cycle.state)

        run(repo, notifier)
        assertEquals("no fourth reminder", 3, notifier.posted.size)
    }

    @Test
    fun noReminderWhenRemindIsOffInactiveOrAnswered() {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(id = "quiet", name = "Quiet", anchorEpochDay = today, remind = false))
        repo.addSubscription(testSubscription(id = "paused", name = "Paused", anchorEpochDay = today, active = false))
        repo.addSubscription(testSubscription(id = "paid", name = "Paid", anchorEpochDay = today))
        repo.addCycle("paid", today, SubscriptionCycleState.PAID, "txn_1")
        val notifier = RecordingNotifier()

        run(repo, notifier)

        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun weeklySubscriptionGetsCyclesForEachWeek() {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(
            testSubscription(frequency = SubscriptionFrequency.WEEKLY, anchorEpochDay = today - 14)
        )
        val notifier = RecordingNotifier()
        run(repo, notifier)
        assertEquals(3, notifier.posted.size)
    }

    @Test
    fun initialDelayTargetsReminderTimeInKolkata() {
        val ist = ZoneId.of("Asia/Kolkata")
        val before = ZonedDateTime.of(2026, 4, 15, 20, 0, 0, 0, ist)
        assertEquals(60L, SubscriptionReminderWorker.initialDelay("21:00", before).toMinutes())
        val after = ZonedDateTime.of(2026, 4, 15, 22, 0, 0, 0, ist)
        assertEquals(23 * 60L, SubscriptionReminderWorker.initialDelay("21:00", after).toMinutes())
        assertEquals(60L, SubscriptionReminderWorker.initialDelay("not a time", before).toMinutes())
    }
}
