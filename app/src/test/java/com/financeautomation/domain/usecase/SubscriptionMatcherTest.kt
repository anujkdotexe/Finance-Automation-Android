package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.SubscriptionCycleState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionMatcherTest {
    private val today = 20_000L

    private fun setup(
        amount: Double? = 649.0,
        keywords: String = "",
        active: Boolean = true,
        dueOffset: Long? = 0L,
        state: SubscriptionCycleState = SubscriptionCycleState.DUE,
        txn: String? = null
    ): Pair<SubscriptionMatcher, FakeSubscriptionRepository> {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(amount = amount, keywords = keywords, active = active))
        if (dueOffset != null) repo.addCycle("sub_netflix", today + dueOffset, state, txn)
        return SubscriptionMatcher(repo) to repo
    }

    @Test
    fun matchesByNameAndAmount() = runBlocking {
        val (m, _) = setup()
        val r = m.match(649.0, "Paid to NETFLIX.COM via UPI", today)
        assertNotNull(r)
        assertEquals("sub_netflix", r!!.subscription.id)
        assertFalse(r.isDuplicate)
    }

    @Test
    fun amountWithinOnePaiseMatches() = runBlocking {
        val (m, _) = setup()
        assertNotNull(m.match(649.01, "netflix", today))
        assertNull(m.match(649.5, "netflix", today))
    }

    @Test
    fun matchesByKeywordCaseInsensitive() = runBlocking {
        val (m, _) = setup(keywords = "nflx, Streaming Co")
        assertNotNull(m.match(649.0, "Debit to streaming co mumbai", today))
        assertNotNull(m.match(649.0, "NFLX*SUB", today))
    }

    @Test
    fun noMatchWhenTextHasNoNameOrKeyword() = runBlocking {
        val (m, _) = setup(keywords = "nflx")
        assertNull(m.match(649.0, "Paid to Swiggy", today))
    }

    @Test
    fun dueDateWindowIsThreeDaysEitherSide() = runBlocking {
        val (early, _) = setup(dueOffset = 3L)
        assertNotNull(early.match(649.0, "netflix", today))
        val (late, _) = setup(dueOffset = -3L)
        assertNotNull(late.match(649.0, "netflix", today))
        val (tooEarly, _) = setup(dueOffset = 4L)
        assertNull(tooEarly.match(649.0, "netflix", today))
        val (tooLate, _) = setup(dueOffset = -4L)
        assertNull(tooLate.match(649.0, "netflix", today))
    }

    @Test
    fun variesAmountMatchesAnyAmount() = runBlocking {
        val (m, _) = setup(amount = null)
        assertNotNull(m.match(123.45, "netflix", today))
    }

    @Test
    fun inactiveSubscriptionNeverMatches() = runBlocking {
        val (m, _) = setup(active = false)
        assertNull(m.match(649.0, "netflix", today))
    }

    @Test
    fun noOpenCycleMeansNoMatch() = runBlocking {
        val (noCycle, _) = setup(dueOffset = null)
        assertNull(noCycle.match(649.0, "netflix", today))
        val (skipped, _) = setup(state = SubscriptionCycleState.SKIPPED)
        assertNull(skipped.match(649.0, "netflix", today))
        val (review, _) = setup(state = SubscriptionCycleState.NEEDS_REVIEW)
        assertNull(review.match(649.0, "netflix", today))
    }

    @Test
    fun paidCycleWithAmountMatchIsFlaggedAsDuplicateOfItsTransaction() = runBlocking {
        val (m, _) = setup(state = SubscriptionCycleState.PAID, txn = "txn_answered")
        val r = m.match(649.0, "netflix", today)
        assertNotNull(r)
        assertTrue(r!!.isDuplicate)
        assertEquals("txn_answered", r.duplicateOfTransactionId)
    }

    @Test
    fun paidCycleOutsideOneDayIsNotADuplicate() = runBlocking {
        val (m, _) = setup(dueOffset = -2L, state = SubscriptionCycleState.PAID, txn = "txn_answered")
        assertNull(m.match(649.0, "netflix", today))
    }

    @Test
    fun paidCycleWithDifferentAmountIsNotADuplicate() = runBlocking {
        val (m, _) = setup(state = SubscriptionCycleState.PAID, txn = "txn_answered")
        assertNull(m.match(100.0, "netflix", today))
    }

    @Test
    fun openCycleWinsOverAnsweredCycle() = runBlocking {
        val (m, repo) = setup(state = SubscriptionCycleState.PAID, txn = "txn_answered")
        repo.addCycle("sub_netflix", today + 2, SubscriptionCycleState.DUE)
        val r = m.match(649.0, "netflix", today)
        assertFalse(r!!.isDuplicate)
    }

    // ---- Cycles are ensured before matching: alerts that arrive before the evening worker runs

    private val aprilToday = java.time.LocalDate.of(2026, 4, 15).toEpochDay()

    private fun monthlyDue(dayOfMonth: Int) = testSubscription(
        anchorEpochDay = java.time.LocalDate.of(2026, 1, dayOfMonth).toEpochDay()
    )

    @Test
    fun paymentOnTheMorningOfTheDueDateMatchesAndNoReminderIsPostedThatEvening() = runBlocking {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(monthlyDue(15))
        assertTrue("no cycle exists before the worker runs", repo.cycles.value.isEmpty())

        val r = SubscriptionMatcher(repo).match(649.0, "Paid to Netflix", aprilToday)

        assertNotNull(r)
        assertEquals(aprilToday, r!!.cycle.dueEpochDay)
        // Saving the capture marks the cycle paid; the evening worker then has nothing to ask about
        repo.markCyclePaidForSubscription("sub_netflix", aprilToday, "txn_morning")
        val posted = mutableListOf<String>()
        com.financeautomation.service.worker.SubscriptionReminderWorker.runCheck(
            repo, SubscriptionScheduleUseCase(),
            com.financeautomation.service.worker.SubscriptionReminderNotifier { s, _ -> posted.add(s.name) },
            aprilToday
        )
        assertTrue(posted.isEmpty())
    }

    @Test
    fun paymentTwoDaysBeforeTheDueDateMatchesAFutureCycle() = runBlocking {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(monthlyDue(17))

        val r = SubscriptionMatcher(repo).match(649.0, "Paid to Netflix", aprilToday)

        assertNotNull(r)
        assertEquals(aprilToday + 2, r!!.cycle.dueEpochDay)
    }

    @Test
    fun paymentFourDaysBeforeTheDueDateDoesNotMatch() = runBlocking {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(monthlyDue(19))

        assertNull(SubscriptionMatcher(repo).match(649.0, "Paid to Netflix", aprilToday))
    }

    @Test
    fun futureCyclesCreatedForMatchingAreNeverReminded() = runBlocking {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(monthlyDue(17))
        SubscriptionMatcher(repo).match(1.0, "nothing relevant", aprilToday)
        SubscriptionMatcher(repo).match(649.0, "Paid to Netflix", aprilToday)
        assertEquals(1, repo.cycles.value.size)

        val posted = mutableListOf<String>()
        com.financeautomation.service.worker.SubscriptionReminderWorker.runCheck(
            repo, SubscriptionScheduleUseCase(),
            com.financeautomation.service.worker.SubscriptionReminderNotifier { s, _ -> posted.add(s.name) },
            aprilToday
        )
        assertTrue(posted.isEmpty())
        assertEquals(0, repo.cycles.value.values.single().remindCount)
    }

    @Test
    fun matcherCreatesNoCycleInAPreviousMonth() = runBlocking {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(monthlyDue(31))
        val earlyApril = java.time.LocalDate.of(2026, 4, 2).toEpochDay()

        assertNull(SubscriptionMatcher(repo).match(649.0, "Paid to Netflix", earlyApril))
        assertTrue(repo.cycles.value.isEmpty())
    }
}
