package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.LogExpenseUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder and action coordinator for the floating Quick Log sheet.
 * Scoped to the Overlay's ViewModelStoreOwner.
 */
class QuickLogViewModel(
    private val transactionRepository: TransactionRepository,
    private val peopleRepository: PeopleRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val tripTagRepository: TripTagRepository,
    private val pendingCaptureDaoProvider: suspend () -> PendingCaptureDao,
    private val syncQueueDaoProvider: suspend () -> SyncQueueDao,
    private val calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
    private val logExpenseUseCase: LogExpenseUseCase = LogExpenseUseCase(transactionRepository, calculateSplitUseCase),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickLogUiState())
    val uiState: StateFlow<QuickLogUiState> = _uiState.asStateFlow()

    init {
        observeCompanions()
        observeCategories()
        observeAccounts()
        observeActiveTripTag()
    }

    private fun observeCompanions() {
        viewModelScope.launch(ioDispatcher) {
            peopleRepository.getCompanions().collect { companions ->
                _uiState.update { it.copy(availableCompanions = companions) }
            }
        }
    }

    private fun observeCategories() {
        viewModelScope.launch(ioDispatcher) {
            categoryRepository.getCategories().collect { categories ->
                _uiState.update { current ->
                    current.copy(
                        availableCategories = categories,
                        selectedCategory = if (categories.any { it.name.equals(current.selectedCategory, ignoreCase = true) }) {
                            current.selectedCategory
                        } else {
                            categories.firstOrNull()?.name ?: CategoryConstants.CATEGORY_FOOD_AND_DRINK
                        }
                    )
                }
            }
        }
    }

    private fun observeAccounts() {
        viewModelScope.launch(ioDispatcher) {
            accountRepository.getAccounts().collect { accounts ->
                _uiState.update { current ->
                    current.copy(
                        availableAccounts = accounts,
                        selectedAccount = if (accounts.any { it.name.equals(current.selectedAccount, ignoreCase = true) }) {
                            current.selectedAccount
                        } else {
                            accounts.firstOrNull()?.name ?: CategoryConstants.ACCOUNT_SBI
                        }
                    )
                }
            }
        }
    }

    private fun observeActiveTripTag() {
        viewModelScope.launch(ioDispatcher) {
            tripTagRepository.getActiveTag().collect { activeTag ->
                _uiState.update { it.copy(activeTripTag = activeTag) }
            }
        }
    }

    /**
     * Loads a pending capture intercepted by the NotificationListenerService.
     */
    fun loadCapture(captureId: String) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val dao = pendingCaptureDaoProvider.invoke()
                val capture = dao.getById(captureId) ?: return@launch
                val amountVal = capture.amount ?: 0.0
                val amountStr = if (amountVal > 0.0) String.format(java.util.Locale.US, "%.2f", amountVal) else ""
                val isNeedsAmount = capture.flags.contains(PendingCaptureEntity.FLAG_NEEDS_AMOUNT) || amountVal <= 0.0
                val isPossibleDup = capture.flags.contains(PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE)

                _uiState.update { current ->
                    val resolvedAccount = capture.inferredAccount?.takeIf { it.isNotBlank() } ?: current.selectedAccount
                    current.copy(
                        captureId = capture.id,
                        sourcePackage = capture.sourcePackage,
                        amountText = amountStr,
                        amount = amountVal,
                        payee = capture.payee,
                        selectedAccount = resolvedAccount,
                        isNeedsAmount = isNeedsAmount,
                        isPossibleDuplicate = isPossibleDup
                    )
                }
                recalculateSplit()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: loadCapture")
            }
        }
    }

    /**
     * Initializes manual entry mode without a pending capture.
     */
    fun setManualMode() {
        _uiState.update {
            it.copy(
                captureId = null,
                sourcePackage = "manual",
                amountText = "",
                amount = 0.0,
                payee = "",
                isNeedsAmount = true,
                isPossibleDuplicate = false,
                isSaved = false,
                isDismissed = false
            )
        }
        recalculateSplit()
    }

    fun onAmountChanged(text: String) {
        val sanitized = text.filter { it.isDigit() || it == '.' }
        val parsed = sanitized.toDoubleOrNull() ?: 0.0
        _uiState.update { it.copy(amountText = sanitized, amount = parsed) }
        recalculateSplit()
    }

    fun onPayeeChanged(text: String) {
        _uiState.update { it.copy(payee = text) }
    }

    fun onAccountSelected(accountName: String) {
        _uiState.update { it.copy(selectedAccount = accountName) }
    }

    fun onCategorySelected(categoryName: String) {
        _uiState.update { it.copy(selectedCategory = categoryName) }
    }

    fun onSplitModeChanged(mode: SplitMode) {
        _uiState.update { it.copy(splitMode = mode) }
        recalculateSplit()
    }

    fun onCompanionToggled(companionId: String) {
        _uiState.update { current ->
            val set = current.selectedCompanionIds.toMutableSet()
            if (set.contains(companionId)) set.remove(companionId) else set.add(companionId)
            current.copy(selectedCompanionIds = set)
        }
        recalculateSplit()
    }

    fun onAddNewCompanion(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val newCompanion = Companion(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name.trim()
                )
                peopleRepository.insertCompanion(newCompanion)
                _uiState.update { current ->
                    current.copy(selectedCompanionIds = current.selectedCompanionIds + newCompanion.id)
                }
                recalculateSplit()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: onAddNewCompanion")
            }
        }
    }

    fun onCustomShareChanged(shareText: String) {
        val sanitized = shareText.filter { it.isDigit() || it == '.' }
        val parsed = sanitized.toDoubleOrNull()
        _uiState.update { it.copy(customShareText = sanitized, customShare = parsed) }
        recalculateSplit()
    }

    fun onEndTrip() {
        viewModelScope.launch(ioDispatcher) {
            try {
                tripTagRepository.setActiveTag(null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: onEndTrip")
            }
        }
    }

    private fun recalculateSplit() {
        val state = _uiState.value
        val total = state.amount
        val selectedCompanions = state.availableCompanions
            .filter { state.selectedCompanionIds.contains(it.id) }
            .map { it.name }

        val calculation = when (state.splitMode) {
            SplitMode.JUST_ME -> calculateSplitUseCase.calculateJustMe(total)
            SplitMode.SPLIT_EQUALLY -> calculateSplitUseCase.calculateSplitEqually(total, selectedCompanions)
            SplitMode.CUSTOM -> calculateSplitUseCase.calculateCustomSplit(
                totalAmount = total,
                myShare = state.customShare ?: total,
                companions = selectedCompanions
            )
        }

        _uiState.update {
            it.copy(
                myShare = calculation.myShare,
                owedToMe = calculation.owedToMe
            )
        }
    }

    /**
     * Executes Confirm and Save (Phase 5.3.4):
     * 1. Validates amount > 0.
     * 2. Logs expense through LogExpenseUseCase (which stores transaction and sync queue item).
     * 3. Marks pending capture as DONE.
     * 4. Notifies completion callback for result pill display.
     */
    fun onConfirmAndSave(onSaved: (String) -> Unit) {
        val state = _uiState.value
        if (state.amount <= 0.0) {
            _uiState.update { it.copy(errorMessage = "Amount must be greater than zero") }
            return
        }

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val selectedCompanions = state.availableCompanions
                    .filter { state.selectedCompanionIds.contains(it.id) }
                    .map { it.name }

                val result = logExpenseUseCase(
                    amount = state.amount,
                    merchant = state.payee.ifBlank { "Expense" },
                    category = state.selectedCategory,
                    account = state.selectedAccount,
                    splitMode = state.splitMode,
                    companions = selectedCompanions,
                    customShare = state.customShare,
                    tripTag = state.activeTripTag?.name ?: "",
                    sourcePackage = state.sourcePackage,
                    timestamp = System.currentTimeMillis(),
                    reviewState = ReviewState.CONFIRMED
                )

                when (result) {
                    is Result.Success -> {
                        // Mark capture DONE
                        state.captureId?.let { cid ->
                            try {
                                pendingCaptureDaoProvider.invoke().updateState(
                                    cid,
                                    PendingCaptureEntity.STATE_DONE
                                )
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: updateState DONE")
                            }
                        }

                        val message = "Saved, will sync"
                        _uiState.update {
                            it.copy(isSaving = false, isSaved = true, resultMessage = message)
                        }
                        onSaved(message)
                    }
                    is Result.Error -> {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = result.message
                            )
                        }
                    }
                    is Result.Loading -> Unit
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: onConfirmAndSave")
                _uiState.update { it.copy(isSaving = false, errorMessage = "Save failed: ${e.message}") }
            }
        }
    }

    /**
     * Handles outside tap, swipe, or Cancel button (Phase 5.3.6):
     * Marks capture as DISMISSED so it is preserved in Needs Review.
     */
    fun onDismiss() {
        val state = _uiState.value
        if (state.isSaved || state.isDismissed) return

        _uiState.update { it.copy(isDismissed = true) }
        val cid = state.captureId ?: return

        viewModelScope.launch(ioDispatcher) {
            try {
                pendingCaptureDaoProvider.invoke().updateState(
                    cid,
                    PendingCaptureEntity.STATE_DISMISSED
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: onDismiss updateState DISMISSED")
            }
        }
    }

    companion object {
        fun provideFactory(
            transactionRepository: TransactionRepository,
            peopleRepository: PeopleRepository,
            categoryRepository: CategoryRepository,
            accountRepository: AccountRepository,
            tripTagRepository: TripTagRepository,
            pendingCaptureDaoProvider: suspend () -> PendingCaptureDao,
            syncQueueDaoProvider: suspend () -> SyncQueueDao,
            calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
            logExpenseUseCase: LogExpenseUseCase = LogExpenseUseCase(transactionRepository, calculateSplitUseCase),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return QuickLogViewModel(
                    transactionRepository = transactionRepository,
                    peopleRepository = peopleRepository,
                    categoryRepository = categoryRepository,
                    accountRepository = accountRepository,
                    tripTagRepository = tripTagRepository,
                    pendingCaptureDaoProvider = pendingCaptureDaoProvider,
                    syncQueueDaoProvider = syncQueueDaoProvider,
                    calculateSplitUseCase = calculateSplitUseCase,
                    logExpenseUseCase = logExpenseUseCase,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}

