package com.financeautomation.presentation.overlay

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayActionDispatcherTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeResultPill : ResultPill {
        val messages = mutableListOf<String>()
        override fun show(message: String, transactionId: String?, onUndo: ((String) -> Unit)?) {
            messages.add(message)
        }
    }

    private class ActionRecordingTransactionRepo : TransactionRepository {
        var creditCalls = 0
        var payableCalls = 0
        var repaymentCalls = 0
        var shouldSucceed = true

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

        override suspend fun logCredit(
            settler: String,
            amount: Double,
            explicitRefs: List<String>,
            account: String,
            inflowType: String,
            reason: String,
            timestamp: Long
        ): Result<String> {
            creditCalls++
            return if (shouldSucceed) Result.Success("credit-$creditCalls") else Result.Error("failed")
        }

        override suspend fun logPayable(
            creditor: String,
            amount: Double,
            whatFor: String,
            timestamp: Long,
            category: String
        ): Result<String> {
            payableCalls++
            return if (shouldSucceed) Result.Success("payable-$payableCalls") else Result.Error("failed")
        }

        override suspend fun logRepayment(
            creditor: String,
            amount: Double,
            explicitRef: String?,
            timestamp: Long,
            account: String?,
            note: String?
        ): Result<String> {
            repaymentCalls++
            return if (shouldSucceed) Result.Success("repayment-$repaymentCalls") else Result.Error("failed")
        }

        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
        override suspend fun reconcileAllMonths(): Result<Int> = Result.Success(0)
        override suspend fun requeueYearForNewWorkbook(year: Int) {}
    }

    private class ActionRecordingTripTagRepo : TripTagRepository {
        override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> = flowOf(emptyList())
        override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> = emptyList()
        override suspend fun getActiveTripTags(): List<TripTag> = emptyList()
        override fun getActiveTag(): Flow<TripTag?> = flowOf(null)
        override suspend fun getActiveTagSync(): TripTag? = null
        override suspend fun getTagById(id: String): TripTag? = null
        override suspend fun getTagByName(name: String): TripTag? = null
        override suspend fun insertTripTag(name: String): TripTag = TripTag(id = "1", name = name)
        override suspend fun updateTripTag(tripTag: TripTag) {}
        override suspend fun setActiveTag(id: String?) {}
        override suspend fun archiveTripTag(id: String) {}
        override suspend fun unarchiveTripTag(id: String) {}
        override suspend fun deleteTripTag(id: String) {}
    }

    @Test
    fun confirmIncomeResetsSubmittingOnSuccessAllowingSubsequentConfirms() = runTest(testDispatcher) {
        val repo = ActionRecordingTransactionRepo()
        val pill = FakeResultPill()
        var dismissCount = 0

        val dispatcher = OverlayActionDispatcher(
            transactionRepository = repo,
            tripTagRepository = ActionRecordingTripTagRepo(),
            resultPill = pill,
            onDismiss = { dismissCount++ },
            mainScope = this,
            ioDispatcher = testDispatcher
        )

        dispatcher.confirmIncome(50.0, "Cash", "Income", "Alice", "Reason", emptyList())
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.creditCalls)
        assertEquals(1, dismissCount)
        assertEquals(1, pill.messages.size)

        // Second confirmation must not be ignored
        dispatcher.confirmIncome(75.0, "Cash", "Income", "Bob", "Reason", emptyList())
        testScheduler.advanceUntilIdle()

        assertEquals(2, repo.creditCalls)
        assertEquals(2, dismissCount)
        assertEquals(2, pill.messages.size)
    }

    @Test
    fun confirmPayableResetsSubmittingOnSuccessAllowingSubsequentConfirms() = runTest(testDispatcher) {
        val repo = ActionRecordingTransactionRepo()
        val pill = FakeResultPill()
        var dismissCount = 0

        val dispatcher = OverlayActionDispatcher(
            transactionRepository = repo,
            tripTagRepository = ActionRecordingTripTagRepo(),
            resultPill = pill,
            onDismiss = { dismissCount++ },
            mainScope = this,
            ioDispatcher = testDispatcher
        )

        dispatcher.confirmPayable(30.0, "Charlie", "Lunch")
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.payableCalls)
        assertEquals(1, dismissCount)

        // Second confirmation must proceed
        dispatcher.confirmPayable(40.0, "David", "Dinner")
        testScheduler.advanceUntilIdle()

        assertEquals(2, repo.payableCalls)
        assertEquals(2, dismissCount)
    }

    @Test
    fun confirmRepaidResetsSubmittingOnSuccessAllowingSubsequentConfirms() = runTest(testDispatcher) {
        val repo = ActionRecordingTransactionRepo()
        val pill = FakeResultPill()
        var dismissCount = 0

        val dispatcher = OverlayActionDispatcher(
            transactionRepository = repo,
            tripTagRepository = ActionRecordingTripTagRepo(),
            resultPill = pill,
            onDismiss = { dismissCount++ },
            mainScope = this,
            ioDispatcher = testDispatcher
        )

        dispatcher.confirmRepaid(25.0, "Eve", System.currentTimeMillis())
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.repaymentCalls)
        assertEquals(1, dismissCount)

        // Second confirmation must proceed
        dispatcher.confirmRepaid(35.0, "Frank", System.currentTimeMillis())
        testScheduler.advanceUntilIdle()

        assertEquals(2, repo.repaymentCalls)
        assertEquals(2, dismissCount)
    }
}
