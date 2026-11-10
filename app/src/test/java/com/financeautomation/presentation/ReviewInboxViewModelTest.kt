package com.financeautomation.presentation

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.presentation.viewmodel.ReviewInboxViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ReviewInboxViewModelTest {

    private lateinit var fakeTransactionRepository: FakeTransactionRepository
    private lateinit var viewModel: ReviewInboxViewModel

    private val txn1 = Transaction(
        id = "txn_inbox_001",
        timestamp = 1730811600000L,
        dateSerial = 46331,
        timeSerial = 0.5,
        account = "SBI",
        merchant = "Swiggy",
        tripTag = "",
        category = "Food & Drink",
        totalAmount = 350.0,
        companions = "Personal",
        myShare = 350.0,
        owedToMe = 0.0,
        status = TransactionStatus.SETTLED,
        sourcePackage = "com.google.android.apps.nbu.paisa.user",
        reviewState = ReviewState.NEEDS_REVIEW,
        syncState = SyncState.PENDING,
        isPossibleDuplicate = false
    )

    private val txn2 = Transaction(
        id = "txn_inbox_002",
        timestamp = 1730811700000L,
        dateSerial = 46331,
        timeSerial = 0.51,
        account = "Canara",
        merchant = "Amazon India",
        tripTag = "",
        category = "Shopping",
        totalAmount = 1200.0,
        companions = "Personal",
        myShare = 1200.0,
        owedToMe = 0.0,
        status = TransactionStatus.SETTLED,
        sourcePackage = "com.phonepe.app",
        reviewState = ReviewState.NEEDS_REVIEW,
        syncState = SyncState.PENDING,
        isPossibleDuplicate = true
    )

    @Before
    fun setUp() = runBlocking {
        fakeTransactionRepository = FakeTransactionRepository()
        fakeTransactionRepository.insertTransaction(txn1)
        fakeTransactionRepository.insertTransaction(txn2)

        viewModel = ReviewInboxViewModel(
            transactionRepository = fakeTransactionRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testInitialStateLoadsNeedsReviewTransactions() {
        val state = viewModel.uiState.value
        assertEquals(2, state.items.size)
        assertTrue(state.items.any { it.id == "txn_inbox_001" })
        assertTrue(state.items.any { it.id == "txn_inbox_002" && it.isPossibleDuplicate })
    }

    @Test
    fun test1TapConfirmUpgradesReviewStateToConfirmed() = runBlocking {
        viewModel.confirmTransaction("txn_inbox_001")

        val state = viewModel.uiState.value
        assertEquals("Transaction confirmed", state.userMessage)

        val updated = fakeTransactionRepository.getTransactionById("txn_inbox_001")
        assertNotNull(updated)
        assertEquals(ReviewState.CONFIRMED, updated?.reviewState)
        assertEquals(SyncState.PENDING, updated?.syncState)
    }

    @Test
    fun testConfirmWithCategoryUpdateAndPluralSavingsNormalization() = runBlocking {
        viewModel.confirmTransaction("txn_inbox_001", "Investment")

        val updated = fakeTransactionRepository.getTransactionById("txn_inbox_001")
        assertNotNull(updated)
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, updated?.category)
        assertEquals(ReviewState.CONFIRMED, updated?.reviewState)
    }

    @Test
    fun testConfirmWithSingularSavingNormalizesToPluralSavings() = runBlocking {
        viewModel.confirmTransaction("txn_inbox_001", "saving")

        val updated = fakeTransactionRepository.getTransactionById("txn_inbox_001")
        assertNotNull(updated)
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, updated?.category)
        assertEquals(ReviewState.CONFIRMED, updated?.reviewState)
    }

    @Test
    fun testVoidTransactionMarksStatusVoid() = runBlocking {
        viewModel.voidTransaction("txn_inbox_001")

        val state = viewModel.uiState.value
        assertEquals("Transaction voided", state.userMessage)

        val voided = fakeTransactionRepository.getTransactionById("txn_inbox_001")
        assertNotNull(voided)
        assertEquals(TransactionStatus.VOID, voided?.status)
        assertEquals(0.0, voided?.myShare ?: 1.0, 0.01)
        assertEquals(0.0, voided?.owedToMe ?: 1.0, 0.01)
    }

    @Test
    fun testSelectAndClearTransaction() {
        viewModel.selectTransaction(txn2)
        assertEquals("txn_inbox_002", viewModel.uiState.value.selectedTransaction?.id)

        viewModel.clearSelection()
        assertNull(viewModel.uiState.value.selectedTransaction)
    }

    @Test
    fun testConfirmNonExistentTransactionSetsError() = runBlocking {
        viewModel.confirmTransaction("txn_non_existent")

        val state = viewModel.uiState.value
        assertEquals("Transaction not found", state.errorMessage)
    }

    private class FakeTransactionRepository : TransactionRepository {
        private val storage = ConcurrentHashMap<String, Transaction>()
        private val allFlow = MutableStateFlow<List<Transaction>>(emptyList())
        private val needsReviewFlow = MutableStateFlow<List<Transaction>>(emptyList())

        private fun updateFlows() {
            val list = storage.values.toList()
            allFlow.value = list
            needsReviewFlow.value = list.filter { it.reviewState == ReviewState.NEEDS_REVIEW }
        }

        override fun getTransactions(): Flow<List<Transaction>> = allFlow.asStateFlow()
        override fun getNeedsReview(): Flow<List<Transaction>> = needsReviewFlow.asStateFlow()

        override suspend fun getTransactionById(id: String): Transaction? = storage[id]

        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            storage[transaction.id] = transaction
            updateFlows()
            return Result.Success(Unit)
        }

        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            storage[transaction.id] = transaction
            updateFlows()
            return Result.Success(Unit)
        }

        override suspend fun voidTransaction(id: String): Result<Unit> {
            val existing = storage[id] ?: return Result.Error(IllegalArgumentException("Not found"))
            val voided = existing.copy(
                myShare = 0.0,
                owedToMe = 0.0,
                status = TransactionStatus.VOID,
                syncState = SyncState.VOID
            )
            storage[id] = voided
            updateFlows()
            return Result.Success(Unit)
        }

        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> =
            storage.values.filter { Math.abs(it.totalAmount - amount) < 0.01 }

        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
    }
}
