package com.financeautomation.presentation.viewmodel

import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.usecase.FakeSubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeSubscriptionRepository
    private lateinit var viewModel: SubscriptionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeSubscriptionRepository()
        viewModel = SubscriptionsViewModel(repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_emptySubscriptions() = runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.subscriptions.isEmpty())
        assertTrue(state.activeSubscriptions.isEmpty())
        assertEquals(0.0, state.totalMonthlySpend, 0.001)
    }

    @Test
    fun observeSubscriptions_computesTotalMonthlySpendAccurately() = runTest {
        advanceUntilIdle()

        repo.addSubscription(
            Subscription(
                id = "sub_1",
                name = "Netflix",
                amount = 649.0,
                frequency = SubscriptionFrequency.MONTHLY,
                anchorEpochDay = 20000,
                account = "HDFC",
                active = true
            )
        )
        repo.addSubscription(
            Subscription(
                id = "sub_2",
                name = "Spotify",
                amount = 119.0,
                frequency = SubscriptionFrequency.MONTHLY,
                anchorEpochDay = 20000,
                account = "ICICI",
                active = true
            )
        )
        repo.addSubscription(
            Subscription(
                id = "sub_3",
                name = "Gym",
                amount = 12000.0,
                frequency = SubscriptionFrequency.YEARLY,
                anchorEpochDay = 20000,
                account = "HDFC",
                active = true
            )
        )
        repo.addSubscription(
            Subscription(
                id = "sub_4",
                name = "Old Magazine",
                amount = 500.0,
                frequency = SubscriptionFrequency.MONTHLY,
                anchorEpochDay = 20000,
                account = "Cash",
                active = false
            )
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(4, state.subscriptions.size)
        assertEquals(3, state.activeSubscriptions.size)
        assertEquals(1, state.pausedSubscriptions.size)
        // 649 (Netflix) + 119 (Spotify) + 1000 (Gym yearly / 12) = 1768.0
        assertEquals(1768.0, state.totalMonthlySpend, 0.01)
    }

    @Test
    fun addSubscription_insertsNewActiveSubscription() = runTest {
        advanceUntilIdle()

        viewModel.addSubscription(
            name = "Disney+",
            amount = 299.0,
            frequency = SubscriptionFrequency.MONTHLY,
            account = "HDFC",
            remind = true
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.activeSubscriptions.size)
        val added = state.activeSubscriptions.first()
        assertEquals("Disney+", added.name)
        assertEquals(299.0, added.amount!!, 0.01)
        assertTrue(added.active)
    }

    @Test
    fun toggleSubscriptionActive_pausesAndResumes() = runTest {
        advanceUntilIdle()

        val sub = Subscription(
            id = "sub_toggle",
            name = "Hotstar",
            amount = 149.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = 20000,
            account = "HDFC",
            active = true
        )
        repo.addSubscription(sub)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.activeSubscriptions.size)

        viewModel.toggleSubscriptionActive("sub_toggle", false)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.activeSubscriptions.size)
        assertEquals(1, viewModel.uiState.value.pausedSubscriptions.size)

        viewModel.toggleSubscriptionActive("sub_toggle", true)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.activeSubscriptions.size)
        assertEquals(0, viewModel.uiState.value.pausedSubscriptions.size)
    }
}

