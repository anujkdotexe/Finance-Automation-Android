package com.financeautomation.domain

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.LogExpenseUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LogExpenseUseCaseTest {

    private lateinit var repository: FakeTransactionRepository
    private lateinit var useCase: LogExpenseUseCase

    @Before
    fun setUp() {
        repository = FakeTransactionRepository()
        useCase = LogExpenseUseCase(
            transactionRepository = repository,
            calculateSplitUseCase = CalculateSplitUseCase()
        )
    }

    @Test
    fun testLogExpenseSuccessJustMe() = runBlocking {
        val result = useCase(
            amount = 450.00,
            merchant = "Blue Tokai Coffee",
            category = "Food & Drink",
            account = CategoryConstants.ACCOUNT_SBI,
            splitMode = SplitMode.JUST_ME
        )

        assertTrue(result is Result.Success)
        val txn = (result as Result.Success).data
        assertTrue(txn.id.startsWith("txn_"))
        assertEquals(450.00, txn.totalAmount, 0.001)
        assertEquals(450.00, txn.myShare, 0.001)
        assertEquals(0.00, txn.owedToMe, 0.001)
        assertEquals(TransactionStatus.SETTLED, txn.status)
        assertEquals(ReviewState.CONFIRMED, txn.reviewState)
        assertEquals(SyncState.PENDING, txn.syncState)

        // Verify stored in repository
        val stored = repository.getTransactions().first()
        assertEquals(1, stored.size)
        assertEquals(txn.id, stored[0].id)
    }

    @Test
    fun testLogExpenseSplitEqually() = runBlocking {
        val result = useCase(
            amount = 900.00,
            merchant = "Dinner at Olive",
            category = "Food & Drink",
            splitMode = SplitMode.SPLIT_EQUALLY,
            companions = listOf("Alice", "Bob")
        )

        assertTrue(result is Result.Success)
        val txn = (result as Result.Success).data
        assertEquals(300.00, txn.myShare, 0.001)
        assertEquals(600.00, txn.owedToMe, 0.001)
        assertEquals(TransactionStatus.PENDING, txn.status)
        assertEquals("Alice, Bob", txn.companions)
    }

    @Test
    fun testLogExpenseNormalizesSavingsCategory() = runBlocking {
        val result = useCase(
            amount = 5000.00,
            merchant = "Zerodha AMC",
            category = "investment",
            splitMode = SplitMode.JUST_ME
        )

        assertTrue(result is Result.Success)
        val txn = (result as Result.Success).data
        assertEquals("Savings", txn.category)
    }

    @Test
    fun testLogExpenseRejectsSingularSaving() = runBlocking {
        val result = useCase(
            amount = 5000.00,
            merchant = "Zerodha AMC",
            category = "Saving",
            splitMode = SplitMode.JUST_ME
        )

        assertTrue(result is Result.Error)
    }

    @Test
    fun testLogExpenseRejectsZeroOrNegativeAmount() = runBlocking {
        val result = useCase(
            amount = -100.00,
            merchant = "Test",
            category = "Food & Drink"
        )
        assertTrue(result is Result.Error)
    }

    @Test
    fun testLogExpenseRejectsBlankMerchant() = runBlocking {
        val result = useCase(
            amount = 100.00,
            merchant = "   ",
            category = "Food & Drink"
        )
        assertTrue(result is Result.Error)
    }

    private class FakeTransactionRepository : TransactionRepository {
        private val list = mutableListOf<com.financeautomation.domain.model.Transaction>()
        private val flow = MutableStateFlow<List<com.financeautomation.domain.model.Transaction>>(emptyList())

        override fun getTransactions(): Flow<List<com.financeautomation.domain.model.Transaction>> = flow.asStateFlow()
        override fun getNeedsReview(): Flow<List<com.financeautomation.domain.model.Transaction>> = flow.asStateFlow()
        override suspend fun getTransactionById(id: String): com.financeautomation.domain.model.Transaction? =
            list.find { it.id == id }

        override suspend fun insertTransaction(transaction: com.financeautomation.domain.model.Transaction): Result<Unit> {
            list.add(transaction)
            flow.value = list.toList()
            return Result.Success(Unit)
        }

        override suspend fun updateTransaction(transaction: com.financeautomation.domain.model.Transaction): Result<Unit> {
            val idx = list.indexOfFirst { it.id == transaction.id }
            if (idx >= 0) {
                list[idx] = transaction
                flow.value = list.toList()
            }
            return Result.Success(Unit)
        }

        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<com.financeautomation.domain.model.Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
    }
}
