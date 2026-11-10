package com.financeautomation.domain

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.SyncTransactionsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncTransactionsUseCaseTest {

    private lateinit var repository: FakeTransactionRepository
    private lateinit var useCase: SyncTransactionsUseCase

    @Before
    fun setUp() {
        repository = FakeTransactionRepository()
        useCase = SyncTransactionsUseCase(repository)
    }

    @Test
    fun testSyncPendingTransactionsFlushesAllPending() = runBlocking {
        val t1 = Transaction(
            id = "txn_sync_1",
            timestamp = System.currentTimeMillis(),
            dateSerial = 46331,
            timeSerial = 0.5,
            account = CategoryConstants.ACCOUNT_SBI,
            merchant = "Coffee",
            category = "Food & Drink",
            totalAmount = 150.0,
            companions = "Personal",
            myShare = 150.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED,
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING
        )
        val t2 = Transaction(
            id = "txn_sync_2",
            timestamp = System.currentTimeMillis(),
            dateSerial = 46331,
            timeSerial = 0.6,
            account = CategoryConstants.ACCOUNT_CANARA,
            merchant = "Groceries",
            category = "Groceries",
            totalAmount = 800.0,
            companions = "Personal",
            myShare = 800.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED,
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING
        )

        repository.insertTransaction(t1)
        repository.insertTransaction(t2)

        val syncResult = useCase()
        assertTrue(syncResult is Result.Success)
        assertEquals(2, (syncResult as Result.Success).data)

        // Verify state is SYNCED
        val all = repository.getTransactions().first()
        assertTrue(all.all { it.syncState == SyncState.SYNCED })
    }

    private class FakeTransactionRepository : TransactionRepository {
        private val list = mutableListOf<Transaction>()
        private val flow = MutableStateFlow<List<Transaction>>(emptyList())

        override fun getTransactions(): Flow<List<Transaction>> = flow.asStateFlow()
        override fun getNeedsReview(): Flow<List<Transaction>> = flow.asStateFlow()
        override suspend fun getTransactionById(id: String): Transaction? =
            list.find { it.id == id }

        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            list.add(transaction)
            flow.value = list.toList()
            return Result.Success(Unit)
        }

        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            val idx = list.indexOfFirst { it.id == transaction.id }
            if (idx >= 0) {
                list[idx] = transaction
                flow.value = list.toList()
            }
            return Result.Success(Unit)
        }

        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()

        override suspend fun syncPendingTransactions(): Result<Int> {
            var count = 0
            val updated = list.map {
                if (it.syncState == SyncState.PENDING && it.reviewState == ReviewState.CONFIRMED) {
                    count++
                    it.copy(syncState = SyncState.SYNCED)
                } else {
                    it
                }
            }
            list.clear()
            list.addAll(updated)
            flow.value = list.toList()
            return Result.Success(count)
        }
    }
}
