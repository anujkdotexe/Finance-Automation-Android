package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.domain.model.Transaction
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Group of transactions belonging to a single calendar day.
 */
data class DayTransactionGroup(
    val dateLabel: String,
    val dayTimestamp: Long,
    val dayTotalOut: Double,
    val dayMyShare: Double,
    val transactions: List<Transaction>
)

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
    val status: String? = null
) {
    val isActive: Boolean
        get() = category != null || account != null || person != null || tripTag != null || status != null
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
    val isLoading: Boolean = false
)

/**
 * ViewModel managing full transaction ledger viewing, month navigation,
 * multi-attribute filtering, search, and daily grouped calculations.
 */
class TransactionsViewModel(
    private val transactionRepository: TransactionRepository,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val monthOffsetFlow = MutableStateFlow(0)
    private val searchQueryFlow = MutableStateFlow("")
    private val searchScopeFlow = MutableStateFlow(SearchScope.THIS_MONTH)
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
        viewModelScope.launch(ioDispatcher) {
            combine(
                transactionRepository.getTransactions(),
                monthOffsetFlow,
                searchQueryFlow,
                filterFlow,
                queueIdsFlow(),
                searchScopeFlow
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                calculateState(
                    transactions = values[0] as List<Transaction>,
                    monthOffset = values[1] as Int,
                    query = values[2] as String,
                    filter = values[3] as TransactionFilter,
                    queueIds = values[4] as QueueTransactionIds,
                    scope = values[5] as SearchScope
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
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

    fun clearFilter() {
        filterFlow.value = TransactionFilter()
    }

    fun clearSearch() {
        searchQueryFlow.value = ""
    }

    private fun calculateState(
        transactions: List<Transaction>,
        monthOffset: Int,
        query: String,
        filter: TransactionFilter,
        queueIds: QueueTransactionIds,
        scope: SearchScope = SearchScope.THIS_MONTH
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

        val filteredTransactions = monthTransactions.filter { txn ->
            if (txn.status == TransactionStatus.VOID) {
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
                if (!txn.account.equals(filter.account.trim(), ignoreCase = true)) {
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

            if (!filter.status.isNullOrBlank()) {
                val st = filter.status.trim().lowercase()
                val rowStatus = computeRowStatus(txn, queueIds)
                val matchesStatus = when (st) {
                    "pending", "waiting to sync", "waiting_to_sync" -> rowStatus == RowStatus.WAITING_TO_SYNC
                    "synced" -> rowStatus == RowStatus.SYNCED
                    "failed" -> rowStatus == RowStatus.FAILED
                    "needs review", "needs_review" -> rowStatus == RowStatus.NEEDS_REVIEW
                    else -> true
                }
                if (!matchesStatus) {
                    return@filter false
                }
            }

            true
        }

        // Header totals cover the whole month normally, and exactly the results for an all-months search
        val totalsSource = if (searchAllMonths) filteredTransactions else monthTransactions.filter { it.status != TransactionStatus.VOID }
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

        val grouped = filteredTransactions
            .sortedByDescending { it.timestamp }
            .groupBy { getStartOfDay(it.timestamp) }
            .map { (dayStart, txnsInDay) ->
                val dateLabel = dayFormat.format(dayStart)
                val dayTotalOut = txnsInDay.sumOf { it.totalAmount }
                val dayMyShare = txnsInDay.sumOf { it.myShare }
                DayTransactionGroup(
                    dateLabel = dateLabel,
                    dayTimestamp = dayStart,
                    dayTotalOut = dayTotalOut,
                    dayMyShare = dayMyShare,
                    transactions = txnsInDay
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
            isLoading = false
        )
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
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TransactionsViewModel(
                    transactionRepository = transactionRepository,
                    syncQueueDaoProvider = syncQueueDaoProvider,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}

