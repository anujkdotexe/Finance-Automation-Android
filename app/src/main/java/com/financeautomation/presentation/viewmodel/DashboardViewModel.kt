package com.financeautomation.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.service.worker.SyncTransactionsWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * UI state for the Live Dashboard and Offline Sync Queue destination.
 */
data class DashboardUiState(
    val currentBurn: Double = 0.0,
    val owedToMe: Double = 0.0,
    val iOwePeople: Double = 0.0,
    val savings: Double = 0.0,
    val transactionCount: Int = 0,
    val syncQueueItems: List<SyncQueueEntity> = emptyList(),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel aggregating real-time monthly financial KPIs and observing
 * Room sync_queue entries for reactive offline sync status.
 */
class DashboardViewModel(
    private val transactionRepository: TransactionRepository,
    private val syncQueueDao: SyncQueueDao,
    private val categoryRepository: CategoryRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch(ioDispatcher) {
            combine(
                transactionRepository.getTransactions(),
                syncQueueDao.getAllQueueFlow()
            ) { transactions, queueItems ->
                calculateDashboardMetrics(transactions, queueItems)
            }.collect { calculatedState ->
                _uiState.update { current ->
                    calculatedState.copy(
                        isSyncing = current.isSyncing,
                        syncMessage = current.syncMessage,
                        errorMessage = current.errorMessage
                    )
                }
            }
        }
    }

    private fun calculateDashboardMetrics(
        transactions: List<Transaction>,
        queueItems: List<SyncQueueEntity>
    ): DashboardUiState {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfMonth = cal.timeInMillis

        cal.add(Calendar.MONTH, 1)
        val endOfMonth = cal.timeInMillis

        var currentBurn = 0.0
        var owedToMe = 0.0
        val iOwePeople = 0.0
        var savings = 0.0
        var txnCount = 0

        for (txn in transactions) {
            // Unsettled receivables
            if (txn.status == TransactionStatus.PENDING) {
                owedToMe += txn.owedToMe
            }

            // Monthly KPIs
            if (txn.timestamp in startOfMonth until endOfMonth && txn.status != TransactionStatus.VOID) {
                txnCount++

                val isSavings = txn.category.equals(CategoryConstants.CATEGORY_SAVINGS, ignoreCase = true)
                val isTransfer = txn.category.equals("Transfer", ignoreCase = true)

                if (isSavings) {
                    savings += txn.myShare
                }

                if (!isSavings && !isTransfer) {
                    currentBurn += txn.myShare
                }
            }
        }

        return DashboardUiState(
            currentBurn = currentBurn,
            owedToMe = owedToMe,
            iOwePeople = iOwePeople,
            savings = savings,
            transactionCount = txnCount,
            syncQueueItems = queueItems
        )
    }

    /**
     * Forces immediate synchronization of pending queue items to Google Sheets.
     */
    fun syncNow(context: Context? = null) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSyncing = true, syncMessage = null, errorMessage = null) }

            if (context != null) {
                try {
                    SyncTransactionsWorker.enqueueExpedited(context.applicationContext)
                } catch (_: Exception) {
                    // Safe fallback for isolated or test environments
                }
            }

            when (val res = transactionRepository.syncPendingTransactions()) {
                is Result.Success -> {
                    val count = res.data
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncMessage = "Synced $count transaction(s) to Google Sheets"
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            errorMessage = res.message ?: "Sync failed with error: ${res.throwable.message}"
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(syncMessage = null, errorMessage = null) }
    }

    companion object {
        fun provideFactory(
            transactionRepository: TransactionRepository,
            syncQueueDao: SyncQueueDao,
            categoryRepository: CategoryRepository? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(
                    transactionRepository = transactionRepository,
                    syncQueueDao = syncQueueDao,
                    categoryRepository = categoryRepository,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}

