package com.financeautomation.service

import androidx.work.ListenableWorker
import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.service.worker.MonthlyRolloverWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

class MonthlyRolloverWorkerTest {

    private class FakeTestTransactionRepository : TransactionRepository {
        var carryForwardCalledWith: String? = null
        var carryForwardResult: Result<Unit> = Result.Success(Unit)

        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> {
            carryForwardCalledWith = targetTab
            return carryForwardResult
        }

        override fun getTransactions(): Flow<List<Transaction>> = emptyFlow()
        override fun getNeedsReview(): Flow<List<Transaction>> = emptyFlow()
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
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
    }

    private val kolkataZone = ZoneId.of("Asia/Kolkata")

    @Test
    fun testDayBefore25_doesNotTriggerRollover() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(0)
        }

        val dateBefore25 = ZonedDateTime.of(2026, 11, 24, 23, 59, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateBefore25, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(null, repo.carryForwardCalledWith)
        assertFalse("Sync must not be called when rollover is skipped", syncCalled)
    }

    @Test
    fun testDay25_triggersRolloverForNextMonth() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }

        val dateDay25 = ZonedDateTime.of(2026, 11, 25, 0, 0, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDay25, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Dec26", repo.carryForwardCalledWith)
        assertTrue("Sync must be triggered after enqueuing carry forward", syncCalled)
    }

    @Test
    fun testDay2_carriesLastMonthIntoThisMonthForDebtsLoggedAfterTheLastRun() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }
        val result = MonthlyRolloverWorker.executeRollover(ZonedDateTime.of(2026, 12, 2, 9, 0, 0, 0, kolkataZone), repo, syncUseCase)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Dec26", repo.carryForwardCalledWith)
        assertTrue(syncCalled)
    }

    @Test
    fun testDay4_doesNothing() = runBlocking {
        val repo = FakeTestTransactionRepository()
        val result = MonthlyRolloverWorker.executeRollover(ZonedDateTime.of(2026, 12, 4, 9, 0, 0, 0, kolkataZone), repo) { Result.Success(0) }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(null, repo.carryForwardCalledWith)
    }

    @Test
    fun testDay31December_triggersRolloverForJanuaryNextYear() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }

        val dateDec31 = ZonedDateTime.of(2026, 12, 31, 15, 30, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDec31, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Jan27", repo.carryForwardCalledWith)
        assertTrue("Sync must be triggered", syncCalled)
    }

    @Test
    fun testDay25_carryForwardFailure_returnsRetry() = runBlocking {
        val repo = FakeTestTransactionRepository()
        repo.carryForwardResult = Result.Error(IOException("Queue insertion failed"))
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(0)
        }

        val dateDay25 = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDay25, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse("Sync should not run if carry forward failed", syncCalled)
    }

    @Test
    fun testDay25_syncTransportFailure_returnsRetry() = runBlocking {
        val repo = FakeTestTransactionRepository()
        val syncUseCase: suspend () -> Result<Int> = {
            Result.Error(IOException("Network unavailable"))
        }

        val dateDay25 = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDay25, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals("Dec26", repo.carryForwardCalledWith)
    }
}

