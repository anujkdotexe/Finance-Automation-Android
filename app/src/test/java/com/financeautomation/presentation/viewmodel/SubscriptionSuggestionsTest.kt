package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.FakeSubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionSuggestionsTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)
    private val now = today.atTime(12, 0).atZone(kolkata).toInstant().toEpochMilli()
    private var counter = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun monthly(name: String, amount: Double, category: String = "Entertainment") =
        listOf(61L, 31L, 1L).map { daysAgo ->
            Transaction(
                id = "t${counter++}", timestamp = today.minusDays(daysAgo).atTime(9, 0).atZone(kolkata).toInstant().toEpochMilli(),
                dateSerial = 0, timeSerial = 0.0, account = "HDFC", merchant = name, category = category,
                totalAmount = amount, myShare = amount, owedToMe = 0.0, status = TransactionStatus.PERSONAL
            )
        }

    /** In-memory preferences that answer on the calling thread, so the tests are deterministic. */
    private fun headlessPrefs() = PreferencesRepositoryImpl(
        preferenceDao = null,
        preferenceDaoProvider = null,
        configSyncCoordinatorProvider = null,
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun txnRepo(list: List<Transaction>): TransactionRepository = Proxy.newProxyInstance(
        TransactionRepository::class.java.classLoader, arrayOf(TransactionRepository::class.java)
    ) { _, method, _ ->
        if (method.name == "getTransactions") flowOf(list) else throw UnsupportedOperationException(method.name)
    } as TransactionRepository

    private fun viewModel(
        list: List<Transaction>,
        prefs: PreferencesRepositoryImpl = headlessPrefs(),
        subs: FakeSubscriptionRepository = FakeSubscriptionRepository()
    ) = SubscriptionsViewModel(
        subscriptionRepository = subs,
        transactionRepository = txnRepo(list),
        preferencesRepository = prefs,
        computeDispatcher = Dispatchers.Unconfined,
        clock = { now }
    )

    @Test
    fun repeatingPaymentsAreShown() = runTest {
        val vm = viewModel(monthly("Netflix", 649.0))
        val suggestion = vm.uiState.value.suggestions.single()
        assertEquals("Netflix", suggestion.name)
        assertEquals(649.0, suggestion.amount, 0.0)
        assertEquals(SubscriptionFrequency.MONTHLY, suggestion.frequency)
    }

    @Test
    fun atMostTwoSuggestionsAreShown() = runTest {
        val vm = viewModel(
            monthly("Alpha", 100.0) + monthly("Bravo", 200.0) + monthly("Charlie", 300.0) + monthly("Delta", 400.0)
        )
        assertEquals(listOf("Alpha", "Bravo"), vm.uiState.value.suggestions.map { it.name })
    }

    @Test
    fun noSuggestionsWithoutRepeatingPayments() = runTest {
        val vm = viewModel(emptyList())
        assertTrue(vm.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun notNowRemovesTheCardAndRemembersIt() = runTest {
        val prefs = headlessPrefs()
        val vm = viewModel(monthly("Netflix", 649.0) + monthly("Spotify", 119.0), prefs)
        val netflix = vm.uiState.value.suggestions.first { it.name == "Netflix" }

        vm.dismissSuggestion(netflix)

        assertEquals(listOf("Spotify"), vm.uiState.value.suggestions.map { it.name })
        assertTrue(netflix.key in prefs.getDismissedRecurring())

        // A new session loads the dismissal and never shows Netflix again
        val again = viewModel(monthly("Netflix", 649.0) + monthly("Spotify", 119.0), prefs)
        assertEquals(listOf("Spotify"), again.uiState.value.suggestions.map { it.name })
    }

    @Test
    fun trackingCreatesTheSubscriptionAndTheSuggestionGoes() = runTest {
        val subs = FakeSubscriptionRepository()
        val vm = viewModel(monthly("Netflix", 649.0), subs = subs)
        val suggestion = vm.uiState.value.suggestions.single()

        vm.addSubscription(
            name = suggestion.name,
            amount = suggestion.amount,
            frequency = suggestion.frequency,
            account = suggestion.account,
            remind = true,
            anchorEpochDay = suggestion.anchorEpochDay,
            category = suggestion.category
        )

        val created = vm.uiState.value.subscriptions.single()
        assertEquals("Netflix", created.name)
        assertEquals(suggestion.anchorEpochDay, created.anchorEpochDay)
        assertEquals("Entertainment", created.category)
        assertEquals(649.0, created.amount!!, 0.0)
        assertTrue("an already tracked payment is no longer suggested", vm.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun withoutAnAnchorTheOldDefaultsStillApply() = runTest {
        val vm = viewModel(emptyList())
        vm.addSubscription("Gym", 500.0)
        val created = vm.uiState.value.subscriptions.single()
        assertEquals("Subscriptions", created.category)
        assertEquals(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().toEpochDay(), created.anchorEpochDay)
    }

    @Test
    fun testSuggestionCard_appearsWhenDetected_andHiddenAfterNotNow() = runTest {
        val prefs = headlessPrefs()
        val txns = monthly("YouTube Premium", 149.0, "Entertainment")
        val vm = viewModel(txns, prefs)

        // 1. Proves the suggestion card data appears when RecurringDetector returns a suggestion
        assertEquals(1, vm.uiState.value.suggestions.size)
        val suggestion = vm.uiState.value.suggestions.first()
        assertEquals("YouTube Premium", suggestion.name)
        assertEquals(149.0, suggestion.amount, 0.0)
        assertEquals(SubscriptionFrequency.MONTHLY, suggestion.frequency)

        // 2. User taps "Not now", which calls dismissSuggestion()
        vm.dismissSuggestion(suggestion)

        // 3. Proves the suggestion card is hidden from the UI state and persisted
        assertTrue(vm.uiState.value.suggestions.isEmpty())
        assertTrue(suggestion.key in prefs.getDismissedRecurring())
    }
}
