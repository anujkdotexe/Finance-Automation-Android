package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.InflowRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.utils.AccountingUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Unified item interface for the transactions ledger (both expenses and inflows).
 */
sealed interface TransactionListItem {
    val id: String
    val timestamp: Long
    val amount: Double
    val account: String
    val isVoid: Boolean

    data class ExpenseItem(val transaction: Transaction) : TransactionListItem {
        override val id: String get() = transaction.id
        override val timestamp: Long get() = transaction.timestamp
        override val amount: Double get() = transaction.totalAmount
        override val account: String get() = transaction.account
        override val isVoid: Boolean get() = transaction.status == TransactionStatus.VOID
    }

    data class IncomeItem(val inflow: Inflow) : TransactionListItem {
        override val id: String get() = inflow.id
        override val timestamp: Long get() = inflow.timestamp
        override val amount: Double get() = inflow.amount
        override val account: String get() = inflow.account
        override val isVoid: Boolean get() = inflow.status.equals("VOID", ignoreCase = true)
    }
}

/**
 * Group of transactions belonging to a single calendar day.
 */
data class DayTransactionGroup(
    val dateLabel: String,
    val dayTimestamp: Long,
    val dayTotalOut: Double,
    val dayMyShare: Double,
    val items: List<TransactionListItem> = emptyList(),
    val transactions: List<Transaction> = items.filterIsInstance<TransactionListItem.ExpenseItem>().map { it.transaction }
) {
    constructor(
        dateLabel: String,
        dayTimestamp: Long,
        dayTotalOut: Double,
        dayMyShare: Double,
        transactions: List<Transaction>
    ) : this(
        dateLabel = dateLabel,
        dayTimestamp = dayTimestamp,
        dayTotalOut = dayTotalOut,
        dayMyShare = dayMyShare,
        items = transactions.map { TransactionListItem.ExpenseItem(it) },
        transactions = transactions
    )
}

/**
 * Where a search looks: the selected month only, or every month.
 */
enum class SearchScope { THIS_MONTH, ALL_MONTHS }

/**
 * Sync and review state of a single transaction row. Computed in one place
 * ([TransactionsViewModel.computeRowStatus]) with priority
 * Failed, Needs review, Waiting to sync, Synced.
 * The settlement status (Pending, Settled) is a separate field on the transaction.
 */
enum class RowStatus { FAILED, NEEDS_REVIEW, WAITING_TO_SYNC, SYNCED }

/**
 * Transaction ids that currently have a failed or a pending sync queue item.
 */
data class QueueTransactionIds(
    val failed: Set<String> = emptySet(),
    val pending: Set<String> = emptySet()
)

/**
 * Active filter dimensions for the Transactions screen.
 */
data class TransactionFilter(
    val category: String? = null,
    val account: String? = null,
    val person: String? = null,
    val tripTag: String? = null,
    val status: String? = null,
    val excludeSavingsAndIgnored: Boolean = false,
    val type: String? = null,
    val showVoid: Boolean = false
) {
    val isActive: Boolean
        get() = category != null || account != null || person != null || tripTag != null || status != null || excludeSavingsAndIgnored || type != null || showVoid
}

/**
 * UI State powering the Transactions screen.
 */
data class TransactionsUiState(
    val selectedMonthLabel: String = "",
    val totalOut: Double = 0.0,
    val myShare: Double = 0.0,
    val searchQuery: String = "",
    val searchScope: SearchScope = SearchScope.THIS_MONTH,
    val activeFilter: TransactionFilter = TransactionFilter(),
    val groupedTransactions: List<DayTransactionGroup> = emptyList(),
    val rowStatuses: Map<String, RowStatus> = emptyMap(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    /** Budget progress of the filtered category for the selected month, or null when no category or no budget. */
    val categoryBudget: com.financeautomation.domain.usecase.BudgetProgress? = null
)

/**
 * ViewModel managing full transaction ledger viewing, month navigation,
 * multi-attribute filtering, search, and daily grouped calculations.
 */
class TransactionsViewModel(
    private val transactionRepository: TransactionRepository,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inflowRepository: InflowRepository? = null,
    private val budgetRepository: com.financeautomation.domain.repository.BudgetRepository? = null,
    private val categoryRepository: com.financeautomation.domain.repository.CategoryRepository? = null
) : ViewModel() {

    private val monthOffsetFlow = MutableStateFlow(0)
    private val searchQueryFlow = MutableStateFlow("")
    private val searchScopeFlow = MutableStateFlow(SearchScope.THIS_MONTH)
    private val isSyncingFlow = MutableStateFlow(false)
    private val filterFlow = MutableStateFlow(TransactionFilter())

    private val _uiState = MutableStateFlow(TransactionsUiState(isLoading = true))
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    init {
        observeTransactions()
    }

    private fun queueIdsFlow() = flow {
        val dao = syncQueueDaoProvider?.invoke()
        if (dao == null) {
            emit(QueueTransactionIds())
        } else {
            emitAll(
                combine(dao.getFailedQueueFlow(), dao.getPendingQueueFlow()) { failed, pending ->
                    QueueTransactionIds(
                        failed = failed.map { it.transactionId }.toSet(),
                        pending = pending.map { it.transactionId }.toSet()
                    )
                }
            )
        }
    }.catch { emit(QueueTransactionIds()) }

    private fun observeTransactions() {
        val inflowsFlow = inflowRepository?.getAllInflows() ?: flowOf(emptyList())
        val budgetsFlow = budgetRepository?.observeBudgets() ?: flowOf(emptyList())
        val categoriesFlow = categoryRepository?.getCategories() ?: flowOf(emptyList())
        viewModelScope.launch(ioDispatcher) {
            combine(
                transactionRepository.getTransactions(),
                inflowsFlow,
                monthOffsetFlow,
                searchQueryFlow,
                filterFlow,
                queueIdsFlow(),
                searchScopeFlow,
                isSyncingFlow,
                budgetsFlow,
                categoriesFlow
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val state = calculateState(
                    transactions = values[0] as List<Transaction>,
                    inflows = values[1] as List<Inflow>,
                    monthOffset = values[2] as Int,
                    query = values[3] as String,
                    filter = values[4] as TransactionFilter,
                    queueIds = values[5] as QueueTransactionIds,
                    scope = values[6] as SearchScope,
                    isSyncing = values[7] as Boolean
                )
                @Suppress("UNCHECKED_CAST")
                state.copy(
                    categoryBudget = categoryBudgetFor(
                        category = (values[4] as TransactionFilter).category,
                        monthOffset = values[2] as Int,
                        budgets = values[8] as List<com.financeautomation.domain.model.Budget>,
                        transactions = values[0] as List<Transaction>,
                        categories = values[9] as List<com.financeautomation.domain.model.Category>
                    )
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    private fun categoryBudgetFor(
        category: String?,
        monthOffset: Int,
        budgets: List<com.financeautomation.domain.model.Budget>,
        transactions: List<Transaction>,
        categories: List<com.financeautomation.domain.model.Category>
    ): com.financeautomation.domain.usecase.BudgetProgress? {
        if (category.isNullOrBlank() || budgets.isEmpty()) return null
        val month = java.time.YearMonth.now(com.financeautomation.domain.usecase.LivingBurn.KOLKATA).plusMonths(monthOffset.toLong())
        return com.financeautomation.domain.usecase.BudgetProgressUseCase()
            .forCategory(category, month.year, month.monthValue, budgets, transactions, categories)
    }

    fun nextMonth() {
        monthOffsetFlow.update { it + 1 }
    }

    fun previousMonth() {
        monthOffsetFlow.update { it - 1 }
    }

    fun currentMonth() {
        monthOffsetFlow.value = 0
    }

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun setSearchScope(scope: SearchScope) {
        searchScopeFlow.value = scope
    }

    fun setFilter(filter: TransactionFilter) {
        filterFlow.value = filter
    }

    fun setFilter(
        category: String? = null,
        account: String? = null,
        person: String? = null,
        tripTag: String? = null,
        status: String? = null
    ) {
        filterFlow.value = TransactionFilter(
            category = category,
            account = account,
            person = person,
            tripTag = tripTag,
            status = status
        )
    }

    fun setMonthOffset(offset: Int) {
        monthOffsetFlow.value = offset
    }

    fun setCategoryFilter(category: String?) {
        if (category.isNullOrBlank()) {
            filterFlow.update { it.copy(category = null) }
            return
        }
        val trimmed = category.trim()
        val match = CategoryConstants.DEFAULT_CATEGORIES
            .firstOrNull { it.equals(trimmed, ignoreCase = true) }
        if (match != null) {
            filterFlow.update { it.copy(category = match, excludeSavingsAndIgnored = false, status = null) }
        }
        // Unknown category is ignored
    }

    fun setTypeFilter(type: String?) {
        if (type.isNullOrBlank()) {
            filterFlow.update { it.copy(status = null, type = null, excludeSavingsAndIgnored = false) }
            return
        }
        when (type.trim().lowercase()) {
            "all" -> {
                filterFlow.value = TransactionFilter()
            }
            "living_burn", "living burn" -> {
                filterFlow.update { it.copy(excludeSavingsAndIgnored = true, category = null, status = null) }
            }
            "savings" -> {
                setCategoryFilter(CategoryConstants.CATEGORY_SAVINGS)
            }
            "income" -> {
                filterFlow.update { it.copy(status = "income", category = null, excludeSavingsAndIgnored = false) }
            }
            "needs review", "needs_review", "review" -> {
                filterFlow.update { it.copy(status = "review", category = null, excludeSavingsAndIgnored = false) }
            }
            "upi" -> {
                filterFlow.update { it.copy(account = "UPI", category = null, excludeSavingsAndIgnored = false) }
            }
            "card" -> {
                filterFlow.update { it.copy(account = "Card", category = null, excludeSavingsAndIgnored = false) }
            }
            "cash" -> {
                filterFlow.update { it.copy(account = "Cash", category = null, excludeSavingsAndIgnored = false) }
            }
        }
    }

    fun clearFilter() {
        filterFlow.value = TransactionFilter()
    }

    fun clearSearch() {
        searchQueryFlow.value = ""
    }

    private fun calculateState(
        transactions: List<Transaction>,
        inflows: List<Inflow>,
        monthOffset: Int,
        query: String,
        filter: TransactionFilter,
        queueIds: QueueTransactionIds,
        scope: SearchScope = SearchScope.THIS_MONTH,
        isSyncing: Boolean = false
    ): TransactionsUiState {
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(kolkataTz).apply {
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfMonth = cal.timeInMillis

        val monthLabelFormat = SimpleDateFormat("MMMM yyyy", Locale.US).apply {
            timeZone = kolkataTz
        }
        val selectedMonthLabel = monthLabelFormat.format(cal.time)

        cal.add(Calendar.MONTH, 1)
        val endOfMonth = cal.timeInMillis

        val searchAllMonths = scope == SearchScope.ALL_MONTHS && query.isNotBlank()
        val monthTransactions = if (searchAllMonths) {
            transactions
        } else {
            transactions.filter { it.timestamp in startOfMonth until endOfMonth }
        }

        val monthInflows = if (searchAllMonths) {
            inflows
        } else {
            inflows.filter { it.timestamp in startOfMonth until endOfMonth }
        }

        val isOnlyVoid = filter.status.equals("void", ignoreCase = true)
        val showVoid = filter.showVoid || isOnlyVoid

        val filteredTransactions = monthTransactions.filter { txn ->
            val isVoid = txn.status == TransactionStatus.VOID
            if (!showVoid && isVoid) {
                return@filter false
            }
            if (isOnlyVoid && !isVoid) {
                return@filter false
            }

            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                val matchMerchant = txn.merchant.lowercase().contains(q)
                val matchCompanions = txn.companions.lowercase().contains(q)
                val matchCategory = txn.category.lowercase().contains(q)
                if (!matchMerchant && !matchCompanions && !matchCategory) {
                    return@filter false
                }
            }

            if (!filter.category.isNullOrBlank()) {
                if (!txn.category.equals(filter.category.trim(), ignoreCase = true)) {
                    return@filter false
                }
            }

            if (!filter.account.isNullOrBlank()) {
                val accFilter = filter.account.trim().lowercase()
                val txnAcc = txn.account.trim().lowercase()
                val matches = when (accFilter) {
                    "card" -> txnAcc.contains("card") || txnAcc.contains("hdfc") || txnAcc.contains("icici") || txnAcc.contains("sbi") || txnAcc.contains("axis")
                    "upi" -> txnAcc.contains("upi") || txnAcc.contains("gpay") || txnAcc.contains("phonepe") || txnAcc.contains("paytm") || txnAcc.contains("supermoney")
                    "cash" -> txnAcc.contains("cash")
                    else -> txnAcc.equals(accFilter, ignoreCase = true)
                }
                if (!matches) {
                    return@filter false
                }
            }

            if (!filter.person.isNullOrBlank()) {
                val tokens = AccountingUtils.tokens(txn.companions)
                val matchesPerson = tokens.any { it.equals(filter.person.trim(), ignoreCase = true) }
                if (!matchesPerson) {
                    return@filter false
                }
            }

            if (!filter.tripTag.isNullOrBlank()) {
                if (!txn.tripTag.equals(filter.tripTag.trim(), ignoreCase = true)) {
                    return@filter false
                }
            }

            if (filter.excludeSavingsAndIgnored) {
                val isSav = txn.category.equals(CategoryConstants.CATEGORY_SAVINGS, ignoreCase = true)
                val isTransfer = txn.category.equals("transfer", ignoreCase = true)
                if (isSav || isTransfer) {
                    return@filter false
                }
            }

            if (!filter.status.isNullOrBlank() && !isOnlyVoid) {
                val st = filter.status.trim().lowercase()
                val rowStatus = computeRowStatus(txn, queueIds)
                val matchesStatus = when (st) {
                    "pending", "waiting to sync", "waiting_to_sync" -> rowStatus == RowStatus.WAITING_TO_SYNC
                    "synced" -> rowStatus == RowStatus.SYNCED
                    "failed" -> rowStatus == RowStatus.FAILED
                    "needs review", "needs_review", "review" -> rowStatus == RowStatus.NEEDS_REVIEW
                    "income" -> false
                    else -> true
                }
                if (!matchesStatus) {
                    return@filter false
                }
            }

            true
        }

        val filteredInflows = monthInflows.filter { inf ->
            val isVoid = inf.status.equals("VOID", ignoreCase = true)
            if (!showVoid && isVoid) {
                return@filter false
            }
            if (isOnlyVoid && !isVoid) {
                return@filter false
            }

            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                val matchSource = inf.source.lowercase().contains(q)
                val matchType = inf.inflowType.lowercase().contains(q)
                val matchNote = inf.note.lowercase().contains(q)
                val matchAccount = inf.account.lowercase().contains(q)
                if (!matchSource && !matchType && !matchNote && !matchAccount) {
                    return@filter false
                }
            }

            // Inflows don't have categories or trip tags; if those filters are set, exclude
            if (!filter.category.isNullOrBlank() || !filter.tripTag.isNullOrBlank() || filter.excludeSavingsAndIgnored) {
                return@filter false
            }

            if (!filter.status.isNullOrBlank() && !isOnlyVoid) {
                val st = filter.status.trim().lowercase()
                if (st != "income") {
                    return@filter false
                }
            }

            if (!filter.account.isNullOrBlank()) {
                val accFilter = filter.account.trim().lowercase()
                val infAcc = inf.account.trim().lowercase()
                val matches = when (accFilter) {
                    "card" -> infAcc.contains("card") || infAcc.contains("hdfc") || infAcc.contains("icici") || infAcc.contains("sbi") || infAcc.contains("axis")
                    "upi" -> infAcc.contains("upi") || infAcc.contains("gpay") || infAcc.contains("phonepe") || infAcc.contains("paytm") || infAcc.contains("supermoney")
                    "cash" -> infAcc.contains("cash")
                    else -> infAcc.equals(accFilter, ignoreCase = true)
                }
                if (!matches) {
                    return@filter false
                }
            }

            if (!filter.person.isNullOrBlank()) {
                if (!inf.source.equals(filter.person.trim(), ignoreCase = true)) {
                    return@filter false
                }
            }

            true
        }

        // Header totals cover the whole month normally, and exactly the results for an all-months search
        // or when a category/burn/income filter is active (G5)
        val hasCategoryOrBurnFilter = !filter.category.isNullOrBlank() || filter.excludeSavingsAndIgnored || filter.status.equals("income", ignoreCase = true)
        val totalsSource = if (searchAllMonths || hasCategoryOrBurnFilter) filteredTransactions else monthTransactions.filter { it.status != TransactionStatus.VOID }
        val totalOut = totalsSource.sumOf { it.totalAmount }
        val myShare = totalsSource.sumOf { it.myShare }

        val dayFormat = SimpleDateFormat("dd MMMM yyyy", Locale.US).apply {
            timeZone = kolkataTz
        }
        val dayCal = Calendar.getInstance(kolkataTz)

        fun getStartOfDay(timestamp: Long): Long {
            dayCal.timeInMillis = timestamp
            dayCal.set(Calendar.HOUR_OF_DAY, 0)
            dayCal.set(Calendar.MINUTE, 0)
            dayCal.set(Calendar.SECOND, 0)
            dayCal.set(Calendar.MILLISECOND, 0)
            return dayCal.timeInMillis
        }

        val allItems: List<TransactionListItem> =
            filteredTransactions.map { TransactionListItem.ExpenseItem(it) } +
            filteredInflows.map { TransactionListItem.IncomeItem(it) }

        val grouped = allItems
            .sortedByDescending { it.timestamp }
            .groupBy { getStartOfDay(it.timestamp) }
            .map { (dayStart, dayItems) ->
                val dateLabel = dayFormat.format(dayStart)
                val dayTxns = dayItems.filterIsInstance<TransactionListItem.ExpenseItem>().map { it.transaction }
                val dayTotalOut = dayTxns.sumOf { it.totalAmount }
                val dayMyShare = dayTxns.sumOf { it.myShare }
                DayTransactionGroup(
                    dateLabel = dateLabel,
                    dayTimestamp = dayStart,
                    dayTotalOut = dayTotalOut,
                    dayMyShare = dayMyShare,
                    items = dayItems,
                    transactions = dayTxns
                )
            }

        return TransactionsUiState(
            selectedMonthLabel = selectedMonthLabel,
            totalOut = totalOut,
            myShare = myShare,
            searchQuery = query,
            searchScope = scope,
            activeFilter = filter,
            groupedTransactions = grouped,
            rowStatuses = filteredTransactions.associate { it.id to computeRowStatus(it, queueIds) },
            isLoading = false,
            isSyncing = isSyncing
        )
    }

    var lastVoidedTransaction: Transaction? = null
        private set

    var lastSavedTransactionId: String? = null
        private set

    fun voidTransaction(txn: Transaction, onComplete: ((Boolean) -> Unit)? = null) {
        lastVoidedTransaction = txn
        viewModelScope.launch(ioDispatcher) {
            val result = transactionRepository.voidTransaction(txn.id)
            onComplete?.invoke(result is Result.Success)
        }
    }

    fun voidTransaction(txnId: String) {
        val currentTxn = _uiState.value.groupedTransactions
            .flatMap { it.transactions }
            .firstOrNull { it.id == txnId }
        lastVoidedTransaction = currentTxn
        viewModelScope.launch(ioDispatcher) {
            transactionRepository.voidTransaction(txnId)
        }
    }

    fun undoVoid(onComplete: ((Boolean) -> Unit)? = null) {
        val txn = lastVoidedTransaction ?: run {
            onComplete?.invoke(false)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val result = transactionRepository.updateTransaction(txn)
            if (result is Result.Success) {
                lastVoidedTransaction = null
                onComplete?.invoke(true)
            } else {
                onComplete?.invoke(false)
            }
        }
    }

    fun recordSavedTransaction(id: String) {
        lastSavedTransactionId = id
    }

    fun syncNow(onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(ioDispatcher) {
            isSyncingFlow.value = true
            val res = transactionRepository.syncPendingTransactions()
            isSyncingFlow.value = false
            onComplete?.invoke(res is Result.Success)
        }
    }

    fun undoSave(txnId: String? = null, onComplete: ((Boolean) -> Unit)? = null) {
        val targetId = txnId ?: lastSavedTransactionId ?: run {
            onComplete?.invoke(false)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val result = transactionRepository.undoRecentSave(targetId)
            if (result is Result.Success) {
                if (lastSavedTransactionId == targetId) {
                    lastSavedTransactionId = null
                }
                onComplete?.invoke(true)
            } else {
                onComplete?.invoke(false)
            }
        }
    }

    fun voidInflow(inflowId: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(ioDispatcher) {
            val result = inflowRepository?.voidInflow(inflowId)
            val success = result is Result.Success
            onComplete?.invoke(success)
        }
    }

    companion object {
        /**
         * Single source of truth for a row's sync/review status.
         * Priority: Failed, Needs review, Waiting to sync, Synced.
         */
        fun computeRowStatus(txn: Transaction, queueIds: QueueTransactionIds): RowStatus = when {
            txn.id in queueIds.failed -> RowStatus.FAILED
            txn.reviewState == ReviewState.NEEDS_REVIEW -> RowStatus.NEEDS_REVIEW
            txn.id in queueIds.pending -> RowStatus.WAITING_TO_SYNC
            else -> RowStatus.SYNCED
        }

        fun provideFactory(
            transactionRepository: TransactionRepository,
            syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
            inflowRepository: InflowRepository? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            budgetRepository: com.financeautomation.domain.repository.BudgetRepository? = null,
            categoryRepository: com.financeautomation.domain.repository.CategoryRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TransactionsViewModel(
                    transactionRepository = transactionRepository,
                    syncQueueDaoProvider = syncQueueDaoProvider,
                    inflowRepository = inflowRepository,
                    ioDispatcher = ioDispatcher,
                    budgetRepository = budgetRepository,
                    categoryRepository = categoryRepository
                ) as T
            }
        }
    }
}

