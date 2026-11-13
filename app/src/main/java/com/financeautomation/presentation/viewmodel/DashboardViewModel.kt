package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
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
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.usecase.BudgetProgress
import com.financeautomation.domain.usecase.BudgetProgressUseCase
import com.financeautomation.domain.usecase.LivingBurn
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.BudgetRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.InflowRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import java.util.TimeZone

/**
 * Breakdown of monthly spend by category for visualization.
 */
data class CategorySpend(
    val category: String,
    val amount: Double,
    val percentage: Float,
    val budgetProgress: BudgetProgress? = null
)

/**
 * Outstanding debt summary per counterparty (who owes me, or who I owe).
 */
data class DebtSummary(
    val name: String,
    val amount: Double
)

/**
 * Reactive state snapshot powering the calm utility Dashboard.
 */
data class DashboardUiState(
    val monthOffset: Int = 0,
    val selectedMonthLabel: String = "",
    val currentBurn: Double = 0.0,
    val dailyAverage: Double = 0.0,
    val lastMonthBurn: Double = 0.0,
    val lastMonthSameDayBurn: Double = 0.0,
    val paceDeltaPercent: Double? = null,
    val onTrack: Boolean = true,
    val owedToMe: Double = 0.0,
    val topOwedToMe: List<DebtSummary> = emptyList(),
    val iOwePeople: Double = 0.0,
    val topIOwe: List<DebtSummary> = emptyList(),
    val needsReviewCount: Int = 0,
    /** Rows waiting in review, which are not counted in the burn: how many and how much. */
    val unconfirmedCount: Int = 0,
    val unconfirmedAmount: Double = 0.0,
    val needsReviewTotal: Int = 0,
    val waitingCount: Int = 0,
    val failedCount: Int = 0,
    val topCategories: List<CategorySpend> = emptyList(),
    val allCategories: List<CategorySpend> = emptyList(),
    val recentTransactions: List<Transaction> = emptyList(),
    val savings: Double = 0.0,
    val income: Double = 0.0,
    /** Refunds of the month, already taken off [currentBurn]. */
    val refunds: Double = 0.0,
    /** Spending before refunds: the categories of Where it went add up to this. */
    val grossBurn: Double = 0.0,
    val transactionCount: Int = 0,
    val syncQueueItems: List<SyncQueueEntity> = emptyList(),
    val isOffline: Boolean = false,
    val queuedItems: List<QueuedItem> = emptyList(),
    val isHintDismissed: Boolean = false,
    val isSyncing: Boolean = false,
    val syncMessage: UiText? = null,
    val errorMessage: UiText? = null
)

/**
 * An item waiting in the sync queue, shown while offline. Carries only the merchant name,
 * amount and creation time; no ids.
 */
data class QueuedItem(
    val name: String,
    val amount: Double,
    val createdAt: Long
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
    private val directPendingCaptureDao: PendingCaptureDao? = null,
    private val pendingCaptureDaoProvider: (suspend () -> PendingCaptureDao)? = null,
    private val inflowRepository: InflowRepository? = null,
    private val onlineState: Flow<Boolean>? = null,
    private val preferencesRepository: PreferencesRepository? = null,
    private val budgetRepository: BudgetRepository? = null,
    private val budgetProgressUseCase: BudgetProgressUseCase = BudgetProgressUseCase(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDao: SyncQueueDao,
        categoryRepository: CategoryRepository? = null,
        inflowRepository: InflowRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = syncQueueDao,
        syncQueueDaoProvider = null,
        directPayableDao = null,
        payableDaoProvider = null,
        categoryRepository = categoryRepository,
        directPendingCaptureDao = null,
        pendingCaptureDaoProvider = null,
        inflowRepository = inflowRepository,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDao: SyncQueueDao,
        payableDao: PayableDao?,
        categoryRepository: CategoryRepository? = null,
        inflowRepository: InflowRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = syncQueueDao,
        syncQueueDaoProvider = null,
        directPayableDao = payableDao,
        payableDaoProvider = null,
        categoryRepository = categoryRepository,
        directPendingCaptureDao = null,
        pendingCaptureDaoProvider = null,
        inflowRepository = inflowRepository,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDao: SyncQueueDao,
        payableDao: PayableDao?,
        pendingCaptureDao: PendingCaptureDao?,
        categoryRepository: CategoryRepository? = null,
        inflowRepository: InflowRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = syncQueueDao,
        syncQueueDaoProvider = null,
        directPayableDao = payableDao,
        payableDaoProvider = null,
        categoryRepository = categoryRepository,
        directPendingCaptureDao = pendingCaptureDao,
        pendingCaptureDaoProvider = null,
        inflowRepository = inflowRepository,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDaoProvider: suspend () -> SyncQueueDao,
        categoryRepository: CategoryRepository? = null,
        inflowRepository: InflowRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = null,
        syncQueueDaoProvider = syncQueueDaoProvider,
        directPayableDao = null,
        payableDaoProvider = null,
        categoryRepository = categoryRepository,
        directPendingCaptureDao = null,
        pendingCaptureDaoProvider = null,
        inflowRepository = inflowRepository,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDaoProvider: suspend () -> SyncQueueDao,
        payableDaoProvider: (suspend () -> PayableDao)?,
        categoryRepository: CategoryRepository? = null,
        inflowRepository: InflowRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = null,
        syncQueueDaoProvider = syncQueueDaoProvider,
        directPayableDao = null,
        payableDaoProvider = payableDaoProvider,
        categoryRepository = categoryRepository,
        directPendingCaptureDao = null,
        pendingCaptureDaoProvider = null,
        inflowRepository = inflowRepository,
        ioDispatcher = ioDispatcher
    )

    constructor(
        transactionRepository: TransactionRepository,
        syncQueueDaoProvider: suspend () -> SyncQueueDao,
        payableDaoProvider: (suspend () -> PayableDao)?,
        pendingCaptureDaoProvider: (suspend () -> PendingCaptureDao)?,
        categoryRepository: CategoryRepository? = null,
        inflowRepository: InflowRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionRepository = transactionRepository,
        directSyncQueueDao = null,
        syncQueueDaoProvider = syncQueueDaoProvider,
        directPayableDao = null,
        payableDaoProvider = payableDaoProvider,
        categoryRepository = categoryRepository,
        directPendingCaptureDao = null,
        pendingCaptureDaoProvider = pendingCaptureDaoProvider,
        inflowRepository = inflowRepository,
        ioDispatcher = ioDispatcher
    )

    private val monthOffsetFlow = MutableStateFlow(0)
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeData()
        loadHintDismissed()
    }

    private fun loadHintDismissed() {
        viewModelScope.launch(ioDispatcher) {
            val dismissed = preferencesRepository?.isDashboardHintDismissed() ?: false
            _uiState.update { it.copy(isHintDismissed = dismissed) }
        }
    }

    /**
     * Dismisses the first-run onboarding hint and persists the choice.
     */
    fun dismissFirstRunHint() {
        _uiState.update { it.copy(isHintDismissed = true) }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository?.setDashboardHintDismissed(true)
        }
    }

    private suspend fun resolveSyncQueueDao(): SyncQueueDao {
        return directSyncQueueDao ?: syncQueueDaoProvider?.invoke()
            ?: throw IllegalStateException("SyncQueueDao not available")
    }

    private suspend fun resolvePayableDao(): PayableDao? {
        return directPayableDao ?: payableDaoProvider?.invoke()
    }

    private suspend fun resolvePendingCaptureDao(): PendingCaptureDao? {
        return directPendingCaptureDao ?: pendingCaptureDaoProvider?.invoke()
    }

    private fun observeData() {
        viewModelScope.launch(ioDispatcher) {
            val syncQueueDao = resolveSyncQueueDao()
            val payableDao = resolvePayableDao()
            val pendingCaptureDao = resolvePendingCaptureDao()
            val payablesFlow = payableDao?.observePending() ?: flowOf(emptyList())
            val categoriesFlow = categoryRepository?.getCategories() ?: flowOf(emptyList())
            val needsReviewCapturesFlow = pendingCaptureDao?.getNeedsReviewCapturesFlow() ?: flowOf(emptyList())
            val inflowsFlow = inflowRepository?.getAllInflows() ?: flowOf(emptyList())
            val budgetsFlow = budgetRepository?.observeBudgets() ?: flowOf(emptyList())

            combine(
                transactionRepository.getTransactions(),
                syncQueueDao.getAllQueueFlow(),
                payablesFlow,
                categoriesFlow,
                needsReviewCapturesFlow,
                monthOffsetFlow,
                onlineState ?: flowOf(true),
                inflowsFlow,
                budgetsFlow
            ) { flows ->
                @Suppress("UNCHECKED_CAST")
                val transactions = flows[0] as List<Transaction>
                @Suppress("UNCHECKED_CAST")
                val queueItems = flows[1] as List<SyncQueueEntity>
                @Suppress("UNCHECKED_CAST")
                val pendingPayables = flows[2] as List<PayableEntity>
                @Suppress("UNCHECKED_CAST")
                val categories = flows[3] as List<com.financeautomation.domain.model.Category>
                @Suppress("UNCHECKED_CAST")
                val needsReviewCaptures = flows[4] as List<PendingCaptureEntity>
                val monthOffset = flows[5] as Int
                val isOnline = flows[6] as Boolean
                @Suppress("UNCHECKED_CAST")
                val inflows = flows[7] as List<Inflow>
                @Suppress("UNCHECKED_CAST")
                val budgets = flows[8] as List<Budget>

                calculateDashboardMetrics(
                    transactions = transactions,
                    queueItems = queueItems,
                    pendingPayables = pendingPayables,
                    categories = categories,
                    needsReviewCaptures = needsReviewCaptures,
                    monthOffset = monthOffset,
                    inflows = inflows,
                    budgets = budgets
                ).copy(
                    isOffline = !isOnline,
                    queuedItems = buildQueuedItems(transactions, queueItems)
                )
            }.collect { calculatedState ->
                _uiState.update { current ->
                    calculatedState.copy(
                        isSyncing = current.isSyncing,
                        syncMessage = current.syncMessage,
                        errorMessage = current.errorMessage,
                        isHintDismissed = current.isHintDismissed
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

    fun setMonthOffset(offset: Int) {
        monthOffsetFlow.value = offset
    }

    private fun buildQueuedItems(
        transactions: List<Transaction>,
        queueItems: List<SyncQueueEntity>
    ): List<QueuedItem> {
        val byId = transactions.associateBy { it.id }
        return queueItems
            .filter { it.status == SyncQueueEntity.STATUS_PENDING }
            .sortedBy { it.createdAt }
            .mapNotNull { item ->
                val txn = byId[item.transactionId] ?: return@mapNotNull null
                QueuedItem(name = txn.merchant, amount = txn.totalAmount, createdAt = item.createdAt)
            }
    }

    private fun calculateDashboardMetrics(
        transactions: List<Transaction>,
        queueItems: List<SyncQueueEntity>,
        pendingPayables: List<PayableEntity>,
        categories: List<com.financeautomation.domain.model.Category> = emptyList(),
        needsReviewCaptures: List<PendingCaptureEntity> = emptyList(),
        monthOffset: Int,
        inflows: List<Inflow> = emptyList(),
        budgets: List<Budget> = emptyList()
    ): DashboardUiState {
        // The living burn rule lives in LivingBurn so budgets and the widget use exactly the same one
        val excludedCategoryNames: Set<String> = LivingBurn.excludedNames(categories)

        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz)
        val cal = Calendar.getInstance(kolkataTz).apply {
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfMonth = cal.timeInMillis
        val selectedYear = cal.get(Calendar.YEAR)
        val selectedMonth = cal.get(Calendar.MONTH) + 1

        val monthLabelFormat = SimpleDateFormat("MMMM yyyy", Locale.US).apply {
            timeZone = kolkataTz
        }
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
        val selectedWindow = startOfMonth until endOfMonth

        // Prior month window for "Last month" comparison
        val priorCal = Calendar.getInstance(kolkataTz).apply {
            timeInMillis = startOfMonth
            add(Calendar.MONTH, -1)
        }
        val startOfPriorMonth = priorCal.timeInMillis
        val endOfPriorMonth = startOfMonth
        val priorWindow = startOfPriorMonth until endOfPriorMonth

        val priorSameDayCal = (priorCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, daysElapsed)
        }
        val endOfPriorSameDay = minOf(priorSameDayCal.timeInMillis, endOfPriorMonth)

        val currentBurn = LivingBurn.total(transactions, selectedWindow, excludedCategoryNames, inflows)
        val lastMonthBurn = LivingBurn.total(transactions, priorWindow, excludedCategoryNames, inflows)
        val lastMonthSameDayBurn = LivingBurn.total(
            transactions, startOfPriorMonth until endOfPriorSameDay, excludedCategoryNames, inflows
        )
        val unconfirmed = LivingBurn.unconfirmed(transactions, excludedCategoryNames)
        val refunds = LivingBurn.refundTotal(inflows, selectedWindow)
        var savings = 0.0
        var income = 0.0
        for (inflow in inflows) {
            if (inflow.timestamp in startOfMonth until endOfMonth && !inflow.status.equals("VOID", ignoreCase = true)) {
                val type = inflow.inflowType.trim()
                // Refunds are not income: they already lower the living burn (LivingBurn.refundTotal)
                val isCounted = type.equals("Income / Salary", ignoreCase = true) ||
                    type.equals("Income", ignoreCase = true)
                val isExcluded = type.equals("Friend Settlement", ignoreCase = true) ||
                    type.equals("Self Transfer", ignoreCase = true) ||
                    type.equals("Transfer", ignoreCase = true)
                if (isCounted && !isExcluded) {
                    income += inflow.amount
                }
            }
        }
        var txnCount = 0
        var needsReviewCount = 0
        var needsReviewWithoutFailedSync = 0
        val failedTransactionIds = queueItems
            .filter { it.status == SyncQueueEntity.STATUS_FAILED }
            .map { it.transactionId }
            .toSet()

        val categorySums = mutableMapOf<String, Double>()
        val monthTransactions = mutableListOf<Transaction>()
        val receivablesByKey = mutableMapOf<String, Double>()
        val displayNamesByKey = mutableMapOf<String, String>()

        for (txn in transactions) {
            if (txn.reviewState == ReviewState.NEEDS_REVIEW && txn.status != TransactionStatus.VOID) {
                needsReviewCount++
                // A failed sync also puts its transaction back in review; count it once, as a failed item
                if (txn.id !in failedTransactionIds) needsReviewWithoutFailedSync++
            }

            // Global unsettled receivables
            if (txn.status == TransactionStatus.PENDING && txn.owedToMe > 0.0) {
                val perPersonShares = com.financeautomation.domain.utils.AccountingUtils.calculateReceivablesForTransaction(txn)
                for (share in perPersonShares) {
                    receivablesByKey[share.key] = (receivablesByKey[share.key] ?: 0.0) + share.amount
                    val existing = displayNamesByKey[share.key]
                    if (existing == null || (existing.none { it.isUpperCase() } && share.displayName.any { it.isUpperCase() })) {
                        displayNamesByKey[share.key] = share.displayName
                    }
                }
            }

            // Selected month transactions
            if (txn.timestamp in startOfMonth until endOfMonth && txn.status != TransactionStatus.VOID) {
                txnCount++
                monthTransactions.add(txn)

                val isSav = txn.category.equals(CategoryConstants.CATEGORY_SAVINGS, ignoreCase = true)

                if (isSav) {
                    savings += txn.myShare
                } else if (LivingBurn.counts(txn, selectedWindow, excludedCategoryNames)) {
                    categorySums[txn.category] = (categorySums[txn.category] ?: 0.0) + txn.myShare
                }
            }
        }

        val dailyAverage = LivingBurn.dailyAverage(currentBurn, daysElapsed)

        val budgetProgressMap = if (budgets.isNotEmpty()) {
            budgetProgressUseCase.forMonth(
                year = selectedYear,
                month = selectedMonth,
                budgets = budgets,
                transactions = transactions,
                categories = categories
            ).associateBy { it.category.trim().lowercase() }
        } else {
            emptyMap()
        }

        val grossBurn = categorySums.values.sum()
        val allCategories = categorySums.entries
            .map { (cat, sum) ->
                val pct = if (grossBurn > 0.0) (sum / grossBurn).toFloat().coerceIn(0f, 1f) else 0f
                val progress = budgetProgressMap[cat.trim().lowercase()]
                CategorySpend(
                    category = cat,
                    amount = sum,
                    percentage = pct,
                    budgetProgress = progress
                )
            }
            .sortedByDescending { it.amount }

        val topCategories = allCategories.take(5)

        val totalOwedToMe = receivablesByKey.values.sum()
        val topOwedToMe = receivablesByKey.entries
            .map { DebtSummary(name = displayNamesByKey[it.key] ?: it.key, amount = it.value) }
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

        val paceDeltaPercent: Double? = if (lastMonthSameDayBurn > 0.0) {
            ((currentBurn - lastMonthSameDayBurn) / lastMonthSameDayBurn) * 100.0
        } else {
            null
        }
        val onTrack = currentBurn <= lastMonthSameDayBurn

        val waitingCount = queueItems.count {
            it.status == SyncQueueEntity.STATUS_PENDING || it.status == SyncQueueEntity.STATUS_IN_PROGRESS
        }
        val failedCount = queueItems.count {
            it.status == SyncQueueEntity.STATUS_FAILED
        }
        val capturesInNeedsReview = needsReviewCaptures.size
        val needsReviewTotal = needsReviewWithoutFailedSync + capturesInNeedsReview + failedCount

        return DashboardUiState(
            monthOffset = monthOffset,
            selectedMonthLabel = selectedMonthLabel,
            currentBurn = currentBurn,
            dailyAverage = dailyAverage,
            lastMonthBurn = lastMonthBurn,
            lastMonthSameDayBurn = lastMonthSameDayBurn,
            paceDeltaPercent = paceDeltaPercent,
            onTrack = onTrack,
            owedToMe = totalOwedToMe,
            topOwedToMe = topOwedToMe,
            iOwePeople = totalIOwe,
            topIOwe = topIOwe,
            needsReviewCount = needsReviewCount,
            refunds = refunds,
            grossBurn = grossBurn,
            unconfirmedCount = unconfirmed.count,
            unconfirmedAmount = unconfirmed.amount,
            needsReviewTotal = needsReviewTotal,
            waitingCount = waitingCount,
            failedCount = failedCount,
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

            when (val res = transactionRepository.syncPendingTransactions(userInitiated = true)) {
                is Result.Success -> {
                    val count = res.data
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncMessage = UiText.res(R.string.dashboard_msg_synced_sheets, count)
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            errorMessage = res.message?.let { msg -> UiText.dynamic(msg) }
                                ?: UiText.res(R.string.dashboard_err_sync_failed, res.throwable.message ?: "")
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
            pendingCaptureDao: PendingCaptureDao? = null,
            categoryRepository: CategoryRepository? = null,
            inflowRepository: InflowRepository? = null,
            budgetRepository: BudgetRepository? = null,
            budgetProgressUseCase: BudgetProgressUseCase = BudgetProgressUseCase(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(
                    transactionRepository = transactionRepository,
                    directSyncQueueDao = syncQueueDao,
                    directPayableDao = payableDao,
                    directPendingCaptureDao = pendingCaptureDao,
                    categoryRepository = categoryRepository,
                    inflowRepository = inflowRepository,
                    budgetRepository = budgetRepository,
                    budgetProgressUseCase = budgetProgressUseCase,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }

        fun provideFactory(
            transactionRepository: TransactionRepository,
            syncQueueDaoProvider: suspend () -> SyncQueueDao,
            payableDaoProvider: (suspend () -> PayableDao)? = null,
            pendingCaptureDaoProvider: (suspend () -> PendingCaptureDao)? = null,
            categoryRepository: CategoryRepository? = null,
            inflowRepository: InflowRepository? = null,
            onlineState: Flow<Boolean>? = null,
            preferencesRepository: PreferencesRepository? = null,
            budgetRepository: BudgetRepository? = null,
            budgetProgressUseCase: BudgetProgressUseCase = BudgetProgressUseCase(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(
                    transactionRepository = transactionRepository,
                    syncQueueDaoProvider = syncQueueDaoProvider,
                    payableDaoProvider = payableDaoProvider,
                    pendingCaptureDaoProvider = pendingCaptureDaoProvider,
                    categoryRepository = categoryRepository,
                    inflowRepository = inflowRepository,
                    onlineState = onlineState,
                    preferencesRepository = preferencesRepository,
                    budgetRepository = budgetRepository,
                    budgetProgressUseCase = budgetProgressUseCase,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}
