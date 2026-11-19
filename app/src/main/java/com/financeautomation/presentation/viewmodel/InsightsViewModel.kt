package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.repository.InflowRepository
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.InsightsCalculator
import com.financeautomation.domain.usecase.InsightsSlice
import com.financeautomation.domain.usecase.LivingBurn
import com.financeautomation.domain.usecase.MonthlyBurn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class InsightsUiState(
    val selectedMonth: YearMonth,
    val currentMonth: YearMonth,
    val slices: List<InsightsSlice> = emptyList(),
    val total: Double = 0.0,
    /** Last 6 months ending at the current month, oldest first. */
    val series: List<MonthlyBurn> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = true
) {
    val isCurrentMonth: Boolean get() = selectedMonth == currentMonth

    /** True when there is nothing to chart at all: no spending in the shown month and none in the last 6 months. */
    val hasNoData: Boolean get() = !isLoading && slices.isEmpty() && series.none { it.amount > 0.0 }
}

/**
 * Insights screen: a donut of the selected month's spending by category and a bar chart of Living burn for the last
 * 6 months. The month switcher moves the donut only; the bar chart always ends at the current month.
 */
class InsightsViewModel(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository? = null,
    private val inflowRepository: InflowRepository? = null,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = LivingBurn.KOLKATA,
    private val clock: () -> Long = System::currentTimeMillis,
    private val calculator: InsightsCalculator = InsightsCalculator(zone),
    private val otherLabel: String = InsightsCalculator.OTHER_LABEL
) : ViewModel() {

    private val currentMonth: YearMonth = YearMonth.from(Instant.ofEpochMilli(clock()).atZone(zone))
    private val selectedMonth: MutableStateFlow<YearMonth>

    private val _uiState: MutableStateFlow<InsightsUiState>
    val uiState: StateFlow<InsightsUiState> get() = _uiState.asStateFlow()

    init {
        val restoredMonth = savedStateHandle?.get<String>(KEY_SAVED_MONTH)?.let {
            try { YearMonth.parse(it) } catch (_: Exception) { null }
        }
        val initial = if (restoredMonth != null && restoredMonth <= currentMonth) restoredMonth else currentMonth
        selectedMonth = MutableStateFlow(initial)
        _uiState = MutableStateFlow(InsightsUiState(selectedMonth = initial, currentMonth = currentMonth))

        viewModelScope.launch(ioDispatcher) {
            combine(
                transactionRepository.getTransactions(),
                categoryRepository?.getCategories(includeArchived = true) ?: flowOf(emptyList()),
                inflowRepository?.getAllInflows() ?: flowOf(emptyList()),
                selectedMonth
            ) { transactions, categories, inflows, month -> build(transactions, categories, inflows, month) }
                .collect { _uiState.value = it }
        }
    }

    private fun build(
        transactions: List<Transaction>,
        categories: List<Category>,
        inflows: List<Inflow>,
        month: YearMonth
    ): InsightsUiState =
        InsightsUiState(
            selectedMonth = month,
            currentMonth = currentMonth,
            slices = calculator.slices(transactions, categories, month, otherLabel),
            total = calculator.total(transactions, categories, month, inflows),
            series = calculator.monthlySeries(transactions, categories, currentMonth, inflows = inflows),
            categories = categories,
            isLoading = false
        )

    fun previousMonth() {
        val next = selectedMonth.value.minusMonths(1)
        selectedMonth.value = next
        savedStateHandle?.set(KEY_SAVED_MONTH, next.toString())
    }

    /** Moves the donut forward, never past the current month. */
    fun nextMonth() {
        if (selectedMonth.value < currentMonth) {
            val next = selectedMonth.value.plusMonths(1)
            selectedMonth.value = next
            savedStateHandle?.set(KEY_SAVED_MONTH, next.toString())
        }
    }

    fun setSelectedMonth(month: YearMonth) {
        val target = if (month <= currentMonth) month else currentMonth
        selectedMonth.value = target
        savedStateHandle?.set(KEY_SAVED_MONTH, target.toString())
    }

    /** How many months before the current month [month] is, for opening Transactions on that month. */
    fun monthsBeforeCurrent(month: YearMonth): Int =
        (currentMonth.year - month.year) * 12 + (currentMonth.monthValue - month.monthValue)

    companion object {
        const val KEY_SAVED_MONTH = "insights_saved_month"

        fun provideFactory(
            transactionRepository: TransactionRepository,
            categoryRepository: CategoryRepository? = null,
            inflowRepository: InflowRepository? = null,
            savedStateHandle: androidx.lifecycle.SavedStateHandle? = null,
            otherLabel: String = InsightsCalculator.OTHER_LABEL,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                InsightsViewModel(
                    transactionRepository = transactionRepository,
                    categoryRepository = categoryRepository,
                    inflowRepository = inflowRepository,
                    savedStateHandle = savedStateHandle,
                    ioDispatcher = ioDispatcher,
                    otherLabel = otherLabel
                ) as T
        }
    }
}
