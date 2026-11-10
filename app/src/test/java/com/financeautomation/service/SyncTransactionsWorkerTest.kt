package com.financeautomation.service

import androidx.work.ListenableWorker
import androidx.work.NetworkType
import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.service.worker.SettleWindowWorker
import com.financeautomation.service.worker.SyncTransactionsWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTransactionsWorkerTest {

    @Test
    fun testNetworkConstraintsRequireConnectedNetwork() {
        val constraints = SyncTransactionsWorker.createNetworkConstraints()
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
    }

    @Test
    fun testExecuteSyncSuccessReturnsSuccessResult() = runBlocking {
        val fakeRepo = object : TestTransactionRepository() {
            override suspend fun syncPendingTransactions(): Result<Int> {
                return Result.Success(5)
            }
        }

        val result = SyncTransactionsWorker.executeSync(fakeRepo, runAttemptCount = 0)
        assertTrue(result is ListenableWorker.Result.Success)
        val success = result as ListenableWorker.Result.Success
        assertEquals(5, success.outputData.getInt(SyncTransactionsWorker.KEY_SYNCED_COUNT, -1))
    }

    @Test
    fun testExecuteSyncErrorRetriesWhenAttemptCountUnderMax() = runBlocking {
        val fakeRepo = object : TestTransactionRepository() {
            override suspend fun syncPendingTransactions(): Result<Int> {
                return Result.Error(IllegalStateException("Network timeout"))
            }
        }

        val result = SyncTransactionsWorker.executeSync(fakeRepo, runAttemptCount = 1)
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun testExecuteSyncErrorFailsWhenRetriesExhausted() = runBlocking {
        val fakeRepo = object : TestTransactionRepository() {
            override suspend fun syncPendingTransactions(): Result<Int> {
                return Result.Error(IllegalStateException("Remote unauthorized"))
            }
        }

        val result = SyncTransactionsWorker.executeSync(fakeRepo, runAttemptCount = 3)
        assertTrue(result is ListenableWorker.Result.Failure)
        val failure = result as ListenableWorker.Result.Failure
        assertEquals("Remote unauthorized", failure.outputData.getString(SyncTransactionsWorker.KEY_ERROR_MESSAGE))
    }

    @Test
    fun testExecuteSyncExceptionRetriesWhenAttemptCountUnderMax() = runBlocking {
        val fakeRepo = object : TestTransactionRepository() {
            override suspend fun syncPendingTransactions(): Result<Int> {
                throw RuntimeException("Socket error")
            }
        }

        val result = SyncTransactionsWorker.executeSync(fakeRepo, runAttemptCount = 0)
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun testSettleWindowWorkerConstants() {
        assertEquals(90L, SettleWindowWorker.DEFAULT_SETTLE_WINDOW_SECONDS)
        assertEquals("settle_window_", SettleWindowWorker.WORK_NAME_PREFIX)
        assertEquals("key_transaction_id", SettleWindowWorker.KEY_TRANSACTION_ID)
    }

    private open class TestTransactionRepository : TransactionRepository {
        override fun getTransactions(): Flow<List<Transaction>> = emptyFlow()
        override fun getNeedsReview(): Flow<List<Transaction>> = emptyFlow()
        override suspend fun getTransactionById(id: String): Transaction? = null
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
    }
}
