package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Review Inbox screen displaying transactions pending user review.
 */
data class ReviewInboxUiState(
    val items: List<Transaction> = emptyList(),
    val isLoading: Boolean = false,
    val selectedTransaction: Transaction? = null,
    val userMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel managing transactions needing review, 1-tap confirmation,
 * duplicate review flags, and non-destructive void operations.
 */
class ReviewInboxViewModel(
    private val transactionRepository: TransactionRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewInboxUiState(isLoading = true))
    val uiState: StateFlow<ReviewInboxUiState> = _uiState.asStateFlow()

    init {
        observeNeedsReview()
    }

    private fun observeNeedsReview() {
        viewModelScope.launch(ioDispatcher) {
            transactionRepository.getNeedsReview()
                .catch { exception ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = exception.message ?: "Failed to load transactions for review"
                        )
                    }
                }
                .collect { items ->
                    _uiState.update {
                        it.copy(
                            items = items,
                            isLoading = false
                        )
                    }
                }
        }
    }

    /**
     * 1-tap confirms a transaction, optionally updating its category.
     */
    fun confirmTransaction(txnId: String, category: String? = null) {
        viewModelScope.launch(ioDispatcher) {
            val existing = transactionRepository.getTransactionById(txnId)
            if (existing == null) {
                _uiState.update { it.copy(errorMessage = "Transaction not found") }
                return@launch
            }

            val resolvedCategory = if (!category.isNullOrBlank()) {
                val trimmed = category.trim()
                if (trimmed.equals("saving", ignoreCase = true) || trimmed.equals("savings", ignoreCase = true)) {
                    CategoryConstants.CATEGORY_SAVINGS
                } else {
                    try {
                        CategoryConstants.normalizeCategory(trimmed)
                    } catch (e: Exception) {
                        Diagnostics.record("ReviewInboxViewModel", "${e.javaClass.simpleName}: normalizeCategory fallback")
                        trimmed
                    }
                }
            } else {
                existing.category
            }

            val updated = existing.copy(
                category = resolvedCategory,
                reviewState = ReviewState.CONFIRMED,
                syncState = SyncState.PENDING,
                isPossibleDuplicate = false
            )

            when (val result = transactionRepository.updateTransaction(updated)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(userMessage = "Transaction confirmed")
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(errorMessage = result.message ?: "Failed to confirm transaction")
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    /**
     * Non-destructively voids a transaction from review inbox.
     */
    fun voidTransaction(txnId: String) {
        viewModelScope.launch(ioDispatcher) {
            when (val result = transactionRepository.voidTransaction(txnId)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(userMessage = "Transaction voided")
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(errorMessage = result.message ?: "Failed to void transaction")
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    /**
     * Selects a transaction to open details / split bottom sheet.
     */
    fun selectTransaction(transaction: Transaction) {
        _uiState.update { it.copy(selectedTransaction = transaction) }
    }

    /**
     * Clears selected transaction.
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedTransaction = null) }
    }

    /**
     * Clears user and error messages.
     */
    fun clearMessages() {
        _uiState.update { it.copy(userMessage = null, errorMessage = null) }
    }

    companion object {
        fun provideFactory(
            transactionRepository: TransactionRepository,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ReviewInboxViewModel(
                    transactionRepository = transactionRepository,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}
