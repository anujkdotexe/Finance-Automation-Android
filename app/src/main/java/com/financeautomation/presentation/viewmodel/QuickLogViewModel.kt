package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.utils.AmountInput
import com.financeautomation.domain.usecase.AmountOutcome
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PrivacyScrubber
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.core.utils.formatAmountInput
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.ExpressionEvaluator
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
import kotlinx.coroutines.withContext
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val subscriptionRepository: com.financeautomation.domain.repository.SubscriptionRepository? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickLogUiState())
    val uiState: StateFlow<QuickLogUiState> = _uiState.asStateFlow()
    private val expressionEvaluator = ExpressionEvaluator()
    private var pastTransactions: List<Transaction> = emptyList()

    init {
        viewModelScope.launch(ioDispatcher) {
            launch {
                transactionRepository.getTransactions().collect { txns ->
                    pastTransactions = txns
                }
            }
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
                        val acc = if (accs.any { it.name.equals(curr.selectedAccount, ignoreCase = true) }) curr.selectedAccount else accs.firstOrNull { it.isDefault }?.name ?: accs.firstOrNull()?.name ?: CategoryConstants.ACCOUNT_OTHER
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
        // A new capture never inherits the previous entry (transaction id, split, flags). The same unfinished capture
        // asked for again after a rotation keeps what the person already typed.
        _uiState.update { current ->
            if (current.captureId == captureId && !current.isSaved && !current.isDismissed && !current.isVoided) current
            else freshStateKeepingLists(current)
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val dao = pendingCaptureDaoProvider.invoke()
                val capture = dao.getById(captureId) ?: return@launch
                val amountVal = capture.amount ?: 0.0
                val amountStr = formatAmountInput(amountVal)
                val isNeedsAmount = capture.flags.contains(PendingCaptureEntity.FLAG_NEEDS_AMOUNT) || amountVal <= 0.0

                val recentSimilar = if (amountVal > 0.0) {
                    try {
                        transactionRepository.findRecentSimilar(amountVal, 120_000L)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: findRecentSimilar")
                        emptyList()
                    }
                } else emptyList()
                val dupTxn = recentSimilar.firstOrNull()
                val isPossibleDup = capture.flags.contains(PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE) || dupTxn != null
                val duplicateOfId = PendingCaptureEntity.flagValue(capture.flags, PendingCaptureEntity.FLAG_DUPLICATE_OF_PREFIX)
                    ?: dupTxn?.id
                val subscriptionId = PendingCaptureEntity.flagValue(capture.flags, PendingCaptureEntity.FLAG_SUBSCRIPTION_PREFIX)

                _uiState.update { current ->
                    val resolvedAccount = capture.inferredAccount?.takeIf { it.isNotBlank() } ?: current.selectedAccount
                    current.copy(
                        captureId = capture.id,
                        captureTimestamp = capture.timestamp,
                        sourcePackage = capture.sourcePackage,
                        kind = capture.kind,
                        amountText = amountStr,
                        amount = amountVal,
                        payee = capture.payee,
                        note = capture.note,
                        selectedAccount = resolvedAccount,
                        isNeedsAmount = isNeedsAmount,
                        isPossibleDuplicate = isPossibleDup,
                        duplicateOfId = duplicateOfId,
                        subscriptionId = subscriptionId,
                        selectedCategory = if (subscriptionId != null) {
                            com.financeautomation.core.constants.CategoryConstants.CATEGORY_SUBSCRIPTIONS
                        } else current.selectedCategory
                    )
                }
                recalculateSplit()
                applyLastUsedAccountAndCategory(capture.payee)
                refreshPredictedCategories(capture.payee)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: loadCapture")
            }
        }
    }

    private fun applyLastUsedAccountAndCategory(payee: String) {
        if (_uiState.value.transactionId != null) return
        val trimmed = payee.trim()
        if (trimmed.isBlank()) return

        val matching = pastTransactions
            .filter { it.merchant.trim().equals(trimmed, ignoreCase = true) }
            .maxByOrNull { it.timestamp }
            ?: return

        _uiState.update { curr ->
            val matchingAcc = curr.availableAccounts.firstOrNull {
                it.name.equals(matching.account, ignoreCase = true)
            }?.name ?: matching.account
            val matchingCat = curr.availableCategories.firstOrNull {
                it.name.equals(matching.category, ignoreCase = true)
            }?.name ?: matching.category

            curr.copy(
                selectedAccount = matchingAcc,
                selectedCategory = matchingCat
            )
        }
    }

    private fun refreshPredictedCategories(payee: String) {
        if (_uiState.value.transactionId != null) return
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

                val hasExactMatch = pastTransactions.any {
                    it.merchant.trim().equals(payee.trim(), ignoreCase = true)
                }
                val selectedCat = if (hasExactMatch) current.selectedCategory else ranked.second
                _uiState.update { it.copy(availableCategories = ranked.first, selectedCategory = selectedCat) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: refreshPredictedCategories")
            }
        }
    }

    /** A blank entry that keeps the lists loaded from the repositories, so nothing of the last entry is reused. */
    private fun freshStateKeepingLists(current: QuickLogUiState): QuickLogUiState = QuickLogUiState(
        selectedAccount = current.selectedAccount,
        availableAccounts = current.availableAccounts,
        availableCategories = current.availableCategories,
        availableCompanions = current.availableCompanions,
        activeTripTag = current.activeTripTag,
        isSaving = current.isSaving
    )

    /**
     * Initializes manual entry mode without a pending capture.
     */
    fun setManualMode() {
        // The sheet is recreated on rotation, dark mode or font scale changes and asks for manual mode again:
        // unfinished manual input must survive that. A saved or dismissed entry starts clean.
        val current = _uiState.value
        val unfinished = current.captureId == null && current.sourcePackage == "manual" &&
            !current.isSaved && !current.isDismissed &&
            (current.amountText.isNotEmpty() || current.payee.isNotEmpty())
        if (unfinished) return
        _uiState.update { freshStateKeepingLists(it) }
        recalculateSplit()
        refreshPredictedCategories("")
    }

    fun onAmountChanged(raw: String) {
        val text = AmountInput.clean(raw)
        // An unreadable or zero amount is held as 0.0 only for the totals; Save stays disabled while amount is 0.0
        val parsed = (expressionEvaluator.evaluateDetailed(text) as? AmountOutcome.Value)?.amount ?: 0.0
        _uiState.update { it.copy(amountText = text, amount = parsed) }
        recalculateSplit()
    }

    fun onPayeeChanged(text: String) {
        val clean = com.financeautomation.core.utils.InputPolicy.cleanNameWhileTyping(text)
            .take(com.financeautomation.core.utils.InputPolicy.MAX_MERCHANT_NAME_LENGTH)
        _uiState.update { it.copy(payee = clean) }
        applyLastUsedAccountAndCategory(clean.trim())
        refreshPredictedCategories(clean.trim())
    }

    fun onNoteChanged(text: String) {
        val clean = com.financeautomation.core.utils.InputPolicy.cleanNoteWhileTyping(text)
            .take(com.financeautomation.core.utils.InputPolicy.MAX_NOTE_LENGTH)
        _uiState.update { it.copy(note = clean) }
    }

    fun onDateSelected(timestampMillis: Long) {
        val capped = minOf(timestampMillis, System.currentTimeMillis())
        _uiState.update { it.copy(captureTimestamp = capped) }
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
                val existing = _uiState.value.availableCompanions.map { it.name }
                val validation = com.financeautomation.core.utils.InputPolicy.validateName(name, existing, rejectGroupSeparators = true)
                if (!validation.isValid) return@launch
                val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue
                val newCompanion = Companion(
                    id = java.util.UUID.randomUUID().toString(),
                    name = cleanName
                )
                peopleRepository.insertCompanion(newCompanion)
                _uiState.update { current ->
                    val updatedCompanions = if (current.availableCompanions.any { it.id == newCompanion.id }) {
                        current.availableCompanions
                    } else {
                        current.availableCompanions + newCompanion
                    }
                    current.copy(
                        availableCompanions = updatedCompanions,
                        selectedCompanionIds = current.selectedCompanionIds + newCompanion.id
                    )
                }
                recalculateSplit()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: onAddNewCompanion")
            }
        }
    }

    fun onCustomShareChanged(rawShare: String) {
        val shareText = AmountInput.clean(rawShare)
        val parsed = expressionEvaluator.evaluate(shareText)
        _uiState.update { it.copy(customShareText = shareText, customShare = parsed) }
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
    fun onConfirmAndSave(
        onSaved: (message: UiText, transactionId: String) -> Unit,
        onError: ((UiText) -> Unit)?
    ) {
        // Compare and set in one step: a second tap in the same frame sees isSaving already true and returns
        var wasSaving = false
        _uiState.update { current ->
            wasSaving = current.isSaving
            if (wasSaving) current else current.copy(isSaving = true)
        }
        if (wasSaving) return
        val trimmedPayee = com.financeautomation.core.utils.InputPolicy.cleanName(_uiState.value.payee)
        val trimmedNote = com.financeautomation.core.utils.InputPolicy.cleanNote(_uiState.value.note)
        _uiState.update { it.copy(payee = trimmedPayee, note = trimmedNote) }
        // isSaving is true now only because of the guard above; the checks below judge the entry itself
        val state = _uiState.value.copy(isSaving = false)
        if (state.amount <= 0.0) {
            val err = UiText.res(R.string.quicklog_err_amount_zero)
            _uiState.update { it.copy(errorMessage = err, isSaving = false) }
            onError?.invoke(err)
            return
        }
        if (state.kind.equals(com.financeautomation.data.local.entity.PendingCaptureEntity.KIND_CREDIT, ignoreCase = true)) {
            val err = UiText.dynamic("Credit transactions cannot be saved as debit expenses.")
            _uiState.update { it.copy(errorMessage = err, isSaving = false) }
            onError?.invoke(err)
            return
        }
        if (!state.isSaveEnabled) {
            val err = UiText.dynamic("At least one companion must be specified when an amount is owed to me")
            _uiState.update { it.copy(errorMessage = err, isSaving = false) }
            onError?.invoke(err)
            return
        }
        val captureTs = state.captureTimestamp
        if (captureTs != null && com.financeautomation.core.utils.isFuture(captureTs)) {
            val err = UiText.res(R.string.quicklog_err_future_date)
            _uiState.update { it.copy(errorMessage = err, isSaving = false) }
            onError?.invoke(err)
            return
        }

        val txnId = state.transactionId
        if (txnId != null) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            viewModelScope.launch(ioDispatcher) {
                try {
                    when (val res = QuickLogTransactionHelper.updateExisting(txnId, state, transactionRepository)) {
                        is Result.Success -> {
                            val msg = UiText.res(R.string.quicklog_msg_updated)
                            _uiState.update { it.copy(isSaving = false, isSaved = true, resultMessage = msg) }
                            withContext(mainDispatcher) {
                                onSaved(msg, txnId)
                            }
                        }
                        is Result.Error -> {
                            Diagnostics.record("QuickLogViewModel", "update_error: ${SyncErrorKind.safeCode("quicklog update", res.throwable)} ${PrivacyScrubber.scrub(res.message.orEmpty())}")
                            val errMsg = EditBlockText.of(res.throwable)
                                ?: UiText.dynamic(SyncErrorKind.formatUserMessage("quicklog update", res.throwable))
                            _uiState.update { it.copy(isSaving = false, errorMessage = errMsg) }
                            withContext(mainDispatcher) { onError?.invoke(errMsg) }
                        }
                        is Result.Loading -> Unit
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("QuickLogViewModel", "update_exception: ${SyncErrorKind.safeCode("quicklog update", e)} ${PrivacyScrubber.scrub(e.message.orEmpty())}")
                    val errMsg = UiText.dynamic(SyncErrorKind.formatUserMessage("quicklog update", e))
                    _uiState.update { it.copy(isSaving = false, errorMessage = errMsg) }
                    withContext(mainDispatcher) { onError?.invoke(errMsg) }
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
                    predictCategoryUseCase = predictCategoryUseCase,
                    subscriptionRepository = subscriptionRepository
                )) {
                    is Result.Success -> {
                        val loggedTxn = result.data
                        val formattedAmount = String.format(java.util.Locale.US, "%.2f", state.amount)
                        val message = UiText.res(R.string.quicklog_msg_saved_will_sync, formattedAmount)
                        _uiState.update { it.copy(isSaving = false, isSaved = true, transactionId = loggedTxn.id, resultMessage = message) }
                        withContext(mainDispatcher) {
                            onSaved(message, loggedTxn.id)
                        }
                    }
                    is Result.Error -> {
                        Diagnostics.record("QuickLogViewModel", "save_error: ${SyncErrorKind.safeCode("quicklog save", result.throwable)} ${PrivacyScrubber.scrub(result.message.orEmpty())}")
                        val errMsg = UiText.dynamic(SyncErrorKind.formatUserMessage("quicklog save", result.throwable))
                        _uiState.update { it.copy(isSaving = false, errorMessage = errMsg) }
                        withContext(mainDispatcher) { onError?.invoke(errMsg) }
                    }
                    is Result.Loading -> Unit
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "save_exception: ${SyncErrorKind.safeCode("quicklog save", e)} ${PrivacyScrubber.scrub(e.message.orEmpty())}")
                val errMsg = UiText.dynamic(SyncErrorKind.formatUserMessage("quicklog save", e))
                _uiState.update { it.copy(isSaving = false, errorMessage = errMsg) }
                withContext(mainDispatcher) { onError?.invoke(errMsg) }
            }
        }
    }

    fun onConfirmAndSave(onSaved: (message: UiText, transactionId: String) -> Unit) {
        onConfirmAndSave(onSaved, null)
    }

    fun onConfirmAndSave(onSaved: (UiText) -> Unit) {
        onConfirmAndSave { message: UiText, _: String -> onSaved(message) }
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
                    _uiState.update { curr ->
                        updatedState.copy(
                            availableAccounts = curr.availableAccounts.ifEmpty { updatedState.availableAccounts },
                            availableCategories = curr.availableCategories.ifEmpty { updatedState.availableCategories },
                            availableCompanions = curr.availableCompanions.ifEmpty { updatedState.availableCompanions }
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "${e.javaClass.simpleName}: loadTransaction")
            }
        }
    }

    /**
     * Voids the active transaction non-destructively or clears the row from the sheet.
     */
    fun onVoid(deleteSheetRow: Boolean = false, onVoided: () -> Unit = {}) {
        val txnId = _uiState.value.transactionId ?: return
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                when (val res = transactionRepository.voidTransaction(txnId, deleteSheetRow)) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isSaving = false, isVoided = true) }
                        onVoided()
                    }
                    is Result.Error -> {
                        val throwable = res.throwable
                        if (throwable is com.financeautomation.domain.model.VoidBlockedException) {
                            val msgRes = com.financeautomation.presentation.components.VoidDialogUtils.blockMessageResId(throwable.reason)
                            _uiState.update { it.copy(isSaving = false, errorMessage = UiText.StringResource(msgRes)) }
                        } else {
                            Diagnostics.record("QuickLogViewModel", "void_error: ${SyncErrorKind.safeCode("quicklog void", res.throwable)} ${PrivacyScrubber.scrub(res.message.orEmpty())}")
                            _uiState.update { it.copy(isSaving = false, errorMessage = UiText.dynamic(SyncErrorKind.formatUserMessage("quicklog void", res.throwable))) }
                        }
                    }
                    is Result.Loading -> Unit
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("QuickLogViewModel", "void_exception: ${SyncErrorKind.safeCode("quicklog void", e)} ${PrivacyScrubber.scrub(e.message.orEmpty())}")
                _uiState.update { it.copy(isSaving = false, errorMessage = UiText.dynamic(SyncErrorKind.formatUserMessage("quicklog void", e))) }
            }
        }
    }

    /**
     * Resets state for a clean prompt cycle.
     */
    fun clearState() {
        _uiState.update { curr ->
            QuickLogUiState(
                availableAccounts = curr.availableAccounts,
                availableCategories = curr.availableCategories,
                availableCompanions = curr.availableCompanions,
                selectedAccount = curr.selectedAccount
            )
        }
    }

    /**
     * Handles outside tap, swipe, or Cancel button (Phase 5.3.6):
     * Marks capture as DISMISSED so it is preserved in Needs Review.
     */
    fun onDismiss() {
        val state = _uiState.value
        // A save in progress cannot be dismissed: the entry is being written and the capture is claimed
        if (state.isSaving || state.isSaved || state.isDismissed) return

        _uiState.update { it.copy(isDismissed = true) }
        val cid = state.captureId ?: return

        viewModelScope.launch(ioDispatcher) {
            QuickLogTransactionHelper.dismissCapture(cid, pendingCaptureDaoProvider)
        }
    }
}

