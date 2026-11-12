package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.utils.AccountingUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val activeFilter: TransactionFilter = TransactionFilter(),
    val groupedTransactions: List<DayTransactionGroup> = emptyList(),
    val isLoading: Boolean = false
)

/**
 * ViewModel managing full transaction ledger viewing, month navigation,
 * multi-attribute filtering, search, and daily grouped calculations.
 */
class TransactionsViewModel(
    private val transactionRepository: TransactionRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val monthOffsetFlow = MutableStateFlow(0)
    private val searchQueryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow(TransactionFilter())

    private val _uiState = MutableStateFlow(TransactionsUiState(isLoading = true))
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    init {
        observeTransactions()
    }

    private fun observeTransactions() {
        viewModelScope.launch(ioDispatcher) {
            combine(
                transactionRepository.getTransactions(),
                monthOffsetFlow,
                searchQueryFlow,
                filterFlow
            ) { transactions, monthOffset, query, filter ->
                calculateState(transactions, monthOffset, query, filter)
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
        filter: TransactionFilter
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

        val monthTransactions = transactions.filter {
            it.timestamp in startOfMonth until endOfMonth
        }

        var totalOut = 0.0
        var myShare = 0.0
        for (txn in monthTransactions) {
            if (txn.status != TransactionStatus.VOID) {
                totalOut += txn.totalAmount
                myShare += txn.myShare
            }
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
                val matchesStatus = when (st) {
                    "pending" -> txn.status == TransactionStatus.PENDING
                    "synced" -> txn.syncState == SyncState.SYNCED
                    "failed" -> txn.syncState == SyncState.FAILED
                    "needs review", "needs_review" -> txn.reviewState == ReviewState.NEEDS_REVIEW
                    else -> true
                }
                if (!matchesStatus) {
                    return@filter false
                }
            }

            true
        }

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
            activeFilter = filter,
            groupedTransactions = grouped,
            isLoading = false
        )
    }

    companion object {
        fun provideFactory(
            transactionRepository: TransactionRepository,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TransactionsViewModel(
                    transactionRepository = transactionRepository,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}

