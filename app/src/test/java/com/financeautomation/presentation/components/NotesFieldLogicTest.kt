package com.financeautomation.presentation.components

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.InputPolicy
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.presentation.viewmodel.QuickLogTransactionHelper
import com.financeautomation.presentation.viewmodel.QuickLogUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesFieldLogicTest {

    private class FakeTransactionRepo : TransactionRepository {
        val inserted = mutableListOf<Transaction>()
        val updated = mutableListOf<Transaction>()
        var existingTxn: Transaction? = null

        override fun getTransactions(): Flow<List<Transaction>> = emptyFlow()
        override fun getNeedsReview(): Flow<List<Transaction>> = emptyFlow()
        override suspend fun getTransactionById(id: String): Transaction? =
            if (id == existingTxn?.id) existingTxn else inserted.find { it.id == id }

        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            inserted.add(transaction)
            return Result.Success(Unit)
        }

        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            updated.add(transaction)
            return Result.Success(Unit)
        }

        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> = Result.Success(Unit)
        override suspend fun undoRecentSave(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long, category: String): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
        override suspend fun reconcileAllMonths(): Result<Int> = Result.Success(0)
        override suspend fun requeueYearForNewWorkbook(year: Int) {}
    }

    private class FakePeopleRepo : PeopleRepository {
        override fun getCompanions(): Flow<List<Companion>> = emptyFlow()
        override suspend fun getAllCompanions(): List<Companion> = emptyList()
        override suspend fun getCompanionById(id: String): Companion? = null
        override suspend fun getCompanionByName(name: String): Companion? = null
        override suspend fun insertCompanion(companion: Companion) {}
        override suspend fun updateCompanion(companion: Companion) {}
        override suspend fun updateCompanionName(id: String, newName: String) {}
        override suspend fun deleteCompanion(companion: Companion) {}
        override suspend fun deleteCompanionById(id: String) {}
    }

    @Test
    fun cleanNoteFiltersBidiAndControlCharactersPreservingNewlinesAndTabs() {
        val raw = "  Dinner with team\nLocation: Cafe\tTable 4\u202E\u0000\u001F  "
        val clean = InputPolicy.cleanNote(raw)
        assertEquals("Dinner with team\nLocation: Cafe\tTable 4", clean)
    }

    @Test
    fun cleanNoteWithMaxLenEnforcesMaxLength120() {
        val longText = "A".repeat(150)
        val clean = InputPolicy.cleanNote(longText).take(InputPolicy.MAX_NOTE_LENGTH)
        assertEquals(120, clean.length)
        assertEquals(InputPolicy.MAX_NOTE_LENGTH, clean.length)
        val validation = InputPolicy.validateNote(longText)
        assertTrue("Text longer than max length must fail validation", validation is InputPolicy.ValidationResult.Error)
        assertEquals(InputPolicy.InputError.TOO_LONG, (validation as InputPolicy.ValidationResult.Error).reason)
    }

    @Test
    fun quickLogUiStateDefaultNoteIsEmpty() {
        val state = QuickLogUiState()
        assertEquals("", state.note)
    }

    @Test
    fun logExpenseUseCasePersistsNote() = runTest {
        val repo = FakeTransactionRepo()
        val useCase = LogExpenseUseCase(repo, CalculateSplitUseCase())
        val res = useCase(
            amount = 250.0,
            merchant = "Coffee",
            category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
            note = "Met with client"
        )

        assertTrue(res is Result.Success)
        assertEquals("Met with client", repo.inserted.firstOrNull()?.note)
    }

    @Test
    fun quickLogTransactionHelperLoadsAndUpdatesNote() = runTest {
        val existingTxn = Transaction(
            id = "txn_1",
            timestamp = 1000L,
            dateSerial = 45000,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Lunch",
            tripTag = "",
            category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
            totalAmount = 500.0,
            companions = "Personal",
            myShare = 500.0,
            owedToMe = 0.0,
            status = TransactionStatus.PERSONAL,
            note = "Client lunch meeting"
        )

        val txnRepo = FakeTransactionRepo().apply { this.existingTxn = existingTxn }
        val peopleRepo = FakePeopleRepo()

        // 1. Load transaction into state
        val loadedState = QuickLogTransactionHelper.loadExistingTransaction(
            txnId = "txn_1",
            currentState = QuickLogUiState(),
            transactionRepository = txnRepo,
            peopleRepository = peopleRepo
        )

        assertEquals("Client lunch meeting", loadedState?.note)

        // 2. Update note and save
        val modifiedState = loadedState!!.copy(note = "Updated note with project details")
        val updateRes = QuickLogTransactionHelper.updateExisting(
            txnId = "txn_1",
            state = modifiedState,
            transactionRepository = txnRepo
        )

        assertTrue(updateRes is Result.Success)
        assertEquals("Updated note with project details", txnRepo.updated.firstOrNull()?.note)
    }

    @Test
    fun subscriptionPreservesNote() {
        val sub = Subscription(
            id = "sub_1",
            name = "Netflix",
            amount = 649.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = 20000L,
            account = "HDFC",
            category = "Subscriptions",
            remind = true,
            active = true,
            note = "Family 4K plan shared with parents"
        )

        assertEquals("Family 4K plan shared with parents", sub.note)
    }
}
