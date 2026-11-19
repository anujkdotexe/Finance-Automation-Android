package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.repository.SubscriptionRepository
import java.time.LocalDate
import java.util.UUID
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.RecurringDetector
import com.financeautomation.domain.usecase.RecurringSuggestion
import com.financeautomation.domain.usecase.SubscriptionScheduleUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubscriptionsUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val activeSubscriptions: List<Subscription> = emptyList(),
    val pausedSubscriptions: List<Subscription> = emptyList(),
    val totalMonthlySpend: Double = 0.0,
    val nextDueSubscription: Subscription? = null,
    /** Next due date (epoch day, Asia/Kolkata) per active subscription id. */
    val nextDueByDay: Map<String, Long> = emptyMap(),
    /** Active subscriptions without a fixed amount; they are left out of [totalMonthlySpend]. */
    val variesAmountCount: Int = 0,
    val isLoading: Boolean = false,
    /** Payments that repeat but are not tracked yet. At most [SubscriptionsViewModel.MAX_SUGGESTIONS] are shown. */
    val suggestions: List<RecurringSuggestion> = emptyList()
)

class SubscriptionsViewModel(
    private val subscriptionRepository: SubscriptionRepository,
    private val transactionRepository: TransactionRepository? = null,
    private val preferencesRepository: PreferencesRepository? = null,
    private val detector: RecurringDetector = RecurringDetector(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val dismissedKeys = MutableStateFlow<Set<String>>(emptySet())

    private val _uiState = MutableStateFlow(SubscriptionsUiState(isLoading = true))
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

    init {
        observeSuggestions()
        viewModelScope.launch {
            subscriptionRepository.observeSubscriptions().collectLatest { allSubs ->
                val active = allSubs.filter { it.active }
                val paused = allSubs.filter { !it.active }

                // Calculate total monthly burn
                val today = todayEpochDay()
                val schedule = SubscriptionScheduleUseCase()
                val nextDueByDay = active.mapNotNull { s -> schedule.nextDue(s, today)?.let { s.id to it } }.toMap()
                val soonest = active.filter { nextDueByDay.containsKey(it.id) }.minByOrNull { nextDueByDay.getValue(it.id) }
                // A subscription without a fixed amount is unknown, not zero: left out of the total and counted
                val totalMonthly = active.filter { it.amount != null }.sumOf { sub ->
                    val amt = sub.amount ?: 0.0
                    when (sub.frequency) {
                        SubscriptionFrequency.MONTHLY -> amt
                        SubscriptionFrequency.WEEKLY -> amt * 4.33
                        SubscriptionFrequency.YEARLY -> amt / 12.0
                    }
                }

                _uiState.update { current ->
                    current.copy(
                        subscriptions = allSubs,
                        activeSubscriptions = active,
                        pausedSubscriptions = paused,
                        totalMonthlySpend = totalMonthly,
                        nextDueSubscription = soonest,
                        nextDueByDay = nextDueByDay,
                        variesAmountCount = active.count { it.amount == null },
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun observeSuggestions() {
        val transactions = transactionRepository ?: return
        viewModelScope.launch {
            dismissedKeys.value = preferencesRepository?.getDismissedRecurring().orEmpty()
            combine(
                transactions.getTransactions(),
                subscriptionRepository.observeSubscriptions(),
                dismissedKeys
            ) { txns, subs, dismissed ->
                detector.detect(txns, subs, dismissed, clock()).take(MAX_SUGGESTIONS)
            }.flowOn(computeDispatcher).collect { found ->
                _uiState.update { it.copy(suggestions = found) }
            }
        }
    }

    /** "Not now": remembers the dismissal so the suggestion does not come back. */
    fun dismissSuggestion(suggestion: RecurringSuggestion) {
        dismissedKeys.update { it + suggestion.key }
        _uiState.update { state -> state.copy(suggestions = state.suggestions.filterNot { it.key == suggestion.key }) }
        viewModelScope.launch { preferencesRepository?.addDismissedRecurring(suggestion.key) }
    }

    /** Today in Asia/Kolkata as an epoch day, from the injected clock. */
    private fun todayEpochDay(): Long =
        java.time.Instant.ofEpochMilli(clock()).atZone(SubscriptionScheduleUseCase.IST).toLocalDate().toEpochDay()

    fun addSubscription(
        name: String,
        amount: Double?,
        frequency: SubscriptionFrequency = SubscriptionFrequency.MONTHLY,
        account: String = "Primary",
        remind: Boolean = true,
        anchorEpochDay: Long? = null,
        category: String = Subscription.DEFAULT_CATEGORY,
        note: String = ""
    ) {
        viewModelScope.launch {
            val subscription = Subscription(
                id = "sub_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                name = name.trim(),
                amount = amount,
                frequency = frequency,
                anchorEpochDay = anchorEpochDay ?: todayEpochDay(),
                account = account,
                category = category,
                remind = remind,
                active = true,
                note = note.trim()
            )
            subscriptionRepository.upsertSubscription(subscription)
        }
    }

    fun toggleSubscriptionActive(id: String, active: Boolean) {
        if (active) {
            resumeSubscription(id)
        } else {
            pauseSubscription(id)
        }
    }

    fun pauseSubscription(id: String) {
        viewModelScope.launch {
            val current = subscriptionRepository.getSubscription(id) ?: return@launch
            subscriptionRepository.upsertSubscription(current.copy(active = false))
        }
    }

    fun resumeSubscription(id: String) {
        viewModelScope.launch {
            val current = subscriptionRepository.getSubscription(id) ?: return@launch
            if (!current.active) {
                val today = todayEpochDay()
                val monthStart = java.time.LocalDate.ofEpochDay(today).withDayOfMonth(1).toEpochDay()
                if (monthStart < today) {
                    subscriptionRepository.skipPausedCycles(current.id, monthStart, today - 1)
                }
                subscriptionRepository.upsertSubscription(current.copy(active = true))
            }
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch {
            subscriptionRepository.deleteSubscription(id)
        }
    }

    fun updateSubscription(subscription: Subscription) {
        viewModelScope.launch {
            subscriptionRepository.upsertSubscription(subscription)
        }
    }

    companion object {
        const val MAX_SUGGESTIONS = 2

        fun provideFactory(
            subscriptionRepository: SubscriptionRepository,
            transactionRepository: TransactionRepository? = null,
            preferencesRepository: PreferencesRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SubscriptionsViewModel(subscriptionRepository, transactionRepository, preferencesRepository) as T
            }
        }
    }
}

