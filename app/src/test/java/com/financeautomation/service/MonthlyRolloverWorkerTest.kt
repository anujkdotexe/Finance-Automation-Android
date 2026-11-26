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
        var carryForwardSourceTab: String? = null
        var carryForwardResult: Result<Unit> = Result.Success(Unit)
        val carries = mutableListOf<Pair<String, String?>>()

        /** Planning is the production repository's; only the queueing is recorded here. */
        val planner = com.financeautomation.testutil.SyncHarness()

        override suspend fun planMonthlyCarry(now: ZonedDateTime): List<Pair<String, String>> =
            planner.repo.planMonthlyCarry(now)

        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> {
            carries.add(targetTab to sourceTab)
            carryForwardCalledWith = targetTab
            carryForwardSourceTab = sourceTab
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
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long, category: String): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
        override suspend fun reconcileAllMonths(): Result<Int> = Result.Success(0)
        override suspend fun requeueYearForNewWorkbook(year: Int) {}
    }

    private val kolkataZone = ZoneId.of("Asia/Kolkata")

    @Test
    fun aRunQueuesTheUnfinishedYearStepFirstAndThenThisMonth() = runBlocking {
        val repo = FakeTestTransactionRepository()

        MonthlyRolloverWorker.executeRollover(
            ZonedDateTime.of(2026, 11, 25, 9, 0, 0, 0, kolkataZone), repo, { Result.Success(1) }
        )

        assertEquals(listOf<Pair<String, String?>>("Jan26" to "Dec25", "Nov26" to "Oct26"), repo.carries)
    }

    @Test
    fun onceThisMonthsCarryReachedTheSheetARunQueuesAndSyncsNothing() = runBlocking {
        val repo = FakeTestTransactionRepository()
        repo.planner.preferences[com.financeautomation.domain.repository.PreferencesRepository.KEY_CARRY_DONE_MONTH] = "2026-11"
        var syncCalled = false

        val result = MonthlyRolloverWorker.executeRollover(
            ZonedDateTime.of(2026, 11, 25, 9, 0, 0, 0, kolkataZone), repo, { syncCalled = true; Result.Success(0) }
        )

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(repo.carries.isEmpty())
        assertFalse(syncCalled)
    }

    @Test
    fun testDay25_doesNotCarryToNextMonth_carriesPreviousIntoCurrent() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }

        val dateDay25 = ZonedDateTime.of(2026, 11, 25, 0, 0, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDay25, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Nov26", repo.carryForwardCalledWith)
        assertEquals("Oct26", repo.carryForwardSourceTab)
        assertTrue("Sync must be triggered", syncCalled)
    }

    @Test
    fun testLastDayMonth_2359IST_doesNotCarryToNextMonth() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }

        val dateNov30 = ZonedDateTime.of(2026, 11, 30, 23, 59, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateNov30, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Nov26", repo.carryForwardCalledWith)
        assertEquals("Oct26", repo.carryForwardSourceTab)
        assertTrue(syncCalled)
    }

    @Test
    fun testDay1_0000IST_carriesIntoNewMonth() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }
        val dateDec1 = ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDec1, repo, syncUseCase)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Dec26", repo.carryForwardCalledWith)
        assertEquals("Nov26", repo.carryForwardSourceTab)
        assertTrue(syncCalled)
    }

    @Test
    fun testMissedDay1_firstRunOnDay5_carriesIntoNewMonth() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }
        val dateDec5 = ZonedDateTime.of(2026, 12, 5, 9, 0, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDec5, repo, syncUseCase)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Dec26", repo.carryForwardCalledWith)
        assertEquals("Nov26", repo.carryForwardSourceTab)
        assertTrue(syncCalled)
    }

    @Test
    fun testMissedDay1_firstRunOnDay20_carriesIntoNewMonth() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }
        val dateDec20 = ZonedDateTime.of(2026, 12, 20, 14, 0, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDec20, repo, syncUseCase)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Dec26", repo.carryForwardCalledWith)
        assertEquals("Nov26", repo.carryForwardSourceTab)
        assertTrue(syncCalled)
    }

    @Test
    fun testDecember31_doesNotCarryToJanuary_targetIsDecember() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }

        val dateDec31 = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateDec31, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Dec26", repo.carryForwardCalledWith)
        assertEquals("Nov26", repo.carryForwardSourceTab)
        assertTrue(syncCalled)
    }

    @Test
    fun testJanuary1_carriesDecemberIntoJanuaryNewWorkbook() = runBlocking {
        val repo = FakeTestTransactionRepository()
        var syncCalled = false
        val syncUseCase: suspend () -> Result<Int> = {
            syncCalled = true
            Result.Success(1)
        }

        val dateJan1 = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, kolkataZone)
        val result = MonthlyRolloverWorker.executeRollover(dateJan1, repo, syncUseCase)

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Jan27", repo.carryForwardCalledWith)
        assertEquals("Dec26", repo.carryForwardSourceTab)
        assertTrue(syncCalled)
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
        assertEquals("Nov26", repo.carryForwardCalledWith)
    }

    @Test
    fun testCarryForwardError_retriesUnderMaxRetriesAndFailsAtOrAboveCap() = runBlocking {
        val repo = FakeTestTransactionRepository()
        repo.carryForwardResult = Result.Error(IOException("Disk error"))
        val dateDay25 = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, kolkataZone)
        val dummySync: suspend () -> Result<Int> = { Result.Success(0) }

        for (attempt in 0..4) {
            val result = MonthlyRolloverWorker.executeRollover(dateDay25, repo, dummySync, runAttemptCount = attempt)
            assertEquals("Attempt $attempt below MAX_RETRIES must return retry", ListenableWorker.Result.retry(), result)
        }

        val cappedResult = MonthlyRolloverWorker.executeRollover(dateDay25, repo, dummySync, runAttemptCount = 5)
        assertEquals("Attempt 5 at MAX_RETRIES must return failure", ListenableWorker.Result.failure(), cappedResult)

        val beyondCappedResult = MonthlyRolloverWorker.executeRollover(dateDay25, repo, dummySync, runAttemptCount = 6)
        assertEquals("Attempt beyond MAX_RETRIES must return failure", ListenableWorker.Result.failure(), beyondCappedResult)
    }

    @Test
    fun testSyncError_retriesUnderMaxRetriesAndFailsAtOrAboveCap() = runBlocking {
        val repo = FakeTestTransactionRepository()
        val dateDay25 = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, kolkataZone)
        val failingSync: suspend () -> Result<Int> = { Result.Error(IOException("Sync failure")) }

        for (attempt in 0..4) {
            val result = MonthlyRolloverWorker.executeRollover(dateDay25, repo, failingSync, runAttemptCount = attempt)
            assertEquals("Attempt $attempt below MAX_RETRIES must return retry", ListenableWorker.Result.retry(), result)
        }

        val cappedResult = MonthlyRolloverWorker.executeRollover(dateDay25, repo, failingSync, runAttemptCount = 5)
        assertEquals("Attempt 5 at MAX_RETRIES must return failure", ListenableWorker.Result.failure(), cappedResult)

        val beyondCappedResult = MonthlyRolloverWorker.executeRollover(dateDay25, repo, failingSync, runAttemptCount = 6)
        assertEquals("Attempt beyond MAX_RETRIES must return failure", ListenableWorker.Result.failure(), beyondCappedResult)
    }

    @Test
    fun testSuccess_returnsSuccessRegardlessOfAttemptCount() = runBlocking {
        val repo = FakeTestTransactionRepository()
        val dateDay25 = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, kolkataZone)
        val successfulSync: suspend () -> Result<Int> = { Result.Success(1) }

        for (attempt in listOf(0, 4, 5, 10)) {
            val result = MonthlyRolloverWorker.executeRollover(dateDay25, repo, successfulSync, runAttemptCount = attempt)
            assertEquals("Successful run at attempt $attempt must return success", ListenableWorker.Result.success(), result)
        }
    }
}

