package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsAndCategoryFilterTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestTransactionRepo : TransactionRepository {
        override fun getTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = null
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> = Result.Success(Unit)
        override suspend fun undoRecentSave(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
    }

    private class TestCategoryRepo(private val categories: List<Category>) : CategoryRepository {
        override fun getCategories(includeArchived: Boolean): Flow<List<Category>> = flowOf(categories)
        override suspend fun getAllCategories(includeArchived: Boolean): List<Category> = categories
        override suspend fun getActiveCategories(): List<Category> = categories
        override suspend fun getCategoryById(id: String): Category? = categories.find { it.id == id }
        override suspend fun getCategoryByName(name: String): Category? = categories.find { it.name.equals(name, ignoreCase = true) }
        override suspend fun insertCategory(category: Category) {}
        override suspend fun updateCategory(category: Category) {}
        override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean) {}
        override suspend fun archiveCategory(id: String) {}
        override suspend fun unarchiveCategory(id: String) {}
        override suspend fun deleteCategory(id: String) {}
        override suspend fun reorderCategories(categoryIds: List<String>) {}
    }

    @Test
    fun insightsViewModel_restoresAndPersistsMonthViaSavedStateHandle() = runTest(testDispatcher) {
        val handle = SavedStateHandle().apply {
            set(InsightsViewModel.KEY_SAVED_MONTH, "2026-05")
        }

        val vm = InsightsViewModel(
            transactionRepository = TestTransactionRepo(),
            savedStateHandle = handle,
            ioDispatcher = testDispatcher,
            clock = { 1793750400000L } // Nov 2026
        )
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 5), vm.uiState.value.selectedMonth)

        vm.previousMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 4), vm.uiState.value.selectedMonth)
        assertEquals("2026-04", handle.get<String>(InsightsViewModel.KEY_SAVED_MONTH))

        vm.nextMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 5), vm.uiState.value.selectedMonth)
        assertEquals("2026-05", handle.get<String>(InsightsViewModel.KEY_SAVED_MONTH))

        vm.setSelectedMonth(YearMonth.of(2026, 8))
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 8), vm.uiState.value.selectedMonth)
        assertEquals("2026-08", handle.get<String>(InsightsViewModel.KEY_SAVED_MONTH))
    }

    @Test
    fun transactionsViewModel_acceptsCustomCategoryInFilter() = runTest(testDispatcher) {
        val customCategory = Category(id = "cat_books", name = "Books & Learning")
        val vm = TransactionsViewModel(
            transactionRepository = TestTransactionRepo(),
            categoryRepository = TestCategoryRepo(listOf(customCategory)),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.setCategoryFilter("Books & Learning")
        advanceUntilIdle()
        assertEquals("Books & Learning", vm.uiState.value.activeFilter.category)

        // Unknown category is ignored
        vm.setCategoryFilter("unknown_category_xyz")
        advanceUntilIdle()
        assertEquals("Books & Learning", vm.uiState.value.activeFilter.category)

        vm.setCategoryFilter(null)
        advanceUntilIdle()
        assertNull(vm.uiState.value.activeFilter.category)
    }
}
