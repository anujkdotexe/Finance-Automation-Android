package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.SplitCalculation
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.LogExpenseUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * UI state representing the Expense Bottom Sheet and transaction detail editing.
 */
data class ExpenseUiState(
    val transactionId: String? = null,
    val amountText: String = "",
    val amount: Double = 0.0,
    val merchant: String = "",
    val selectedCategory: String = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
    val splitMode: SplitMode = SplitMode.JUST_ME,
    val allCompanions: List<Companion> = emptyList(),
    val selectedCompanionIds: Set<String> = emptySet(),
    val customShareText: String = "",
    val customShare: Double? = null,
    val myShare: Double = 0.0,
    val owedToMe: Double = 0.0,
    val status: TransactionStatus = TransactionStatus.PERSONAL,
    val tripTag: String = "",
    val account: String = CategoryConstants.ACCOUNT_SBI,
    val availableCategories: List<com.financeautomation.domain.model.Category> = emptyList(),
    val availableAccounts: List<com.financeautomation.domain.model.Account> = emptyList(),
    val isPossibleDuplicate: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isVoided: Boolean = false,
    val errorMessage: String? = null
)

/**
 * ViewModel managing interactive expense categorization, 3-mode split math,
 * companion selection, persistence, and non-destructive voiding.
 */
class ExpenseViewModel(
    private val transactionRepository: TransactionRepository,
    private val peopleRepository: PeopleRepository,
    private val categoryRepository: com.financeautomation.domain.repository.CategoryRepository? = null,
    private val accountRepository: com.financeautomation.domain.repository.AccountRepository? = null,
    private val calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
    private val logExpenseUseCase: LogExpenseUseCase = LogExpenseUseCase(transactionRepository, calculateSplitUseCase),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpenseUiState())
    val uiState: StateFlow<ExpenseUiState> = _uiState.asStateFlow()

    init {
        observeCompanions()
        observeCategories()
        observeAccounts()
    }

    private fun observeCompanions() {
        viewModelScope.launch(ioDispatcher) {
            peopleRepository.getCompanions().collect { companions ->
                _uiState.update { current ->
                    current.copy(allCompanions = companions)
                }
            }
        }
    }

    private fun observeCategories() {
        categoryRepository?.let { repo ->
            viewModelScope.launch(ioDispatcher) {
                repo.getCategories(includeArchived = false).collect { categories ->
                    _uiState.update { current ->
                        current.copy(availableCategories = categories)
                    }
                }
            }
        }
    }

    private fun observeAccounts() {
        accountRepository?.let { repo ->
            viewModelScope.launch(ioDispatcher) {
                repo.getAccounts(includeArchived = false).collect { accounts ->
                    _uiState.update { current ->
                        current.copy(availableAccounts = accounts)
                    }
                }
            }
        }
    }

    /**
     * Loads an existing transaction by ID into the editor state.
     */
    fun loadTransaction(txnId: String) {
        if (txnId.isBlank()) return

        viewModelScope.launch(ioDispatcher) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isSaved = false,
                    isVoided = false,
                    errorMessage = null
                )
            }
            val txn = transactionRepository.getTransactionById(txnId)
            if (txn != null) {
                val companionsList = if (txn.companions.isNotBlank() && txn.companions != "Personal") {
                    txn.companions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }

                val currentCompanions = _uiState.value.allCompanions
                val matchedIds = mutableSetOf<String>()
                for (name in companionsList) {
                    val found = currentCompanions.find { it.name.equals(name, ignoreCase = true) }
                    if (found != null) {
                        matchedIds.add(found.id)
                    } else {
                        val newId = "comp_${UUID.randomUUID().toString().take(8)}"
                        val newCompanion = Companion(id = newId, name = name, isSelected = true)
                        peopleRepository.insertCompanion(newCompanion)
                        matchedIds.add(newId)
                    }
                }

                val determinedMode = when {
                    txn.companions == "Personal" || txn.owedToMe == 0.0 -> SplitMode.JUST_ME
                    txn.owedToMe > 0.0 && companionsList.isNotEmpty() -> {
                        val totalHeads = companionsList.size + 1
                        val expectedShare = Math.round((txn.totalAmount / totalHeads) * 100.0) / 100.0
                        if (Math.abs(txn.myShare - expectedShare) <= 0.02) {
                            SplitMode.SPLIT_EQUALLY
                        } else {
                            SplitMode.CUSTOM
                        }
                    }
                    else -> SplitMode.JUST_ME
                }

                _uiState.update {
                    it.copy(
                        transactionId = txn.id,
                        amountText = formatAmount(txn.totalAmount),
                        amount = txn.totalAmount,
                        merchant = txn.merchant,
                        selectedCategory = txn.category,
                        splitMode = determinedMode,
                        selectedCompanionIds = matchedIds,
                        customShareText = if (determinedMode == SplitMode.CUSTOM) formatAmount(txn.myShare) else "",
                        customShare = if (determinedMode == SplitMode.CUSTOM) txn.myShare else null,
                        myShare = txn.myShare,
                        owedToMe = txn.owedToMe,
                        status = txn.status,
                        tripTag = txn.tripTag,
                        account = txn.account,
                        isPossibleDuplicate = txn.isPossibleDuplicate,
                        isLoading = false,
                        isSaved = false,
                        isVoided = false,
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isSaved = false,
                        isVoided = false,
                        errorMessage = "Transaction not found"
                    )
                }
            }
        }
    }

    /**
     * Handles amount text input changes and recalculates split.
     */
    fun onAmountChanged(newAmountText: String) {
        val sanitized = newAmountText.filter { it.isDigit() || it == '.' }
        val parsedAmount = sanitized.toDoubleOrNull() ?: 0.0

        _uiState.update { current ->
            val updated = current.copy(
                amountText = sanitized,
                amount = parsedAmount
            )
            recalculateSplit(updated)
        }
    }

    /**
     * Handles merchant / payee name changes.
     */
    fun onMerchantChanged(newMerchant: String) {
        _uiState.update { it.copy(merchant = newMerchant) }
    }

    /**
     * Handles payment funding account selection.
     */
    fun onAccountChanged(newAccount: String) {
        _uiState.update { it.copy(account = newAccount.trim()) }
    }

    /**
     * Handles category selection, strictly normalizing to plural "Savings".
     */
    fun onCategorySelected(categoryName: String) {
        val normalized = try {
            if (categoryName.trim().equals("saving", ignoreCase = true) || categoryName.trim().equals("savings", ignoreCase = true)) {
                CategoryConstants.CATEGORY_SAVINGS
            } else {
                CategoryConstants.normalizeCategory(categoryName)
            }
        } catch (_: Exception) {
            categoryName.trim()
        }
        _uiState.update { it.copy(selectedCategory = normalized) }
    }

    /**
     * Handles split mode changes (Just Me, Split Equally, Custom Split).
     */
    fun onSplitModeChanged(mode: SplitMode) {
        _uiState.update { current ->
            val updated = current.copy(splitMode = mode)
            recalculateSplit(updated)
        }
    }

    /**
     * Toggles companion selection and recalculates split.
     */
    fun onCompanionToggled(companionId: String) {
        _uiState.update { current ->
            val updatedSelected = current.selectedCompanionIds.toMutableSet()
            if (updatedSelected.contains(companionId)) {
                updatedSelected.remove(companionId)
            } else {
                updatedSelected.add(companionId)
            }
            val updated = current.copy(selectedCompanionIds = updatedSelected)
            recalculateSplit(updated)
        }
    }

    /**
     * Adds a new companion to the persistent database and marks as selected.
     */
    fun onAddNewCompanion(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch(ioDispatcher) {
            val existing = peopleRepository.getCompanionByName(trimmed)
            val newCompanion = if (existing != null) {
                existing
            } else {
                val newId = "comp_${UUID.randomUUID().toString().take(8)}"
                val created = Companion(id = newId, name = trimmed, isSelected = true)
                peopleRepository.insertCompanion(created)
                created
            }

            _uiState.update { current ->
                val updatedCompanions = if (current.allCompanions.none { it.id == newCompanion.id }) {
                    current.allCompanions + newCompanion
                } else {
                    current.allCompanions
                }
                val updatedSelected = current.selectedCompanionIds + newCompanion.id
                val updated = current.copy(
                    allCompanions = updatedCompanions,
                    selectedCompanionIds = updatedSelected
                )
                recalculateSplit(updated)
            }
        }
    }

    /**
     * Handles custom numeric share input changes.
     */
    fun onCustomShareChanged(newShareText: String) {
        val sanitized = newShareText.filter { it.isDigit() || it == '.' }
        val parsedShare = sanitized.toDoubleOrNull()

        _uiState.update { current ->
            val updated = current.copy(
                customShareText = sanitized,
                customShare = parsedShare
            )
            recalculateSplit(updated)
        }
    }

    /**
     * Recalculates split values based on current state.
     */
    private fun recalculateSplit(state: ExpenseUiState): ExpenseUiState {
        val amount = state.amount
        if (amount <= 0.0) {
            return state.copy(
                myShare = 0.0,
                owedToMe = 0.0,
                status = TransactionStatus.PERSONAL
            )
        }

        val selectedNames = state.allCompanions
            .filter { state.selectedCompanionIds.contains(it.id) }
            .map { it.name }

        val calc: SplitCalculation = when (state.splitMode) {
            SplitMode.JUST_ME -> {
                calculateSplitUseCase.calculateJustMe(amount)
            }
            SplitMode.SPLIT_EQUALLY -> {
                calculateSplitUseCase.calculateSplitEqually(amount, selectedNames)
            }
            SplitMode.CUSTOM -> {
                val share = state.customShare ?: amount
                val boundedShare = share.coerceIn(0.0, amount)
                calculateSplitUseCase.calculateCustomSplit(amount, boundedShare, selectedNames)
            }
        }

        return state.copy(
            myShare = calc.myShare,
            owedToMe = calc.owedToMe,
            status = calc.status
        )
    }

    /**
     * Confirms and persists the expense.
     * If editing an existing transaction, updates in-place with reviewState = CONFIRMED.
     * If manual entry, persists via LogExpenseUseCase.
     */
    fun onConfirmAndSave() {
        val currentState = _uiState.value
        val amount = currentState.amount
        val merchant = currentState.merchant.trim()

        if (amount <= 0.0) {
            _uiState.update { it.copy(errorMessage = "Amount must be greater than zero") }
            return
        }
        if (merchant.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Merchant / Description cannot be blank") }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            val selectedNames = currentState.allCompanions
                .filter { currentState.selectedCompanionIds.contains(it.id) }
                .map { it.name }

            val companionsText = when (currentState.splitMode) {
                SplitMode.JUST_ME -> "Personal"
                SplitMode.SPLIT_EQUALLY, SplitMode.CUSTOM -> {
                    if (selectedNames.isNotEmpty()) selectedNames.joinToString(", ") else "Personal"
                }
            }

            val txnId = currentState.transactionId
            if (txnId != null) {
                val existing = transactionRepository.getTransactionById(txnId)
                if (existing != null) {
                    val updated = existing.copy(
                        merchant = merchant,
                        category = currentState.selectedCategory,
                        totalAmount = currentState.amount,
                        myShare = currentState.myShare,
                        owedToMe = currentState.owedToMe,
                        companions = companionsText,
                        status = currentState.status,
                        account = currentState.account,
                        tripTag = currentState.tripTag,
                        reviewState = ReviewState.CONFIRMED,
                        syncState = SyncState.PENDING,
                        isPossibleDuplicate = false
                    )
                    when (val res = transactionRepository.updateTransaction(updated)) {
                        is Result.Success -> {
                            _uiState.update { it.copy(isSaving = false, isSaved = true) }
                        }
                        is Result.Error -> {
                            _uiState.update { it.copy(isSaving = false, errorMessage = res.message ?: "Failed to save") }
                        }
                        is Result.Loading -> {}
                    }
                } else {
                    _uiState.update { it.copy(isSaving = false, errorMessage = "Existing transaction not found") }
                }
            } else {
                when (val res = logExpenseUseCase(
                    amount = currentState.amount,
                    merchant = merchant,
                    category = currentState.selectedCategory,
                    account = currentState.account,
                    splitMode = currentState.splitMode,
                    companions = selectedNames,
                    customShare = currentState.customShare,
                    tripTag = currentState.tripTag,
                    reviewState = ReviewState.CONFIRMED
                )) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isSaving = false, isSaved = true) }
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isSaving = false, errorMessage = res.message ?: "Failed to log expense") }
                    }
                    is Result.Loading -> {}
                }
            }
        }
    }

    /**
     * Voids the current transaction non-destructively.
     */
    fun onVoid() {
        val txnId = _uiState.value.transactionId ?: return

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            when (val res = transactionRepository.voidTransaction(txnId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isSaving = false, isVoided = true) }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isSaving = false, errorMessage = res.message ?: "Failed to void transaction") }
                }
                is Result.Loading -> {}
            }
        }
    }

    /**
     * Resets dismiss/completion flags.
     */
    fun clearState() {
        _uiState.value = ExpenseUiState()
    }

    private fun formatAmount(amount: Double): String {
        return if (amount % 1.0 == 0.0) {
            String.format("%.0f", amount)
        } else {
            String.format("%.2f", amount)
        }
    }

    companion object {
        fun provideFactory(
            transactionRepository: TransactionRepository,
            peopleRepository: PeopleRepository,
            calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
            logExpenseUseCase: LogExpenseUseCase = LogExpenseUseCase(transactionRepository, calculateSplitUseCase),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = provideFactory(
            transactionRepository = transactionRepository,
            peopleRepository = peopleRepository,
            categoryRepository = null,
            accountRepository = null,
            calculateSplitUseCase = calculateSplitUseCase,
            logExpenseUseCase = logExpenseUseCase,
            ioDispatcher = ioDispatcher
        )

        fun provideFactory(
            transactionRepository: TransactionRepository,
            peopleRepository: PeopleRepository,
            categoryRepository: com.financeautomation.domain.repository.CategoryRepository? = null,
            accountRepository: com.financeautomation.domain.repository.AccountRepository? = null,
            calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
            logExpenseUseCase: LogExpenseUseCase = LogExpenseUseCase(transactionRepository, calculateSplitUseCase),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ExpenseViewModel(
                    transactionRepository = transactionRepository,
                    peopleRepository = peopleRepository,
                    categoryRepository = categoryRepository,
                    accountRepository = accountRepository,
                    calculateSplitUseCase = calculateSplitUseCase,
                    logExpenseUseCase = logExpenseUseCase,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}
