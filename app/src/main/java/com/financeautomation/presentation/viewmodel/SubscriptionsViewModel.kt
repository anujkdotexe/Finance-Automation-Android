package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.repository.SubscriptionRepository
import java.time.LocalDate
import java.util.UUID
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
    val isLoading: Boolean = false
)

class SubscriptionsViewModel(
    private val subscriptionRepository: SubscriptionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionsUiState(isLoading = true))
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

    init {
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

    fun addSubscription(
        name: String,
        amount: Double?,
        frequency: SubscriptionFrequency = SubscriptionFrequency.MONTHLY,
        account: String = "Primary",
        remind: Boolean = true
    ) {
        viewModelScope.launch {
            val subscription = Subscription(
                id = "sub_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                name = name.trim(),
                amount = amount,
                frequency = frequency,
                anchorEpochDay = LocalDate.now().toEpochDay(),
                account = account,
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
        fun provideFactory(
            subscriptionRepository: SubscriptionRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SubscriptionsViewModel(subscriptionRepository) as T
            }
        }
    }
}

