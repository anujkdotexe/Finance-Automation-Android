package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.utils.RegexPatterns
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.presentation.viewmodel.QuickLogViewModel
import com.financeautomation.testutil.FakePendingCaptureDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectionDetectionAndCreditCaptureTest {

    private val testDispatcher = StandardTestDispatcher()
    private val parseNotificationUseCase = ParseNotificationUseCase()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sbiAtmMentioningCashNotReceivedIsClassifiedAsDebit() {
        val text = "Dear Customer, Rs.1000 withdrawn at SBI ATM S1BB##### from A/cX#### on 16Aug25 Transaction Number 2717. Available Balance Rs.7922.00. If not withdrawn by you, forward this SMS to ########## / call ########## or ########## to block your card. Call ##### if cash not received."
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "JX-ATMSBI-S",
            text = text
        )

        assertTrue("Expected Success, got $result", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(1000.0, success.transaction.totalAmount, 0.01)
        assertTrue(success.isDebit)
        assertFalse(success.isCredit)
    }

    @Test
    fun debitSmsContainingCreditedToMerchantIsClassifiedAsDebit() {
        val text = "Your A/c ending 1234 is debited for Rs 450.00 on 12-Nov-26 and credited to merchant Swiggy. Avl bal Rs 5400.00."
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "AX-HDFCBK-S",
            text = text
        )

        assertTrue("Expected Success, got $result", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(450.0, success.transaction.totalAmount, 0.01)
        assertTrue(success.isDebit)
        assertFalse(success.isCredit)
    }

    @Test
    fun consentSubstringDoesNotTriggerSuccessSent() {
        val text = "Dear Customer, by giving your consent for e-mandate on your Card XX4321, no deduction has been made yet."
        val wording = ParseNotificationUseCase.classifyWording(text)
        assertEquals(WordingClass.UNKNOWN, wording)

        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "VM-CANBNK-S",
            text = text
        )
        assertTrue("Expected Dropped, got $result", result is NotificationParseResult.Dropped)
    }

    @Test
    fun realCreditSmsIsClassifiedAsCredit() {
        val text = "Dear Customer, your A/c XX7890 has been credited with Rs 2,500.00 on 16-Nov-26 by UPI/Ref 123456789. Avl Bal Rs 15,200.00."
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "AX-ICICIB-S",
            text = text
        )

        assertTrue("Expected Success, got $result", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(2500.0, success.transaction.totalAmount, 0.01)
        assertTrue(success.isCredit)
        assertFalse(success.isDebit)
    }

    private class TestTxnRepo : TransactionRepository {
        val inserted = mutableListOf<Transaction>()
        override fun getTransactions(): Flow<List<Transaction>> = flowOf(inserted)
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
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
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("credit_1")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long, category: String): Result<String> = Result.Success("payable_1")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("repay_1")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
        override suspend fun reconcileAllMonths(): Result<Int> = Result.Success(0)
        override suspend fun requeueYearForNewWorkbook(year: Int) {}
    }

    private class TestPeopleRepo : PeopleRepository {
        override fun getCompanions(): Flow<List<com.financeautomation.domain.model.Companion>> = flowOf(emptyList())
        override suspend fun getAllCompanions(): List<com.financeautomation.domain.model.Companion> = emptyList()
        override suspend fun getCompanionById(id: String): com.financeautomation.domain.model.Companion? = null
        override suspend fun getCompanionByName(name: String): com.financeautomation.domain.model.Companion? = null
        override suspend fun insertCompanion(companion: com.financeautomation.domain.model.Companion) {}
        override suspend fun updateCompanion(companion: com.financeautomation.domain.model.Companion) {}
        override suspend fun updateCompanionName(id: String, newName: String) {}
        override suspend fun deleteCompanion(companion: com.financeautomation.domain.model.Companion) {}
        override suspend fun deleteCompanionById(id: String) {}
    }

    private class TestCatRepo : CategoryRepository {
        override fun getCategories(includeArchived: Boolean): Flow<List<com.financeautomation.domain.model.Category>> =
            flowOf(listOf(com.financeautomation.domain.model.Category("1", "Food & Drink")))
        override suspend fun getAllCategories(includeArchived: Boolean): List<com.financeautomation.domain.model.Category> = emptyList()
        override suspend fun getActiveCategories(): List<com.financeautomation.domain.model.Category> = emptyList()
        override suspend fun getCategoryById(id: String): com.financeautomation.domain.model.Category? = null
        override suspend fun getCategoryByName(name: String): com.financeautomation.domain.model.Category? = null
        override suspend fun insertCategory(category: com.financeautomation.domain.model.Category) {}
        override suspend fun updateCategory(category: com.financeautomation.domain.model.Category) {}
        override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean) {}
        override suspend fun archiveCategory(id: String) {}
        override suspend fun unarchiveCategory(id: String) {}
        override suspend fun deleteCategory(id: String) {}
        override suspend fun reorderCategories(categoryIds: List<String>) {}
    }

    private class TestAccRepo : AccountRepository {
        override fun getAccounts(includeArchived: Boolean): Flow<List<com.financeautomation.domain.model.Account>> =
            flowOf(listOf(com.financeautomation.domain.model.Account("1", "Cash", isDefault = true)))
        override suspend fun getAllAccounts(includeArchived: Boolean): List<com.financeautomation.domain.model.Account> =
            listOf(com.financeautomation.domain.model.Account("1", "Cash", isDefault = true))
        override suspend fun getAccountById(id: String): com.financeautomation.domain.model.Account? = null
        override suspend fun getAccountByName(name: String): com.financeautomation.domain.model.Account? = null
        override suspend fun getDefaultAccount(): com.financeautomation.domain.model.Account? =
            com.financeautomation.domain.model.Account("1", "Cash", isDefault = true)
        override suspend fun insertAccount(account: com.financeautomation.domain.model.Account) {}
        override suspend fun updateAccount(account: com.financeautomation.domain.model.Account) {}
        override suspend fun setDefaultAccount(id: String) {}
        override suspend fun archiveAccount(id: String) {}
        override suspend fun unarchiveAccount(id: String) {}
        override suspend fun deleteAccount(id: String) {}
        override suspend fun reorderAccounts(accountIds: List<String>) {}
        override fun getAccountTransactionCounts(from: Long, to: Long): Flow<Map<String, Int>> = flowOf(emptyMap())
    }

    private class TestTripRepo : TripTagRepository {
        override fun getTripTags(includeArchived: Boolean): Flow<List<com.financeautomation.domain.model.TripTag>> = flowOf(emptyList())
        override suspend fun getAllTripTags(includeArchived: Boolean): List<com.financeautomation.domain.model.TripTag> = emptyList()
        override suspend fun getActiveTripTags(): List<com.financeautomation.domain.model.TripTag> = emptyList()
        override fun getActiveTag(): Flow<com.financeautomation.domain.model.TripTag?> = flowOf(null)
        override suspend fun getActiveTagSync(): com.financeautomation.domain.model.TripTag? = null
        override suspend fun getTagById(id: String): com.financeautomation.domain.model.TripTag? = null
        override suspend fun getTagByName(name: String): com.financeautomation.domain.model.TripTag? = null
        override suspend fun insertTripTag(name: String): com.financeautomation.domain.model.TripTag = com.financeautomation.domain.model.TripTag("1", name)
        override suspend fun updateTripTag(tripTag: com.financeautomation.domain.model.TripTag) {}
        override suspend fun setActiveTag(id: String?) {}
        override suspend fun archiveTripTag(id: String) {}
        override suspend fun unarchiveTripTag(id: String) {}
        override suspend fun deleteTripTag(id: String) {}
    }

    private class TestSyncDao : com.financeautomation.data.local.dao.SyncQueueDao {
        override fun getPendingQueueFlow(): Flow<List<com.financeautomation.data.local.entity.SyncQueueEntity>> = flowOf(emptyList())
        override fun getAllQueueFlow(): Flow<List<com.financeautomation.data.local.entity.SyncQueueEntity>> = flowOf(emptyList())
        override fun getFailedQueueFlow(): Flow<List<com.financeautomation.data.local.entity.SyncQueueEntity>> = flowOf(emptyList())
        override suspend fun insert(item: com.financeautomation.data.local.entity.SyncQueueEntity): Long = 1L
        override suspend fun update(item: com.financeautomation.data.local.entity.SyncQueueEntity) {}
        override suspend fun delete(item: com.financeautomation.data.local.entity.SyncQueueEntity) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun getById(id: Long): com.financeautomation.data.local.entity.SyncQueueEntity? = null
        override suspend fun deleteByTransactionId(transactionId: String) {}
        override suspend fun getNextPending(maxRetries: Int): com.financeautomation.data.local.entity.SyncQueueEntity? = null
        override suspend fun getAllPending(maxRetries: Int): List<com.financeautomation.data.local.entity.SyncQueueEntity> = emptyList()
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<com.financeautomation.data.local.entity.SyncQueueEntity> = emptyList()
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {}
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {}
        override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {}
        override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {}
        override suspend fun resetQueueItem(id: Long) {}
        override suspend fun recoverRetryableFailedItems(): Int = 0
        override suspend fun countPendingOperations(opType: String): Int = 0
        override suspend fun updatePlanJson(id: Long, planJson: String?) {}
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
        override suspend fun getActiveTransactionIds(): List<String> = emptyList()
        override suspend fun deletePendingForTransactions(transactionIds: List<String>) {}
    }

    @Test
    fun creditCaptureLoadedInQuickLogCannotBeSavedAsExpense() = runTest {
        val tRepo = TestTxnRepo()
        val pDao = FakePendingCaptureDao()
        val qDao = TestSyncDao()
        val now = System.currentTimeMillis()

        val creditCapture = PendingCaptureEntity(
            id = "cap_credit_1",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 1500.0,
            payee = "Refund Issuer",
            inferredAccount = "Cash",
            timestamp = now,
            kind = PendingCaptureEntity.KIND_CREDIT,
            state = PendingCaptureEntity.STATE_WAITING,
            notificationKey = null,
            contentHash = null,
            updatedAt = now
        )
        pDao.insert(creditCapture)

        val vm = QuickLogViewModel(
            transactionRepository = tRepo,
            peopleRepository = TestPeopleRepo(),
            categoryRepository = TestCatRepo(),
            accountRepository = TestAccRepo(),
            tripTagRepository = TestTripRepo(),
            pendingCaptureDaoProvider = { pDao },
            syncQueueDaoProvider = { qDao },
            ioDispatcher = testDispatcher
        )

        vm.loadCapture("cap_credit_1")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(PendingCaptureEntity.KIND_CREDIT, state.kind)
        assertEquals(1500.0, state.amount, 0.01)
        assertFalse("isSaveEnabled should be false for credit captures", state.isSaveEnabled)

        var savedCalled = false
        vm.onConfirmAndSave { _, _ -> savedCalled = true }
        advanceUntilIdle()

        assertFalse("onSaved must not be called for credit capture save attempt", savedCalled)
        assertEquals(0, tRepo.inserted.size)
        assertFalse(vm.uiState.value.isSaved)
        assertTrue(vm.uiState.value.errorMessage != null)
    }

    @Test
    fun quickActionCaptureClaimRejectsCreditCaptures() = runTest {
        val pDao = FakePendingCaptureDao()
        val now = System.currentTimeMillis()
        val creditCapture = PendingCaptureEntity(
            id = "cap_credit_qa",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 500.0,
            payee = "Sender",
            inferredAccount = "Cash",
            timestamp = now,
            kind = PendingCaptureEntity.KIND_CREDIT,
            state = PendingCaptureEntity.STATE_WAITING,
            notificationKey = null,
            contentHash = null,
            updatedAt = now
        )
        pDao.insert(creditCapture)

        var executed = false
        val outcome = com.financeautomation.service.CaptureClaim.logOnce(pDao, "cap_credit_qa") { capture ->
            if (capture.kind.equals(PendingCaptureEntity.KIND_CREDIT, ignoreCase = true)) {
                return@logOnce false
            }
            executed = true
            true
        }

        assertEquals("Claim should return FAILED when block returns false", com.financeautomation.service.CaptureClaim.Outcome.FAILED, outcome)
        assertFalse("Execution block must reject credit capture", executed)
        val loaded = pDao.getById("cap_credit_qa")
        assertEquals("Capture must remain waiting", PendingCaptureEntity.STATE_WAITING, loaded?.state)
    }
}
