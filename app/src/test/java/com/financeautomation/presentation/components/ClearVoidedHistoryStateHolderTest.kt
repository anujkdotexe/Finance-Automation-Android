package com.financeautomation.presentation.components

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.model.VoidedHistory
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearVoidedHistoryStateHolderTest {

    private class FakeTxnRepo(
        val preview: VoidedHistory = VoidedHistory(transactions = 4, inflows = 1, skippedPending = 0),
        var clearResult: Result<VoidedHistory> = Result.Success(VoidedHistory(transactions = 4, inflows = 1, skippedPending = 0))
    ) : TransactionRepository {
        var clearCalls = 0
        var previewCalls = 0

        override suspend fun previewVoidedHistory(): VoidedHistory {
            previewCalls++
            return preview
        }

        override suspend fun clearVoidedHistory(): Result<VoidedHistory> {
            clearCalls++
            return clearResult
        }

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

    @Test
    fun initialState_hasNoLoadedDataAndNoErrors() {
        val repo = FakeTxnRepo()
        val holder = ClearVoidedHistoryStateHolder(repo)

        assertNull(holder.history)
        assertNull(holder.result)
        assertFalse(holder.failed)
        assertEquals(0, repo.previewCalls)
        assertEquals(0, repo.clearCalls)
    }

    @Test
    fun load_populatesHistoryCountsFromRepository() = runBlocking {
        val repo = FakeTxnRepo(preview = VoidedHistory(transactions = 3, inflows = 2, skippedPending = 1))
        val holder = ClearVoidedHistoryStateHolder(repo)

        val loaded = holder.load()

        assertEquals(1, repo.previewCalls)
        assertEquals(loaded, holder.history)
        assertEquals(3, holder.history?.transactions)
        assertEquals(2, holder.history?.inflows)
        assertEquals(1, holder.history?.skippedPending)
        assertNull(holder.result)
        assertFalse(holder.failed)
    }

    @Test
    fun confirmClear_success_populatesResultAndClearsFailedFlag() = runBlocking {
        val expected = VoidedHistory(transactions = 3, inflows = 2, skippedPending = 0)
        val repo = FakeTxnRepo(clearResult = Result.Success(expected))
        val holder = ClearVoidedHistoryStateHolder(repo)

        val success = holder.confirmClear()

        assertTrue(success)
        assertEquals(1, repo.clearCalls)
        assertEquals(expected, holder.result)
        assertFalse(holder.failed)
    }

    @Test
    fun confirmClear_failure_setsFailedFlagAndLeavesResultNull() = runBlocking {
        val repo = FakeTxnRepo(clearResult = Result.Error("Database locked"))
        val holder = ClearVoidedHistoryStateHolder(repo)

        val success = holder.confirmClear()

        assertFalse(success)
        assertEquals(1, repo.clearCalls)
        assertNull(holder.result)
        assertTrue(holder.failed)
    }
}
