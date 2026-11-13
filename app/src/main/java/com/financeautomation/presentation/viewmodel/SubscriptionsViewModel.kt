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
                val totalMonthly = active.sumOf { sub ->
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
                        nextDueSubscription = active.firstOrNull(),
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

    fun addSubscription(
        name: String,
        amount: Double?,
        frequency: SubscriptionFrequency = SubscriptionFrequency.MONTHLY,
        account: String = "Primary",
        remind: Boolean = true,
        anchorEpochDay: Long = LocalDate.now().toEpochDay(),
        category: String = Subscription.DEFAULT_CATEGORY
    ) {
        viewModelScope.launch {
            val subscription = Subscription(
                id = "sub_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                name = name.trim(),
                amount = amount,
                frequency = frequency,
                anchorEpochDay = anchorEpochDay,
                account = account,
                category = category,
                remind = remind,
                active = true
            )
            subscriptionRepository.upsertSubscription(subscription)
        }
    }

    fun toggleSubscriptionActive(id: String, active: Boolean) {
        viewModelScope.launch {
            val current = subscriptionRepository.getSubscription(id) ?: return@launch
            subscriptionRepository.upsertSubscription(current.copy(active = active))
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

