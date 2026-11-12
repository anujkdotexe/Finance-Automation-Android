package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * UI state for the Review Inbox screen displaying transactions pending user review.
 */
data class ReviewInboxUiState(
    val items: List<Transaction> = emptyList(),
    val captures: List<PendingCaptureEntity> = emptyList(),
    val newAccountCaptures: List<PendingCaptureEntity> = emptyList(),
    val failedQueueItems: List<SyncQueueEntity> = emptyList(),
    val isLoading: Boolean = false,
    val selectedTransaction: Transaction? = null,
    val openQuickLogCaptureId: String? = null,
    val userMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel managing transactions needing review, 1-tap confirmation,
 * duplicate review flags, failed queue recovery, and non-destructive void operations.
 */
class ReviewInboxViewModel(
    private val transactionRepository: TransactionRepository,
    private val directSyncQueueDao: SyncQueueDao? = null,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    private val directPendingCaptureDao: PendingCaptureDao? = null,
    private val pendingCaptureDaoProvider: (suspend () -> PendingCaptureDao)? = null,
    private val accountRepository: AccountRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDao: SyncQueueDao?,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = syncQueueDao,
        syncQueueDaoProvider = null,
        directPendingCaptureDao = null,
        pendingCaptureDaoProvider = null,
        accountRepository = null,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDaoProvider: suspend () -> SyncQueueDao,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = null,
        syncQueueDaoProvider = syncQueueDaoProvider,
        directPendingCaptureDao = null,
        pendingCaptureDaoProvider = null,
        accountRepository = null,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDao: SyncQueueDao?,
        pendingCaptureDao: PendingCaptureDao?,
        accountRepository: AccountRepository?,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = syncQueueDao,
        syncQueueDaoProvider = null,
        directPendingCaptureDao = pendingCaptureDao,
        pendingCaptureDaoProvider = null,
        accountRepository = accountRepository,
        ioDispatcher = ioDispatcher
    )

    private val _uiState = MutableStateFlow(ReviewInboxUiState(isLoading = true))
    val uiState: StateFlow<ReviewInboxUiState> = _uiState.asStateFlow()

    init {
        observeNeedsReview()
        observeFailedQueue()
        observeCaptures()
    }

    private suspend fun resolveSyncQueueDao(): SyncQueueDao? =
        directSyncQueueDao ?: syncQueueDaoProvider?.invoke()

    private suspend fun resolvePendingCaptureDao(): PendingCaptureDao? =
        directPendingCaptureDao ?: pendingCaptureDaoProvider?.invoke()

    private fun observeFailedQueue() {
        viewModelScope.launch(ioDispatcher) {
            val qDao = resolveSyncQueueDao() ?: return@launch
            qDao.getFailedQueueFlow()
                .catch { /* ignore */ }
                .collect { failedItems ->
                    _uiState.update { it.copy(failedQueueItems = failedItems) }
                }
        }
    }

    private fun observeCaptures() {
        viewModelScope.launch(ioDispatcher) {
            val captureDao = resolvePendingCaptureDao() ?: return@launch
            combine(
                captureDao.getNeedsReviewCapturesFlow(),
                captureDao.getNewAccountCapturesFlow()
            ) { needsReview, newAccounts ->
                Pair(needsReview, newAccounts)
            }.catch { /* ignore */ }
                .collect { (needsReview, newAccounts) ->
                    _uiState.update {
                        it.copy(
                            captures = needsReview,
                            newAccountCaptures = newAccounts
                        )
                    }
                }
        }
    }

    fun confirmCapture(id: String) {
        _uiState.update { it.copy(openQuickLogCaptureId = id) }
    }

    fun clearOpenQuickLogCaptureId() {
        _uiState.update { it.copy(openQuickLogCaptureId = null) }
    }

    fun dismissCapture(id: String) {
        viewModelScope.launch(ioDispatcher) {
            val captureDao = resolvePendingCaptureDao() ?: return@launch
            captureDao.updateState(id, PendingCaptureEntity.STATE_DISMISSED)
            _uiState.update { it.copy(userMessage = "Capture dismissed") }
        }
    }

    fun addAccountFromCapture(captureId: String, name: String, lastFour: String) {
        viewModelScope.launch(ioDispatcher) {
            val accountRepo = accountRepository ?: return@launch
            val captureDao = resolvePendingCaptureDao() ?: return@launch

            val existingAccounts = accountRepo.getAllAccounts(includeArchived = true)
            val isFirst = existingAccounts.isEmpty()

            val newAccount = Account(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                lastFourDigits = lastFour.trim(),
                isDefault = isFirst
            )
            accountRepo.insertAccount(newAccount)
            if (isFirst) {
                accountRepo.setDefaultAccount(newAccount.id)
            }

            val capture = captureDao.getById(captureId)
            if (capture != null) {
                val updatedFlags = capture.flags
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals(PendingCaptureEntity.FLAG_NEW_ACCOUNT, ignoreCase = true) }
                    .joinToString(",")
                val updated = capture.copy(
                    flags = updatedFlags,
                    detectedAccountHint = null,
                    updatedAt = System.currentTimeMillis()
                )
                captureDao.update(updated)
            }
            _uiState.update { it.copy(userMessage = "Account added") }
        }
    }

    suspend fun confirmAllEligible(): Int {
        val currentItems = transactionRepository.getNeedsReview().firstOrNull() ?: _uiState.value.items
        var confirmedCount = 0

        for (txn in currentItems) {
            val isEligible = txn.reviewState == ReviewState.NEEDS_REVIEW &&
                txn.totalAmount > 0.0 &&
                txn.category.isNotBlank() &&
                !txn.isPossibleDuplicate &&
                txn.status != TransactionStatus.VOID &&
                txn.account.isNotBlank() &&
                !txn.account.equals("unknown", ignoreCase = true)

            if (isEligible) {
                val updated = txn.copy(
                    reviewState = ReviewState.CONFIRMED,
                    syncState = SyncState.PENDING,
                    isPossibleDuplicate = false
                )
                val res = transactionRepository.updateTransaction(updated)
                if (res is Result.Success) {
                    confirmedCount++
                }
            }
        }
        if (confirmedCount > 0) {
            _uiState.update { it.copy(userMessage = "Confirmed $confirmedCount transaction(s)") }
        }
        return confirmedCount
    }

    fun keepBoth(id: String) {
        viewModelScope.launch(ioDispatcher) {
            val target = transactionRepository.getTransactionById(id) ?: return@launch
            val otherId = target.duplicateOfId ?: transactionRepository.getTransactions().firstOrNull()?.find { it.duplicateOfId == id }?.id
            val other = otherId?.let { transactionRepository.getTransactionById(it) }

            val updatedTarget = target.copy(
                isPossibleDuplicate = false,
                duplicateOfId = null,
                reviewState = ReviewState.CONFIRMED,
                syncState = SyncState.PENDING
            )
            transactionRepository.updateTransaction(updatedTarget)

            if (other != null) {
                val updatedOther = other.copy(
                    isPossibleDuplicate = false,
                    duplicateOfId = null,
                    reviewState = ReviewState.CONFIRMED,
                    syncState = SyncState.PENDING
                )
                transactionRepository.updateTransaction(updatedOther)
            }
            _uiState.update { it.copy(userMessage = "Kept both transactions") }
        }
    }

    fun keepOne(id: String) {
        viewModelScope.launch(ioDispatcher) {
            val target = transactionRepository.getTransactionById(id) ?: return@launch
            val otherId = target.duplicateOfId ?: transactionRepository.getTransactions().firstOrNull()?.find { it.duplicateOfId == id }?.id
            val other = otherId?.let { transactionRepository.getTransactionById(it) }

            if (other == null) {
                transactionRepository.voidTransaction(target.id)
            } else {
                val (newer, older) = if (target.timestamp >= other.timestamp) Pair(target, other) else Pair(other, target)
                transactionRepository.voidTransaction(newer.id)
                val updatedOlder = older.copy(
                    isPossibleDuplicate = false,
                    duplicateOfId = null,
                    reviewState = ReviewState.CONFIRMED,
                    syncState = SyncState.PENDING
                )
                transactionRepository.updateTransaction(updatedOlder)
            }
            _uiState.update { it.copy(userMessage = "Kept one transaction") }
        }
    }

    fun retryQueueItem(id: Long) {
        viewModelScope.launch(ioDispatcher) {
            val qDao = resolveSyncQueueDao() ?: return@launch
            qDao.setStatusAndRetryCount(id, SyncQueueEntity.STATUS_PENDING, 0)
            transactionRepository.syncPendingTransactions()
            _uiState.update { it.copy(userMessage = "Operation queued for retry") }
        }
    }

    fun discardQueueItem(id: Long) {
        viewModelScope.launch(ioDispatcher) {
            val qDao = resolveSyncQueueDao() ?: return@launch
            qDao.deleteById(id)
            _uiState.update { it.copy(userMessage = "Operation discarded") }
        }
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
            syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
            pendingCaptureDaoProvider: (suspend () -> PendingCaptureDao)? = null,
            accountRepository: AccountRepository? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ReviewInboxViewModel(
                    transactionRepository = transactionRepository,
                    syncQueueDaoProvider = syncQueueDaoProvider,
                    pendingCaptureDaoProvider = pendingCaptureDaoProvider,
                    accountRepository = accountRepository,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}
