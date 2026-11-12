package com.financeautomation.presentation.viewmodel

import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.FakeSubscriptionRepository
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.domain.usecase.SubscriptionActionUseCase
import com.financeautomation.domain.usecase.SubscriptionScheduleUseCase
import com.financeautomation.domain.usecase.testSubscription
import com.financeautomation.testutil.RecordingTransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

/** Needs Review hooks for subscriptions: the list of unanswered cycles and its Yes, Not yet, Skip actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionReviewHooksTest {

    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.now(SubscriptionScheduleUseCase.IST).toEpochDay()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val idleTransactionRepository: TransactionRepository = Proxy.newProxyInstance(
        TransactionRepository::class.java.classLoader, arrayOf(TransactionRepository::class.java)
    ) { _, method, _ ->
        if (method.returnType == kotlinx.coroutines.flow.Flow::class.java) emptyFlow<Any>() else null
    } as TransactionRepository

    private class Fixture(amount: Double?) {
        val recorder = RecordingTransactionRepository()
        val repo = FakeSubscriptionRepository()
        val actions = SubscriptionActionUseCase(
            repository = repo,
            logExpenseUseCase = LogExpenseUseCase(recorder.repository),
            createCapture = { _, _ -> "cap_varies" }
        )
        init { repo.addSubscription(testSubscription(amount = amount)) }
    }

    private fun viewModel(f: Fixture) = ReviewInboxViewModel(
        transactionRepository = idleTransactionRepository,
        ioDispatcher = dispatcher,
        subscriptionRepository = f.repo,
        subscriptionActions = f.actions
    )

    @Test
    fun subscriptionsDueExposesOnlyNeedsReviewCyclesWithTheirSubscription() = runTest(dispatcher) {
        val f = Fixture(649.0)
        f.repo.addCycle("sub_netflix", today, SubscriptionCycleState.NEEDS_REVIEW, remindCount = 3)
        f.repo.addCycle("sub_netflix", today - 35, SubscriptionCycleState.DUE)
        f.repo.addCycle("sub_netflix", today - 65, SubscriptionCycleState.PAID, "txn_1")
        val vm = viewModel(f)
        advanceUntilIdle()

        val due = vm.subscriptionsDue.value
        assertEquals(1, due.size)
        assertEquals("Netflix", due.single().subscription.name)
        assertEquals(today, due.single().cycle.dueEpochDay)
    }

    @Test
    fun yesLogsExpenseAndRemovesItemFromList() = runTest(dispatcher) {
        val f = Fixture(649.0)
        val cycle = f.repo.addCycle("sub_netflix", today, SubscriptionCycleState.NEEDS_REVIEW, remindCount = 3)
        val vm = viewModel(f)
        advanceUntilIdle()

        vm.subscriptionYes(cycle.id)
        advanceUntilIdle()

        assertEquals(1, f.recorder.inserted.size)
        assertEquals(SubscriptionCycleState.PAID, f.repo.cycle(cycle.id).state)
        assertTrue(vm.subscriptionsDue.value.isEmpty())
        assertEquals("Subscription expense saved", vm.uiState.value.userMessage)
    }

    @Test
    fun yesWithVariesAmountOpensPrefilledCapture() = runTest(dispatcher) {
        val f = Fixture(null)
        val cycle = f.repo.addCycle("sub_netflix", today, SubscriptionCycleState.NEEDS_REVIEW, remindCount = 3)
        val vm = viewModel(f)
        advanceUntilIdle()

        vm.subscriptionYes(cycle.id)
        advanceUntilIdle()

        assertEquals("cap_varies", vm.uiState.value.openQuickLogCaptureId)
        assertTrue(f.recorder.inserted.isEmpty())
    }

    @Test
    fun notYetGoesBackToDueWithReminderCountTwo() = runTest(dispatcher) {
        val f = Fixture(649.0)
        val cycle = f.repo.addCycle("sub_netflix", today, SubscriptionCycleState.NEEDS_REVIEW, remindCount = 3)
        val vm = viewModel(f)
        advanceUntilIdle()

        vm.subscriptionNotYet(cycle.id)
        advanceUntilIdle()

        assertEquals(SubscriptionCycleState.DUE, f.repo.cycle(cycle.id).state)
        assertEquals(2, f.repo.cycle(cycle.id).remindCount)
        assertTrue(vm.subscriptionsDue.value.isEmpty())
    }

    @Test
    fun skipMarksSkippedAndLogsNothing() = runTest(dispatcher) {
        val f = Fixture(649.0)
        val cycle = f.repo.addCycle("sub_netflix", today, SubscriptionCycleState.NEEDS_REVIEW, remindCount = 3)
        val vm = viewModel(f)
        advanceUntilIdle()

        vm.subscriptionSkip(cycle.id)
        advanceUntilIdle()

        assertEquals(SubscriptionCycleState.SKIPPED, f.repo.cycle(cycle.id).state)
        assertTrue(f.recorder.inserted.isEmpty())
        assertNull(vm.uiState.value.errorMessage)
    }
}
