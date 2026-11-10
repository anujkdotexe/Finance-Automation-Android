package com.financeautomation.presentation

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.presentation.viewmodel.ExpenseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ExpenseViewModelTest {

    private lateinit var fakeTransactionRepository: FakeTransactionRepository
    private lateinit var fakePeopleRepository: FakePeopleRepository
    private lateinit var calculateSplitUseCase: CalculateSplitUseCase
    private lateinit var logExpenseUseCase: LogExpenseUseCase
    private lateinit var viewModel: ExpenseViewModel

    private val sampleTxn = Transaction(
        id = "txn_test_100",
        timestamp = 1730811600000L,
        dateSerial = 46331,
        timeSerial = 0.5,
        account = "SBI",
        merchant = "Chai Point",
        tripTag = "",
        category = "Food & Drink",
        totalAmount = 300.0,
        companions = "Personal",
        myShare = 300.0,
        owedToMe = 0.0,
        status = TransactionStatus.SETTLED,
        sourcePackage = "com.google.android.apps.nbu.paisa.user",
        reviewState = ReviewState.NEEDS_REVIEW,
        syncState = SyncState.PENDING
    )

    @Before
    fun setUp() = runBlocking {
        fakeTransactionRepository = FakeTransactionRepository()
        fakePeopleRepository = FakePeopleRepository()
        calculateSplitUseCase = CalculateSplitUseCase()
        logExpenseUseCase = LogExpenseUseCase(fakeTransactionRepository, calculateSplitUseCase)

        fakeTransactionRepository.insertTransaction(sampleTxn)
        fakePeopleRepository.insertCompanion(Companion(id = "c1", name = "Aman"))
        fakePeopleRepository.insertCompanion(Companion(id = "c2", name = "Rahul"))

        viewModel = ExpenseViewModel(
            transactionRepository = fakeTransactionRepository,
            peopleRepository = fakePeopleRepository,
            calculateSplitUseCase = calculateSplitUseCase,
            logExpenseUseCase = logExpenseUseCase,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testInitialStateHasDefaultCategoryAndJustMeMode() {
        val state = viewModel.uiState.value
        assertEquals(CategoryConstants.CATEGORY_FOOD_AND_DRINK, state.selectedCategory)
        assertEquals(SplitMode.JUST_ME, state.splitMode)
        assertFalse(state.isSaving)
        assertFalse(state.isSaved)
        assertFalse(state.isVoided)
    }

    @Test
    fun testLoadExistingTransactionPopulatesFields() = runBlocking {
        viewModel.loadTransaction("txn_test_100")
        val state = viewModel.uiState.value

        assertEquals("txn_test_100", state.transactionId)
        assertEquals("Chai Point", state.merchant)
        assertEquals(300.0, state.amount, 0.01)
        assertEquals(300.0, state.myShare, 0.01)
        assertEquals(0.0, state.owedToMe, 0.01)
        assertEquals(TransactionStatus.SETTLED, state.status)
        assertEquals(SplitMode.JUST_ME, state.splitMode)
    }

    @Test
    fun testAmountChangedRecalculatesSplitJustMe() {
        viewModel.onAmountChanged("600")
        val state = viewModel.uiState.value

        assertEquals(600.0, state.amount, 0.01)
        assertEquals(600.0, state.myShare, 0.01)
        assertEquals(0.0, state.owedToMe, 0.01)
        assertEquals(TransactionStatus.SETTLED, state.status)
    }

    @Test
    fun testSplitEquallyWithCompanions() = runBlocking {
        viewModel.onAmountChanged("900")
        viewModel.onSplitModeChanged(SplitMode.SPLIT_EQUALLY)
        viewModel.onCompanionToggled("c1")
        viewModel.onCompanionToggled("c2")

        val state = viewModel.uiState.value
        // 900 divided across 2 friends + user = 3 heads -> 300 each
        assertEquals(300.0, state.myShare, 0.01)
        assertEquals(600.0, state.owedToMe, 0.01)
        assertEquals(TransactionStatus.PENDING, state.status)
    }

    @Test
    fun testCustomSplitMath() {
        viewModel.onAmountChanged("1000")
        viewModel.onSplitModeChanged(SplitMode.CUSTOM)
        viewModel.onCustomShareChanged("400")

        val state = viewModel.uiState.value
        assertEquals(400.0, state.myShare, 0.01)
        assertEquals(600.0, state.owedToMe, 0.01)
        assertEquals(TransactionStatus.PENDING, state.status)
    }

    @Test
    fun testPluralSavingsNormalization() {
        viewModel.onCategorySelected("Investment")
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, viewModel.uiState.value.selectedCategory)

        viewModel.onCategorySelected("SIP")
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, viewModel.uiState.value.selectedCategory)
    }

    @Test
    fun testSingularSavingNormalizedToPluralSavings() {
        viewModel.onCategorySelected("saving")
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, viewModel.uiState.value.selectedCategory)
    }

    @Test
    fun testAddNewCompanionInsertsAndSelects() = runBlocking {
        viewModel.onAmountChanged("500")
        viewModel.onSplitModeChanged(SplitMode.SPLIT_EQUALLY)
        viewModel.onAddNewCompanion("Vikram")

        val companion = fakePeopleRepository.getCompanionByName("Vikram")
        assertNotNull(companion)

        val state = viewModel.uiState.value
        assertTrue(state.selectedCompanionIds.contains(companion?.id))
        assertEquals(250.0, state.myShare, 0.01)
        assertEquals(250.0, state.owedToMe, 0.01)
        assertEquals(TransactionStatus.PENDING, state.status)
    }

    @Test
    fun testConfirmAndSaveExistingTransactionUpgradesToConfirmed() = runBlocking {
        viewModel.loadTransaction("txn_test_100")
        viewModel.onCategorySelected(CategoryConstants.CATEGORY_GROCERIES)
        viewModel.onConfirmAndSave()

        val state = viewModel.uiState.value
        assertTrue(state.isSaved)

        val updated = fakeTransactionRepository.getTransactionById("txn_test_100")
        assertNotNull(updated)
        assertEquals(ReviewState.CONFIRMED, updated?.reviewState)
        assertEquals(CategoryConstants.CATEGORY_GROCERIES, updated?.category)
    }

    @Test
    fun testConfirmAndSaveNewManualExpense() = runBlocking {
        viewModel.onAmountChanged("450")
        viewModel.onMerchantChanged("Supermarket")
        viewModel.onCategorySelected(CategoryConstants.CATEGORY_GROCERIES)
        viewModel.onConfirmAndSave()

        val state = viewModel.uiState.value
        assertTrue(state.isSaved)

        val txns = fakeTransactionRepository.getTransactions().first()
        val created = txns.find { it.merchant == "Supermarket" }
        assertNotNull(created)
        assertEquals(450.0, created?.totalAmount ?: 0.0, 0.01)
        assertEquals(ReviewState.CONFIRMED, created?.reviewState)
    }

    @Test
    fun testVoidTransaction() = runBlocking {
        viewModel.loadTransaction("txn_test_100")
        viewModel.onVoid()

        val state = viewModel.uiState.value
        assertTrue(state.isVoided)

        val voided = fakeTransactionRepository.getTransactionById("txn_test_100")
        assertEquals(TransactionStatus.VOID, voided?.status)
        assertEquals(0.0, voided?.myShare ?: 1.0, 0.01)
        assertEquals(0.0, voided?.owedToMe ?: 1.0, 0.01)
    }

    @Test
    fun testValidationRejectsZeroAmountAndBlankMerchant() {
        viewModel.onAmountChanged("0")
        viewModel.onMerchantChanged("   ")
        viewModel.onConfirmAndSave()

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun testLoadTransactionAfterSaveClearsIsSavedAndIsVoided() = runBlocking {
        viewModel.loadTransaction("txn_test_100")
        viewModel.onConfirmAndSave()
        assertTrue(viewModel.uiState.value.isSaved)

        viewModel.loadTransaction("txn_test_100")
        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertFalse(state.isVoided)
    }

    @Test
    fun testLoadTransactionAfterVoidClearsIsSavedAndIsVoided() = runBlocking {
        viewModel.loadTransaction("txn_test_100")
        viewModel.onVoid()
        assertTrue(viewModel.uiState.value.isVoided)

        viewModel.loadTransaction("txn_test_100")
        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertFalse(state.isVoided)
    }

    // In-Memory Test Doubles
    private class FakeTransactionRepository : TransactionRepository {
        private val storage = ConcurrentHashMap<String, Transaction>()
        private val allFlow = MutableStateFlow<List<Transaction>>(emptyList())
        private val needsReviewFlow = MutableStateFlow<List<Transaction>>(emptyList())

        private fun updateFlows() {
            val list = storage.values.toList()
            allFlow.value = list
            needsReviewFlow.value = list.filter { it.reviewState == ReviewState.NEEDS_REVIEW }
        }

        override fun getTransactions(): Flow<List<Transaction>> = allFlow.asStateFlow()
        override fun getNeedsReview(): Flow<List<Transaction>> = needsReviewFlow.asStateFlow()

        override suspend fun getTransactionById(id: String): Transaction? = storage[id]

        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            storage[transaction.id] = transaction
            updateFlows()
            return Result.Success(Unit)
        }

        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            storage[transaction.id] = transaction
            updateFlows()
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
            updateFlows()
            return Result.Success(Unit)
        }

        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> =
            storage.values.filter { Math.abs(it.totalAmount - amount) < 0.01 }

        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
    }

    private class FakePeopleRepository : PeopleRepository {
        private val storage = ConcurrentHashMap<String, Companion>()
        private val flow = MutableStateFlow<List<Companion>>(emptyList())

        private fun updateFlow() {
            flow.value = storage.values.toList()
        }

        override fun getCompanions(): Flow<List<Companion>> = flow.asStateFlow()
        override suspend fun getAllCompanions(): List<Companion> = storage.values.toList()
        override suspend fun getCompanionById(id: String): Companion? = storage[id]
        override suspend fun getCompanionByName(name: String): Companion? =
            storage.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

        override suspend fun insertCompanion(companion: Companion) {
            storage[companion.id] = companion
            updateFlow()
        }

        override suspend fun updateCompanion(companion: Companion) {
            storage[companion.id] = companion
            updateFlow()
        }

        override suspend fun updateCompanionName(id: String, newName: String) {
            val existing = storage[id]
            if (existing != null) {
                storage[id] = existing.copy(name = newName)
                updateFlow()
            }
        }

        override suspend fun deleteCompanion(companion: Companion) {
            storage.remove(companion.id)
            updateFlow()
        }

        override suspend fun deleteCompanionById(id: String) {
            storage.remove(id)
            updateFlow()
        }
    }
}
