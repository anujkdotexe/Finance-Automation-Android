package com.financeautomation.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PayableEntity
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Summary item for friend debts shown on the dashboard cards (Design Guide 5.1).
 */
data class DebtSummary(
    val name: String,
    val amount: Double
)

/**
 * Summary item for category breakdown bars (Design Guide 5.1).
 */
data class CategorySpend(
    val category: String,
    val amount: Double,
    val percentage: Float
)

/**
 * UI state for the Calm Utility Home Dashboard (Design Guide 5.1).
 */
data class DashboardUiState(
    val selectedMonthLabel: String = "",
    val currentBurn: Double = 0.0,
    val dailyAverage: Double = 0.0,
    val lastMonthBurn: Double = 0.0,
    val owedToMe: Double = 0.0,
    val topOwedToMe: List<DebtSummary> = emptyList(),
    val iOwePeople: Double = 0.0,
    val topIOwe: List<DebtSummary> = emptyList(),
    val needsReviewCount: Int = 0,
    val topCategories: List<CategorySpend> = emptyList(),
    val allCategories: List<CategorySpend> = emptyList(),
    val recentTransactions: List<Transaction> = emptyList(),
    val savings: Double = 0.0,
    val income: Double = 0.0,
    val transactionCount: Int = 0,
    val syncQueueItems: List<SyncQueueEntity> = emptyList(),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel aggregating calm utility financial KPIs and reactive offline sync status.
 */
class DashboardViewModel(
    private val transactionRepository: TransactionRepository,
    private val directSyncQueueDao: SyncQueueDao? = null,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    private val directPayableDao: PayableDao? = null,
    private val payableDaoProvider: (suspend () -> PayableDao)? = null,
    private val categoryRepository: CategoryRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDao: SyncQueueDao,
        categoryRepository: CategoryRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = syncQueueDao,
        syncQueueDaoProvider = null,
        directPayableDao = null,
        payableDaoProvider = null,
        categoryRepository = categoryRepository,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDao: SyncQueueDao,
        payableDao: PayableDao?,
        categoryRepository: CategoryRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = syncQueueDao,
        syncQueueDaoProvider = null,
        directPayableDao = payableDao,
        payableDaoProvider = null,
        categoryRepository = categoryRepository,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDaoProvider: suspend () -> SyncQueueDao,
        categoryRepository: CategoryRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = null,
        syncQueueDaoProvider = syncQueueDaoProvider,
        directPayableDao = null,
        payableDaoProvider = null,
        categoryRepository = categoryRepository,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDaoProvider: suspend () -> SyncQueueDao,
        payableDaoProvider: (suspend () -> PayableDao)?,
        categoryRepository: CategoryRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = null,
        syncQueueDaoProvider = syncQueueDaoProvider,
        directPayableDao = null,
        payableDaoProvider = payableDaoProvider,
        categoryRepository = categoryRepository,
        ioDispatcher = ioDispatcher
    )

    private val monthOffsetFlow = MutableStateFlow(0)
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeData()
    }

    private suspend fun resolveSyncQueueDao(): SyncQueueDao {
        return directSyncQueueDao ?: syncQueueDaoProvider?.invoke()
            ?: throw IllegalStateException("SyncQueueDao not available")
    }

    private suspend fun resolvePayableDao(): PayableDao? {
        return directPayableDao ?: payableDaoProvider?.invoke()
    }

    private fun observeData() {
        viewModelScope.launch(ioDispatcher) {
            val syncQueueDao = resolveSyncQueueDao()
            val payableDao = resolvePayableDao()
            val payablesFlow = payableDao?.observePending() ?: flowOf(emptyList())
            val categoriesFlow = categoryRepository?.getCategories() ?: flowOf(emptyList())

            combine(
                transactionRepository.getTransactions(),
                syncQueueDao.getAllQueueFlow(),
                payablesFlow,
                categoriesFlow,
                monthOffsetFlow
            ) { transactions, queueItems, pendingPayables, categories, monthOffset ->
                calculateDashboardMetrics(transactions, queueItems, pendingPayables, categories, monthOffset)
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

    fun nextMonth() {
        monthOffsetFlow.update { it + 1 }
    }

    fun previousMonth() {
        monthOffsetFlow.update { it - 1 }
    }

    fun currentMonth() {
        monthOffsetFlow.value = 0
    }

    private fun calculateDashboardMetrics(
        transactions: List<Transaction>,
        queueItems: List<SyncQueueEntity>,
        pendingPayables: List<PayableEntity>,
        categories: List<com.financeautomation.domain.model.Category> = emptyList(),
        monthOffset: Int
    ): DashboardUiState {
        val excludedCategoryNames: Set<String> = categories
            .filter { it.isExcludedFromLivingBurn }
            .map { it.name.trim().lowercase() }
            .toSet()

        fun isExcluded(category: String): Boolean {
            val trimmed = category.trim().lowercase()
            return excludedCategoryNames.contains(trimmed) ||
                trimmed == CategoryConstants.CATEGORY_SAVINGS.lowercase() ||
                trimmed == "transfer"
        }

        val nowCal = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfMonth = cal.timeInMillis

        val monthLabelFormat = SimpleDateFormat("MMMM yyyy", Locale.US)
        val selectedMonthLabel = monthLabelFormat.format(cal.time)

        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val daysElapsed = if (monthOffset == 0) {
            nowCal.get(Calendar.DAY_OF_MONTH).coerceIn(1, daysInMonth)
        } else if (monthOffset < 0) {
            daysInMonth
        } else {
            1
        }

        cal.add(Calendar.MONTH, 1)
        val endOfMonth = cal.timeInMillis

        // Prior month window for "Last month" comparison
        val priorCal = Calendar.getInstance().apply {
            timeInMillis = startOfMonth
            add(Calendar.MONTH, -1)
        }
        val startOfPriorMonth = priorCal.timeInMillis
        val endOfPriorMonth = startOfMonth

        var currentBurn = 0.0
        var lastMonthBurn = 0.0
        var savings = 0.0
        var income = 0.0
        var txnCount = 0
        var needsReviewCount = 0

        val categorySums = mutableMapOf<String, Double>()
        val monthTransactions = mutableListOf<Transaction>()
        val receivablesByFriend = mutableMapOf<String, Double>()

        for (txn in transactions) {
            if (txn.reviewState == ReviewState.NEEDS_REVIEW && txn.status != TransactionStatus.VOID) {
                needsReviewCount++
            }

            // Global unsettled receivables
            if (txn.status == TransactionStatus.PENDING && txn.owedToMe > 0.0) {
                val tokens = com.financeautomation.domain.utils.AccountingUtils.tokens(txn.companions)
                if (tokens.isEmpty() || txn.companions.equals("Personal", ignoreCase = true)) {
                    val friend = "Others"
                    receivablesByFriend[friend] = (receivablesByFriend[friend] ?: 0.0) + txn.owedToMe
                } else {
                    val sharePerPerson = txn.owedToMe / tokens.size
                    for (friend in tokens) {
                        receivablesByFriend[friend] = (receivablesByFriend[friend] ?: 0.0) + sharePerPerson
                    }
                }
            }

            // Prior month burn
            if (txn.timestamp in startOfPriorMonth until endOfPriorMonth && txn.status != TransactionStatus.VOID) {
                if (!isExcluded(txn.category)) {
                    lastMonthBurn += txn.myShare
                }
            }

            // Selected month transactions
            if (txn.timestamp in startOfMonth until endOfMonth && txn.status != TransactionStatus.VOID) {
                txnCount++
                monthTransactions.add(txn)

                val isSav = txn.category.equals(CategoryConstants.CATEGORY_SAVINGS, ignoreCase = true)

                if (isSav) {
                    savings += txn.myShare
                } else if (!isExcluded(txn.category)) {
                    currentBurn += txn.myShare
                    categorySums[txn.category] = (categorySums[txn.category] ?: 0.0) + txn.myShare
                }
            }
        }

        val dailyAverage = if (daysElapsed > 0 && currentBurn > 0.0) currentBurn / daysElapsed else 0.0

        val allCategories = categorySums.entries
            .map { (cat, sum) ->
                val pct = if (currentBurn > 0.0) (sum / currentBurn).toFloat().coerceIn(0f, 1f) else 0f
                CategorySpend(category = cat, amount = sum, percentage = pct)
            }
            .sortedByDescending { it.amount }

        val topCategories = allCategories.take(5)

        val totalOwedToMe = receivablesByFriend.values.sum()
        val topOwedToMe = receivablesByFriend.entries
            .map { DebtSummary(name = it.key, amount = it.value) }
            .sortedByDescending { it.amount }
            .take(3)

        val totalIOwe = pendingPayables.sumOf { it.amountIOwe }
        val topIOwe = pendingPayables
            .groupBy { it.paidBy }
            .map { (person, list) -> DebtSummary(name = person, amount = list.sumOf { it.amountIOwe }) }
            .sortedByDescending { it.amount }
            .take(3)

        val recentTransactions = monthTransactions
            .sortedByDescending { it.timestamp }
            .take(5)

        return DashboardUiState(
            selectedMonthLabel = selectedMonthLabel,
            currentBurn = currentBurn,
            dailyAverage = dailyAverage,
            lastMonthBurn = lastMonthBurn,
            owedToMe = totalOwedToMe,
            topOwedToMe = topOwedToMe,
            iOwePeople = totalIOwe,
            topIOwe = topIOwe,
            needsReviewCount = needsReviewCount,
            topCategories = topCategories,
            allCategories = allCategories,
            recentTransactions = recentTransactions,
            savings = savings,
            income = income,
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
            payableDao: PayableDao? = null,
            categoryRepository: CategoryRepository? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(
                    transactionRepository = transactionRepository,
                    syncQueueDao = syncQueueDao,
                    payableDao = payableDao,
                    categoryRepository = categoryRepository,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }

        fun provideFactory(
            transactionRepository: TransactionRepository,
            syncQueueDaoProvider: suspend () -> SyncQueueDao,
            payableDaoProvider: (suspend () -> PayableDao)? = null,
            categoryRepository: CategoryRepository? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(
                    transactionRepository = transactionRepository,
                    syncQueueDaoProvider = syncQueueDaoProvider,
                    payableDaoProvider = payableDaoProvider,
                    categoryRepository = categoryRepository,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}
