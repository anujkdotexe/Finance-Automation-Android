package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SplitMode
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExpenseUseCaseTest {

    private class FakeTransactionRepo : TransactionRepository {
        val inserted = mutableListOf<Transaction>()

        override fun getTransactions(): Flow<List<Transaction>> = emptyFlow()
        override fun getNeedsReview(): Flow<List<Transaction>> = emptyFlow()
        override suspend fun getTransactionById(id: String): Transaction? = inserted.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            inserted.add(transaction)
            return Result.Success(Unit)
        }
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
        override suspend fun reconcileAllMonths(): Result<Int> = Result.Success(0)
        override suspend fun requeueYearForNewWorkbook(year: Int) {}
    }

    @Test
    fun customSplit_withOwedAmountAndNoCompanions_returnsResultError() = runTest {
        val repo = FakeTransactionRepo()
        val useCase = LogExpenseUseCase(repo)

        // Custom split: total 100, user share 40 -> owed is 60 > 0, but no companions
        val result = useCase(
            amount = 100.0,
            merchant = "Dinner",
            category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
            splitMode = SplitMode.CUSTOM,
            customShare = 40.0,
            companions = emptyList()
        )

        assertTrue("Expected Result.Error when custom split has owed > 0 and no companions", result is Result.Error)
        val error = result as Result.Error
        assertTrue(
            "Expected descriptive error message, got: ${error.message}",
            error.message?.contains("companion", ignoreCase = true) == true
        )
        assertTrue("No transaction should be inserted into repo", repo.inserted.isEmpty())
    }

    @Test
    fun customSplit_withOwedAmountAndCompanions_succeeds() = runTest {
        val repo = FakeTransactionRepo()
        val useCase = LogExpenseUseCase(repo)

        val result = useCase(
            amount = 100.0,
            merchant = "Dinner",
            category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
            splitMode = SplitMode.CUSTOM,
            customShare = 40.0,
            companions = listOf("Alice")
        )

        assertTrue("Expected Result.Success when companions are present", result is Result.Success)
        assertEquals(1, repo.inserted.size)
        assertEquals("Alice", repo.inserted[0].companions)
        assertEquals(60.0, repo.inserted[0].owedToMe, 0.001)
    }

    @Test
    fun customSplit_withShareEqualToTotalAndNoCompanions_succeeds() = runTest {
        val repo = FakeTransactionRepo()
        val useCase = LogExpenseUseCase(repo)

        val result = useCase(
            amount = 100.0,
            merchant = "Dinner",
            category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
            splitMode = SplitMode.CUSTOM,
            customShare = 100.0,
            companions = emptyList()
        )

        assertTrue("Expected Result.Success when custom share equals total (owed is zero)", result is Result.Success)
        assertEquals(1, repo.inserted.size)
        assertEquals(0.0, repo.inserted[0].owedToMe, 0.001)
    }
}
