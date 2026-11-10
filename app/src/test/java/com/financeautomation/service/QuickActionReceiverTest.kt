package com.financeautomation.service

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class QuickActionReceiverTest {

    private lateinit var fakeRepository: FakeTransactionRepository

    private val pendingTxn = Transaction(
        id = "txn_quick_001",
        timestamp = 1730811600000L,
        dateSerial = 46331,
        timeSerial = 0.5,
        account = "SBI",
        merchant = "Cafe Coffee Day",
        tripTag = "",
        category = "Other",
        totalAmount = 220.0,
        companions = "Personal",
        myShare = 220.0,
        owedToMe = 0.0,
        status = TransactionStatus.SETTLED,
        sourcePackage = "com.google.android.apps.nbu.paisa.user",
        reviewState = ReviewState.NEEDS_REVIEW,
        syncState = SyncState.PENDING
    )

    @Before
    fun setUp() {
        fakeRepository = FakeTransactionRepository()
        runBlocking {
            fakeRepository.insertTransaction(pendingTxn)
        }
    }

    @Test
    fun testQuickCategorizeActionUpgradesReviewStateAndSetsCategory() = runBlocking {
        val outcome = QuickActionReceiver.handleAction(
            action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE,
            txnId = "txn_quick_001",
            category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
            repository = fakeRepository
        )

        assertTrue(outcome is QuickActionResult.Success)
        val success = outcome as QuickActionResult.Success
        assertEquals(ReviewState.CONFIRMED, success.transaction.reviewState)
        assertEquals(CategoryConstants.CATEGORY_FOOD_AND_DRINK, success.confirmedCategory)

        // Verify state in repository
        val stored = fakeRepository.getTransactionById("txn_quick_001")
        assertNotNull(stored)
        assertEquals(ReviewState.CONFIRMED, stored?.reviewState)
        assertEquals(CategoryConstants.CATEGORY_FOOD_AND_DRINK, stored?.category)
    }

    @Test
    fun testQuickCategorizeNormalizesPluralSavings() = runBlocking {
        val outcome = QuickActionReceiver.handleAction(
            action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE,
            txnId = "txn_quick_001",
            category = "Investment",
            repository = fakeRepository
        )

        assertTrue(outcome is QuickActionResult.Success)
        val success = outcome as QuickActionResult.Success
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, success.confirmedCategory)

        val stored = fakeRepository.getTransactionById("txn_quick_001")
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, stored?.category)
    }

    @Test
    fun testQuickCategorizeNormalizesSingularSavingToPluralSavings() = runBlocking {
        val outcome = QuickActionReceiver.handleAction(
            action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE,
            txnId = "txn_quick_001",
            category = "saving",
            repository = fakeRepository
        )

        assertTrue(outcome is QuickActionResult.Success)
        val success = outcome as QuickActionResult.Success
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, success.confirmedCategory)

        val stored = fakeRepository.getTransactionById("txn_quick_001")
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, stored?.category)
    }

    @Test
    fun testQuickConfirmActionConfirmsWithoutChangingCategory() = runBlocking {
        val outcome = QuickActionReceiver.handleAction(
            action = QuickActionReceiver.ACTION_QUICK_CONFIRM,
            txnId = "txn_quick_001",
            category = null,
            repository = fakeRepository
        )

        assertTrue(outcome is QuickActionResult.Success)
        val success = outcome as QuickActionResult.Success
        assertEquals(ReviewState.CONFIRMED, success.transaction.reviewState)
        assertEquals("Other", success.confirmedCategory)

        val stored = fakeRepository.getTransactionById("txn_quick_001")
        assertEquals(ReviewState.CONFIRMED, stored?.reviewState)
        assertEquals("Other", stored?.category)
    }

    @Test
    fun testNonExistentTransactionReturnsNotFound() = runBlocking {
        val outcome = QuickActionReceiver.handleAction(
            action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE,
            txnId = "txn_unknown_999",
            category = CategoryConstants.CATEGORY_GROCERIES,
            repository = fakeRepository
        )

        assertTrue(outcome is QuickActionResult.NotFound)
        assertEquals("txn_unknown_999", (outcome as QuickActionResult.NotFound).transactionId)
    }

    private class FakeTransactionRepository : TransactionRepository {
        private val storage = ConcurrentHashMap<String, Transaction>()
        private val flow = MutableStateFlow<List<Transaction>>(emptyList())

        override fun getTransactions(): Flow<List<Transaction>> = flow.asStateFlow()

        override fun getNeedsReview(): Flow<List<Transaction>> {
            val reviewFlow = MutableStateFlow<List<Transaction>>(emptyList())
            reviewFlow.value = storage.values.filter { it.reviewState == ReviewState.NEEDS_REVIEW }
            return reviewFlow
        }

        override suspend fun getTransactionById(id: String): Transaction? = storage[id]

        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            storage[transaction.id] = transaction
            flow.value = storage.values.toList()
            return Result.Success(Unit)
        }

        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            storage[transaction.id] = transaction
            flow.value = storage.values.toList()
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
            flow.value = storage.values.toList()
            return Result.Success(Unit)
        }

        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> {
            return storage.values.filter { Math.abs(it.totalAmount - amount) < 0.01 }
        }

        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
    }
}
