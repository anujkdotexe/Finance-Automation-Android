package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.service.SubscriptionActionReceiver
import com.financeautomation.testutil.RecordingTransactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SubscriptionActionTest {

    private val ist = SubscriptionScheduleUseCase.IST

    private class Fixture(amount: Double?) {
        val recorder = RecordingTransactionRepository()
        val repo = FakeSubscriptionRepository()
        val captures = mutableListOf<Pair<String, Long>>()
        val actions = SubscriptionActionUseCase(
            repository = repo,
            logExpenseUseCase = LogExpenseUseCase(recorder.repository),
            createCapture = { sub, ts -> captures.add(sub.id to ts); "cap_1" }
        )
        init {
            repo.addSubscription(testSubscription(amount = amount))
        }
    }

    private fun dueNoon(day: Long) = LocalDate.ofEpochDay(day).atTime(12, 0).atZone(ist).toInstant().toEpochMilli()

    @Test
    fun yesWithFixedAmountLogsExpenseAtDueDateNoonAndMarksPaid() = runBlocking {
        val f = Fixture(649.0)
        val due = LocalDate.now(ist).minusDays(2).toEpochDay()
        val cycle = f.repo.addCycle("sub_netflix", due)

        val outcome = f.actions.yes(cycle.id, System.currentTimeMillis())

        assertTrue(outcome is SubscriptionYesOutcome.Logged)
        assertEquals(1, f.recorder.inserted.size)
        val txn = f.recorder.inserted.single()
        assertEquals("Netflix", txn.merchant)
        assertEquals(649.0, txn.totalAmount!!, 0.001)
        assertEquals("HDFC", txn.account)
        assertEquals("Subscriptions", txn.category)
        assertEquals(dueNoon(due), txn.timestamp)
        val updated = f.repo.cycle(cycle.id)
        assertEquals(SubscriptionCycleState.PAID, updated.state)
        assertEquals(txn.id, updated.transactionId)
    }

    @Test
    fun yesDueTodayBeforeNoonUsesNowNotFutureNoon() = runBlocking {
        val f = Fixture(649.0)
        val today = LocalDate.now(ist).toEpochDay()
        val cycle = f.repo.addCycle("sub_netflix", today)
        val now = System.currentTimeMillis()

        f.actions.yes(cycle.id, now)

        val ts = f.recorder.inserted.single().timestamp
        assertEquals(minOf(now, dueNoon(today)), ts)
        assertTrue(ts <= now)
    }

    @Test
    fun yesWithVariesAmountPreparesCaptureAndLogsNothing() = runBlocking {
        val f = Fixture(null)
        val due = LocalDate.now(ist).minusDays(1).toEpochDay()
        val cycle = f.repo.addCycle("sub_netflix", due)

        val outcome = f.actions.yes(cycle.id, System.currentTimeMillis())

        assertEquals(SubscriptionYesOutcome.NeedsAmount("cap_1"), outcome)
        assertEquals(listOf("sub_netflix" to dueNoon(due)), f.captures)
        assertTrue(f.recorder.inserted.isEmpty())
        assertEquals(SubscriptionCycleState.DUE, f.repo.cycle(cycle.id).state)
    }

    @Test
    fun yesOnResolvedCycleDoesNothing() = runBlocking {
        val f = Fixture(649.0)
        val paid = f.repo.addCycle("sub_netflix", 10, SubscriptionCycleState.PAID, "txn_x")
        val skipped = f.repo.addCycle("sub_netflix", 11, SubscriptionCycleState.SKIPPED)
        assertEquals(SubscriptionYesOutcome.AlreadyResolved, f.actions.yes(paid.id))
        assertEquals(SubscriptionYesOutcome.AlreadyResolved, f.actions.yes(skipped.id))
        assertEquals(SubscriptionYesOutcome.NotFound, f.actions.yes("missing"))
        assertTrue(f.recorder.inserted.isEmpty())
    }

    @Test
    fun notYetLeavesDueCycleDue() = runBlocking {
        val f = Fixture(649.0)
        val cycle = f.repo.addCycle("sub_netflix", 10, SubscriptionCycleState.DUE, remindCount = 1)
        f.actions.notYet(cycle.id)
        val after = f.repo.cycle(cycle.id)
        assertEquals(SubscriptionCycleState.DUE, after.state)
        assertEquals(1, after.remindCount)
        assertTrue(f.recorder.inserted.isEmpty())
    }

    @Test
    fun notYetFromNeedsReviewGoesBackToDueWithCountTwo() = runBlocking {
        val f = Fixture(649.0)
        val cycle = f.repo.addCycle("sub_netflix", 10, SubscriptionCycleState.NEEDS_REVIEW, remindCount = 3)
        f.actions.notYet(cycle.id)
        val after = f.repo.cycle(cycle.id)
        assertEquals(SubscriptionCycleState.DUE, after.state)
        assertEquals(2, after.remindCount)
    }

    @Test
    fun skipMarksSkippedWithoutExpense() = runBlocking {
        val f = Fixture(649.0)
        val cycle = f.repo.addCycle("sub_netflix", 10)
        f.actions.skip(cycle.id)
        assertEquals(SubscriptionCycleState.SKIPPED, f.repo.cycle(cycle.id).state)
        assertTrue(f.recorder.inserted.isEmpty())
        assertNull(f.repo.cycle(cycle.id).transactionId)
    }

    @Test
    fun receiverHandleMapsActionsAndReportsLoggedExpense() = runBlocking {
        val f = Fixture(649.0)
        val due = LocalDate.now(ist).minusDays(1).toEpochDay()
        val a = f.repo.addCycle("sub_netflix", due)
        val b = f.repo.addCycle("sub_netflix", due - 30)
        val c = f.repo.addCycle("sub_netflix", due - 60)

        assertTrue(SubscriptionActionReceiver.handle(SubscriptionActionReceiver.ACTION_YES, a.id, f.actions))
        assertFalse(SubscriptionActionReceiver.handle(SubscriptionActionReceiver.ACTION_NOT_YET, b.id, f.actions))
        assertFalse(SubscriptionActionReceiver.handle(SubscriptionActionReceiver.ACTION_SKIP, c.id, f.actions))

        assertEquals(SubscriptionCycleState.PAID, f.repo.cycle(a.id).state)
        assertEquals(SubscriptionCycleState.DUE, f.repo.cycle(b.id).state)
        assertEquals(SubscriptionCycleState.SKIPPED, f.repo.cycle(c.id).state)
    }
}
