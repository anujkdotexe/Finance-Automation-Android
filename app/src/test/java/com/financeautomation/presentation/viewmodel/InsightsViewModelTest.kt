package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 13, 12, 0, 0, 0, kolkata).toInstant().toEpochMilli()
    private val nov = YearMonth.of(2026, 11)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun at(ym: YearMonth, day: Int = 5) =
        ZonedDateTime.of(ym.year, ym.monthValue, day, 12, 0, 0, 0, kolkata).toInstant().toEpochMilli()

    private fun txn(
        category: String,
        share: Double,
        month: YearMonth = nov,
        status: TransactionStatus = TransactionStatus.PERSONAL
    ) = Transaction(
        id = "t${category}$share${month}", timestamp = at(month), dateSerial = 0, timeSerial = 0.0, account = "SBI",
        merchant = "m", category = category, totalAmount = share, myShare = share, owedToMe = 0.0, status = status
    )

    private fun txnRepo(list: List<Transaction>): TransactionRepository = Proxy.newProxyInstance(
        TransactionRepository::class.java.classLoader, arrayOf(TransactionRepository::class.java)
    ) { _, method, _ ->
        if (method.name == "getTransactions") flowOf(list) else throw UnsupportedOperationException(method.name)
    } as TransactionRepository

    private fun categoryRepo(list: List<Category>): CategoryRepository = Proxy.newProxyInstance(
        CategoryRepository::class.java.classLoader, arrayOf(CategoryRepository::class.java)
    ) { _, method, _ ->
        if (method.name == "getCategories") flowOf(list) else throw UnsupportedOperationException(method.name)
    } as CategoryRepository

    private fun vm(list: List<Transaction>, categories: List<Category> = emptyList()) = InsightsViewModel(
        transactionRepository = txnRepo(list),
        categoryRepository = categoryRepo(categories),
        ioDispatcher = Dispatchers.Unconfined,
        zone = kolkata,
        clock = { now }
    )

    @Test
    fun aggregatesMySharePerCategoryLargestFirstWithPercent() {
        val state = vm(
            listOf(txn("Food", 300.0), txn("Food", 100.0), txn("Transit", 100.0), txn("Health", 500.0))
        ).uiState.value
        assertEquals(listOf("Health", "Food", "Transit"), state.slices.map { it.category })
        assertEquals(listOf(500.0, 400.0, 100.0), state.slices.map { it.amount })
        assertEquals(50.0, state.slices[0].percent, 0.0001)
        assertEquals(10.0, state.slices[2].percent, 0.0001)
        assertEquals(1000.0, state.total, 0.0001)
        assertEquals(100.0, state.slices.sumOf { it.percent }, 0.0001)
    }

    @Test
    fun usesTheLivingBurnRule() {
        val state = vm(
            listOf(
                txn("Food", 100.0),
                txn("Savings", 900.0),
                txn("Transfer", 900.0),
                txn("Rent", 900.0),
                txn("Food", 900.0, status = TransactionStatus.VOID)
            ),
            categories = listOf(Category("1", "Rent", isExcludedFromLivingBurn = true))
        ).uiState.value
        assertEquals(listOf("Food"), state.slices.map { it.category })
        assertEquals(100.0, state.total, 0.0001)
    }

    @Test
    fun sixCategoriesStayAsTheyAreAndSevenGroupTheRestAsOther() {
        val six = (1..6).map { txn("C$it", it * 10.0) }
        val sixState = vm(six).uiState.value
        assertEquals(6, sixState.slices.size)
        assertFalse(sixState.slices.any { it.isOther })

        val seven = (1..7).map { txn("C$it", it * 10.0) }
        val sevenState = vm(seven).uiState.value
        assertEquals(6, sevenState.slices.size)
        assertEquals(listOf("C7", "C6", "C5", "C4", "C3"), sevenState.slices.take(5).map { it.category })
        val other = sevenState.slices.last()
        assertTrue(other.isOther)
        assertEquals("Other", other.category)
        assertEquals(20.0 + 10.0, other.amount, 0.0001)
        assertEquals(280.0, sevenState.total, 0.0001)
        assertEquals(100.0, sevenState.slices.sumOf { it.percent }, 0.0001)
    }

    @Test
    fun manyCategoriesNeverNeedMoreThanSixColours() {
        val state = vm((1..20).map { txn("C$it", it.toDouble()) }).uiState.value
        assertEquals(6, state.slices.size)
        assertEquals(1, state.slices.count { it.isOther })
    }

    @Test
    fun theSeriesHasSixMonthsOldestFirstWithZeroMonthsFilled() {
        val state = vm(
            listOf(
                txn("Food", 100.0, nov),
                txn("Food", 40.0, nov.minusMonths(2)),
                txn("Food", 70.0, nov.minusMonths(5)),
                txn("Food", 999.0, nov.minusMonths(6))
            )
        ).uiState.value
        assertEquals(
            listOf(
                YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8),
                YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)
            ),
            state.series.map { it.month }
        )
        assertEquals(listOf(70.0, 0.0, 0.0, 40.0, 0.0, 100.0), state.series.map { it.amount })
    }

    @Test
    fun theSeriesSpansTheYearBoundary() {
        val january = ZonedDateTime.of(2027, 1, 10, 12, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        val model = InsightsViewModel(
            transactionRepository = txnRepo(listOf(txn("Food", 50.0, YearMonth.of(2026, 12)))),
            categoryRepository = categoryRepo(emptyList()),
            ioDispatcher = Dispatchers.Unconfined,
            zone = kolkata,
            clock = { january }
        )
        val months = model.uiState.value.series.map { it.month }
        assertEquals(YearMonth.of(2026, 8), months.first())
        assertEquals(YearMonth.of(2027, 1), months.last())
        assertEquals(50.0, model.uiState.value.series[4].amount, 0.0)
    }

    @Test
    fun theMonthSwitcherMovesTheDonutButNotTheBars() {
        val model = vm(listOf(txn("Food", 100.0, nov), txn("Transit", 60.0, nov.minusMonths(1))))
        assertEquals(listOf("Food"), model.uiState.value.slices.map { it.category })

        model.previousMonth()
        val october = model.uiState.value
        assertEquals(YearMonth.of(2026, 10), october.selectedMonth)
        assertEquals(listOf("Transit"), october.slices.map { it.category })
        assertEquals(60.0, october.total, 0.0001)
        assertEquals(model.uiState.value.series.last().month, nov)
        assertEquals(100.0, october.series.last().amount, 0.0001)
        assertEquals(1, model.monthsBeforeCurrent(october.selectedMonth))
    }

    @Test
    fun nextMonthStopsAtTheCurrentMonth() {
        val model = vm(listOf(txn("Food", 100.0)))
        model.nextMonth()
        assertEquals(nov, model.uiState.value.selectedMonth)
        assertTrue(model.uiState.value.isCurrentMonth)
        model.previousMonth()
        model.nextMonth()
        assertEquals(nov, model.uiState.value.selectedMonth)
    }

    @Test
    fun noDataGivesTheEmptyState() {
        val state = vm(emptyList()).uiState.value
        assertTrue(state.hasNoData)
        assertTrue(state.slices.isEmpty())
        assertEquals(6, state.series.size)
    }

    @Test
    fun aMonthWithoutSpendingIsNotTheWholeScreenEmptyWhenOtherMonthsHaveData() {
        val model = vm(listOf(txn("Food", 100.0, nov.minusMonths(3))))
        assertFalse(model.uiState.value.hasNoData)
        assertTrue(model.uiState.value.slices.isEmpty())
    }

    @Test
    fun categoryNamesDifferingOnlyByCaseAreCombined() {
        val state = vm(listOf(txn("Food", 100.0), txn("food", 50.0))).uiState.value
        assertEquals(1, state.slices.size)
        assertEquals(150.0, state.slices[0].amount, 0.0001)
    }
}
