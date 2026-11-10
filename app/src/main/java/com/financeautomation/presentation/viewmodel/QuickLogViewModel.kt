package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.formatAmountInput
import com.financeautomation.data.local.dao.CategoryStatDao
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
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
    private val categoryStatDaoProvider: (suspend () -> CategoryStatDao)? = null,
    private val predictCategoryUseCase: PredictCategoryUseCase = PredictCategoryUseCase(),
    private val calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
    private val logExpenseUseCase: LogExpenseUseCase = LogExpenseUseCase(transactionRepository, calculateSplitUseCase),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickLogUiState())
    val uiState: StateFlow<QuickLogUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            launch { peopleRepository.getCompanions().collect { c -> _uiState.update { it.copy(availableCompanions = c) } } }
            launch {
                categoryRepository.getCategories().collect { cats ->
                    _uiState.update { it.copy(availableCategories = cats) }
                    refreshPredictedCategories(_uiState.value.payee)
                }
            }
            launch {
                accountRepository.getAccounts().collect { accs ->
                    _uiState.update { curr ->
                        val acc = if (accs.any { it.name.equals(curr.selectedAccount, ignoreCase = true) }) curr.selectedAccount else accs.firstOrNull()?.name ?: CategoryConstants.ACCOUNT_SBI
                        curr.copy(availableAccounts = accs, selectedAccount = acc)
                    }
                }
            }
            launch { tripTagRepository.getActiveTag().collect { t -> _uiState.update { it.copy(activeTripTag = t) } } }
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
                val amountStr = formatAmountInput(amountVal)
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
                refreshPredictedCategories(capture.payee)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: loadCapture")
            }
        }
    }

    private fun refreshPredictedCategories(payee: String) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val current = _uiState.value
                val ranked = QuickLogCategoryRanker.rankCategories(
                    payee = payee,
                    categories = current.availableCategories,
                    currentSelected = current.selectedCategory,
                    statDaoProvider = categoryStatDaoProvider,
                    predictCategoryUseCase = predictCategoryUseCase
                ) ?: return@launch
                _uiState.update { it.copy(availableCategories = ranked.first, selectedCategory = ranked.second) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: refreshPredictedCategories")
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
        refreshPredictedCategories("")
    }

    /**
     * Initializes self-test mode with sample expense data and no pending capture (Spec 7.4).
     * Does not reference any whitelisted package or write to persistent storage.
     */
    fun setSelfTestMode(samplePayee: String? = null) {
        _uiState.update { SelfTestSampleData.createSelfTestState(it, samplePayee) }
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
        refreshPredictedCategories(text)
    }

    fun onAccountSelected(accountName: String) = _uiState.update { it.copy(selectedAccount = accountName) }

    fun onCategorySelected(categoryName: String) = _uiState.update { it.copy(selectedCategory = categoryName) }

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
            SplitMode.CUSTOM -> calculateSplitUseCase.calculateCustomSplit(total, state.customShare ?: total, selectedCompanions)
        }
        _uiState.update { it.copy(myShare = calculation.myShare, owedToMe = calculation.owedToMe) }
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

        val txnId = state.transactionId
        if (txnId != null) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            viewModelScope.launch(ioDispatcher) {
                try {
                    when (val res = QuickLogTransactionHelper.updateExisting(txnId, state, transactionRepository)) {
                        is Result.Success -> {
                            _uiState.update { it.copy(isSaving = false, isSaved = true, resultMessage = "Updated") }
                            onSaved("Updated")
                        }
                        is Result.Error -> {
                            _uiState.update { it.copy(isSaving = false, errorMessage = res.message) }
                        }
                        is Result.Loading -> Unit
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: updateTransaction")
                    _uiState.update { it.copy(isSaving = false, errorMessage = "Update failed: ${e.message}") }
                }
            }
            return
        }

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                when (val result = QuickLogTransactionHelper.logNewExpense(
                    state = state,
                    logExpenseUseCase = logExpenseUseCase,
                    pendingCaptureDaoProvider = pendingCaptureDaoProvider,
                    categoryStatDaoProvider = categoryStatDaoProvider,
                    predictCategoryUseCase = predictCategoryUseCase
                )) {
                    is Result.Success -> {
                        val message = "Saved, will sync"
                        _uiState.update { it.copy(isSaving = false, isSaved = true, resultMessage = message) }
                        onSaved(message)
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isSaving = false, errorMessage = result.message) }
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
     * Loads an existing transaction for review and correction (Design Guide 5.3 & 5.5).
     */
    fun loadTransaction(txnId: String) {
        if (txnId.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val updatedState = QuickLogTransactionHelper.loadExistingTransaction(
                    txnId = txnId,
                    currentState = _uiState.value,
                    transactionRepository = transactionRepository,
                    peopleRepository = peopleRepository
                )
                if (updatedState != null) {
                    _uiState.value = updatedState
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: loadTransaction")
            }
        }
    }

    /**
     * Voids the active transaction non-destructively (Design Guide 5.3).
     */
    fun onVoid(onVoided: () -> Unit = {}) {
        val txnId = _uiState.value.transactionId ?: return
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                when (val res = transactionRepository.voidTransaction(txnId)) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isSaving = false, isVoided = true) }
                        onVoided()
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isSaving = false, errorMessage = res.message ?: "Failed to void") }
                    }
                    is Result.Loading -> Unit
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: onVoid")
                _uiState.update { it.copy(isSaving = false, errorMessage = "Void failed: ${e.message}") }
            }
        }
    }

    /**
     * Resets state for a clean prompt cycle.
     */
    fun clearState() {
        _uiState.value = QuickLogUiState()
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
            QuickLogTransactionHelper.dismissCapture(cid, pendingCaptureDaoProvider)
        }
    }
}

