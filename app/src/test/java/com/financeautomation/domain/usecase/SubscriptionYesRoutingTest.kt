package com.financeautomation.domain.usecase

import com.financeautomation.service.SubscriptionActionReceiver
import com.financeautomation.service.SubscriptionNotifier
import com.financeautomation.testutil.RecordingTransactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** C-5: Yes on a varying amount opens the app from an activity PendingIntent; the receiver must not create captures. */
class SubscriptionYesRoutingTest {

    private val createdCaptures = mutableListOf<String>()

    private fun actions(amount: Double?): Pair<SubscriptionActionUseCase, FakeSubscriptionRepository> {
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(amount = amount))
        val useCase = SubscriptionActionUseCase(
            repository = repo,
            logExpenseUseCase = LogExpenseUseCase(RecordingTransactionRepository().repository),
            createCapture = { sub, _ -> createdCaptures.add(sub.id); "cap_${createdCaptures.size}" }
        )
        return useCase to repo
    }

    @Test
    fun receiverYesForAVaryingAmountCreatesNoCaptureAndStartsNothing() = runBlocking {
        val (useCase, repo) = actions(amount = null)
        val cycle = repo.addCycle("sub_netflix", LocalDate.now(SubscriptionScheduleUseCase.IST).toEpochDay())

        val logged = SubscriptionActionReceiver.handle(SubscriptionActionReceiver.ACTION_YES, cycle.id, useCase)

        assertFalse(logged)
        assertTrue("the receiver must not leave an orphan capture behind", createdCaptures.isEmpty())
    }

    @Test
    fun yesActionTargetsAnActivityOnlyWhenTheAmountVaries() {
        assertTrue(SubscriptionNotifier.yesOpensSheet(null))
        assertFalse(SubscriptionNotifier.yesOpensSheet(649.0))
    }

    @Test
    fun receiverYesForAFixedAmountStillLogsTheExpense() = runBlocking {
        val (useCase, repo) = actions(amount = 649.0)
        val cycle = repo.addCycle("sub_netflix", LocalDate.now(SubscriptionScheduleUseCase.IST).toEpochDay())

        assertTrue(SubscriptionActionReceiver.handle(SubscriptionActionReceiver.ACTION_YES, cycle.id, useCase))
        assertEquals(0, createdCaptures.size)
    }
}
