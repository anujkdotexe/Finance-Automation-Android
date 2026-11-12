package com.financeautomation.service

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.model.Result
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.usecase.FakeSubscriptionRepository
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import com.financeautomation.domain.usecase.SubscriptionMatcher
import com.financeautomation.domain.usecase.SubscriptionScheduleUseCase
import com.financeautomation.domain.usecase.testSubscription
import com.financeautomation.presentation.viewmodel.QuickLogTransactionHelper
import com.financeautomation.presentation.viewmodel.QuickLogUiState
import com.financeautomation.testutil.FakePendingCaptureDao
import com.financeautomation.testutil.RecordingTransactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Capture flow: matching a captured debit with a subscription, the duplicate guard, and saving. */
class SubscriptionCaptureTest {

    private val today = LocalDate.now(SubscriptionScheduleUseCase.IST).toEpochDay()
    private val gpay = PackageWhitelist.PACKAGE_GPAY
    // Anchor far ahead so the matcher creates no cycles of its own; these tests add their cycles by hand
    private val farFuture = today + 400

    private class Fixture {
        val repo = FakeSubscriptionRepository()
        val captures = FakePendingCaptureDao()
        val processor = NotificationProcessor(
            pendingCaptureDao = captures,
            subscriptionMatcher = SubscriptionMatcher(repo)
        )
    }

    private fun capture(f: Fixture): PendingCaptureEntity = f.captures.captures.values.single()

    @Test
    fun matchingDebitGetsSubscriptionFlagCategoryAccountAndLeavesCycleOpen() = runBlocking {
        val f = Fixture()
        f.repo.addSubscription(testSubscription(account = "HDFC", anchorEpochDay = farFuture))
        val cycle = f.repo.addCycle("sub_netflix", today)

        val outcome = f.processor.process(gpay, "Payment to Netflix", "Paid Rs 649.00 to Netflix using Bank A/c ending 1234. UPI Ref: 123456789012")

        assertTrue(outcome is NotificationProcessingOutcome.Captured)
        val cap = capture(f)
        assertEquals("sub_netflix", PendingCaptureEntity.flagValue(cap.flags, PendingCaptureEntity.FLAG_SUBSCRIPTION_PREFIX))
        assertEquals("HDFC", cap.inferredAccount)
        assertFalse(cap.flags.contains(PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE))
        assertEquals(SubscriptionCycleState.DUE, f.repo.cycle(cycle.id).state)
    }

    @Test
    fun nonMatchingAmountOrMerchantGetsNoSubscriptionFlag() = runBlocking {
        val f = Fixture()
        f.repo.addSubscription(testSubscription(anchorEpochDay = farFuture))
        f.repo.addCycle("sub_netflix", today)

        f.processor.process(gpay, "Payment to Swiggy", "Paid Rs 649.00 to Swiggy using Bank A/c ending 1234. UPI Ref: 123456789012")

        assertNull(PendingCaptureEntity.flagValue(capture(f).flags, PendingCaptureEntity.FLAG_SUBSCRIPTION_PREFIX))
    }

    @Test
    fun captureForAlreadyAnsweredCycleIsFlaggedDuplicateOfItsTransaction() = runBlocking {
        val f = Fixture()
        f.repo.addSubscription(testSubscription(anchorEpochDay = farFuture))
        f.repo.addCycle("sub_netflix", today, SubscriptionCycleState.PAID, transactionId = "txn_answered")

        f.processor.process(gpay, "Payment to Netflix", "Paid Rs 649.00 to Netflix using Bank A/c ending 1234. UPI Ref: 123456789012")

        val cap = capture(f)
        assertTrue(cap.flags.contains(PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE))
        assertEquals("txn_answered", PendingCaptureEntity.flagValue(cap.flags, PendingCaptureEntity.FLAG_DUPLICATE_OF_PREFIX))
        assertNull(PendingCaptureEntity.flagValue(cap.flags, PendingCaptureEntity.FLAG_SUBSCRIPTION_PREFIX))
    }

    private fun savedState(subscriptionId: String?) = QuickLogUiState(
        captureId = "cap_1",
        captureTimestamp = System.currentTimeMillis(),
        amount = 649.0,
        payee = "Netflix",
        selectedAccount = "HDFC",
        selectedCategory = "Subscriptions",
        subscriptionId = subscriptionId
    )

    @Test
    fun savingSubscriptionCaptureMarksCyclePaidWithTransactionId() = runBlocking {
        val f = Fixture()
        f.repo.addSubscription(testSubscription(anchorEpochDay = farFuture))
        val cycle = f.repo.addCycle("sub_netflix", today)
        f.captures.insert(PendingCaptureEntity("cap_1", gpay, 649.0, "Netflix", "HDFC", System.currentTimeMillis(), "DEBIT", "SUBSCRIPTION:sub_netflix", "WAITING", null, null, 0L))
        val recorder = RecordingTransactionRepository()

        val result = QuickLogTransactionHelper.logNewExpense(
            state = savedState("sub_netflix"),
            logExpenseUseCase = LogExpenseUseCase(recorder.repository),
            pendingCaptureDaoProvider = { f.captures },
            categoryStatDaoProvider = null,
            predictCategoryUseCase = PredictCategoryUseCase(),
            subscriptionRepository = f.repo
        )

        assertTrue(result is Result.Success)
        val txn = recorder.inserted.single()
        val updated = f.repo.cycle(cycle.id)
        assertEquals(SubscriptionCycleState.PAID, updated.state)
        assertEquals(txn.id, updated.transactionId)
        assertEquals(PendingCaptureEntity.STATE_DONE, f.captures.captures["cap_1"]!!.state)
    }

    @Test
    fun savingCaptureWithoutSubscriptionFlagLeavesCyclesAlone() = runBlocking {
        val f = Fixture()
        f.repo.addSubscription(testSubscription(anchorEpochDay = farFuture))
        val cycle = f.repo.addCycle("sub_netflix", today)
        f.captures.insert(PendingCaptureEntity("cap_1", gpay, 649.0, "Netflix", "HDFC", System.currentTimeMillis(), "DEBIT", "", "WAITING", null, null, 0L))
        val recorder = RecordingTransactionRepository()

        QuickLogTransactionHelper.logNewExpense(
            state = savedState(null),
            logExpenseUseCase = LogExpenseUseCase(recorder.repository),
            pendingCaptureDaoProvider = { f.captures },
            categoryStatDaoProvider = null,
            predictCategoryUseCase = PredictCategoryUseCase(),
            subscriptionRepository = f.repo
        )

        assertEquals(SubscriptionCycleState.DUE, f.repo.cycle(cycle.id).state)
        assertNull(f.repo.cycle(cycle.id).transactionId)
    }

    @Test
    fun dismissedCaptureKeepsCycleOpen() = runBlocking {
        val f = Fixture()
        f.repo.addSubscription(testSubscription(anchorEpochDay = farFuture))
        val cycle = f.repo.addCycle("sub_netflix", today)
        f.processor.process(gpay, "Payment to Netflix", "Paid Rs 649.00 to Netflix using Bank A/c ending 1234. UPI Ref: 123456789012")
        val cap = capture(f)

        QuickLogTransactionHelper.dismissCapture(cap.id) { f.captures }

        assertEquals(PendingCaptureEntity.STATE_DISMISSED, f.captures.captures[cap.id]!!.state)
        assertEquals(SubscriptionCycleState.DUE, f.repo.cycle(cycle.id).state)
        assertNotNull(f.repo.cycle(cycle.id))
    }
}
