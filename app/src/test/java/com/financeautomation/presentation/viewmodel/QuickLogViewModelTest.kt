package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class QuickLogViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeTransactionRepo : TransactionRepository {
        val inserted = mutableListOf<Transaction>()
        override fun getTransactions(): Flow<List<Transaction>> = flowOf(inserted)
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = inserted.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            inserted.add(transaction)
            return Result.Success(Unit)
        }
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            val idx = inserted.indexOfFirst { it.id == transaction.id }
            if (idx >= 0) inserted[idx] = transaction
            return Result.Success(Unit)
        }
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
    }

    private class FakePeopleRepo : PeopleRepository {
        override fun getCompanions(): Flow<List<Companion>> = flowOf(listOf(Companion("1", "Alice"), Companion("2", "Bob")))
        override suspend fun getAllCompanions(): List<Companion> = listOf(Companion("1", "Alice"), Companion("2", "Bob"))
        override suspend fun getCompanionById(id: String): Companion? = null
        override suspend fun getCompanionByName(name: String): Companion? = null
        override suspend fun insertCompanion(companion: Companion) {}
        override suspend fun updateCompanion(companion: Companion) {}
        override suspend fun updateCompanionName(id: String, newName: String) {}
        override suspend fun deleteCompanion(companion: Companion) {}
        override suspend fun deleteCompanionById(id: String) {}
    }

    private class FakeCategoryRepo : CategoryRepository {
        override fun getCategories(includeArchived: Boolean): Flow<List<Category>> = flowOf(listOf(Category("1", "Food & Drink")))
        override suspend fun getAllCategories(includeArchived: Boolean): List<Category> = emptyList()
        override suspend fun getActiveCategories(): List<Category> = emptyList()
        override suspend fun getCategoryById(id: String): Category? = null
        override suspend fun getCategoryByName(name: String): Category? = null
        override suspend fun insertCategory(category: Category) {}
        override suspend fun updateCategory(category: Category) {}
        override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean) {}
        override suspend fun archiveCategory(id: String) {}
        override suspend fun unarchiveCategory(id: String) {}
        override suspend fun deleteCategory(id: String) {}
        override suspend fun reorderCategories(categoryIds: List<String>) {}
    }

    private class FakeAccountRepo : AccountRepository {
        override fun getAccounts(includeArchived: Boolean): Flow<List<Account>> = flowOf(listOf(Account("1", "Cash", isDefault = true)))
        override suspend fun getAllAccounts(includeArchived: Boolean): List<Account> = listOf(Account("1", "Cash", isDefault = true))
        override suspend fun getAccountById(id: String): Account? = null
        override suspend fun getAccountByName(name: String): Account? = null
        override suspend fun getDefaultAccount(): Account? = Account("1", "Cash", isDefault = true)
        override suspend fun insertAccount(account: Account) {}
        override suspend fun updateAccount(account: Account) {}
        override suspend fun setDefaultAccount(id: String) {}
        override suspend fun archiveAccount(id: String) {}
        override suspend fun unarchiveAccount(id: String) {}
        override suspend fun deleteAccount(id: String) {}
        override suspend fun reorderAccounts(accountIds: List<String>) {}
        override fun getAccountTransactionCounts(from: Long, to: Long): Flow<Map<String, Int>> = flowOf(emptyMap())
    }

    private class FakeTripTagRepo : TripTagRepository {
        override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> = flowOf(emptyList())
        override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> = emptyList()
        override suspend fun getActiveTripTags(): List<TripTag> = emptyList()
        override fun getActiveTag(): Flow<TripTag?> = flowOf(null)
        override suspend fun getActiveTagSync(): TripTag? = null
        override suspend fun getTagById(id: String): TripTag? = null
        override suspend fun getTagByName(name: String): TripTag? = null
        override suspend fun insertTripTag(name: String): TripTag = TripTag("1", name)
        override suspend fun updateTripTag(tripTag: TripTag) {}
        override suspend fun setActiveTag(id: String?) {}
        override suspend fun archiveTripTag(id: String) {}
        override suspend fun unarchiveTripTag(id: String) {}
        override suspend fun deleteTripTag(id: String) {}
    }

    private class FakePendingCaptureDao : PendingCaptureDao {
        val captures = mutableMapOf<String, PendingCaptureEntity>()
        override suspend fun insert(capture: PendingCaptureEntity) { captures[capture.id] = capture }
        override suspend fun update(capture: PendingCaptureEntity) { captures[capture.id] = capture }
        override suspend fun getById(id: String): PendingCaptureEntity? = captures[id]
        override suspend fun getByNotificationKey(key: String): PendingCaptureEntity? = captures.values.lastOrNull { it.notificationKey == key }
        override suspend fun getByContentHash(hash: String, since: Long): PendingCaptureEntity? =
            captures.values.lastOrNull { it.contentHash == hash && it.timestamp >= since }
        override suspend fun getByState(state: String): List<PendingCaptureEntity> = captures.values.filter { it.state == state }
        override fun getWaitingCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(emptyList())
        override fun getNeedsReviewCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(emptyList())
        override fun getNewAccountCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(emptyList())
        override suspend fun getWaitingCaptures(): List<PendingCaptureEntity> = emptyList()
        override suspend fun getExpiredWaitingCaptures(cutoffTime: Long): List<PendingCaptureEntity> = emptyList()
        override suspend fun getAwaitingSuccessCaptures(): List<PendingCaptureEntity> = emptyList()
        override suspend fun findSimilarByAmountAndTime(amount: Double, minTime: Long, maxTime: Long): List<PendingCaptureEntity> = emptyList()
        override suspend fun updateState(id: String, state: String, updatedAt: Long) {}
        override suspend fun resetShownToWaiting(updatedAt: Long): Int = 0
        override suspend fun deleteById(id: String) { captures.remove(id) }
        override suspend fun pruneOldCompletedCaptures(cutoffTime: Long) {}
    }

    private class FakeTestSyncQueueDao : SyncQueueDao {
        val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
        private val idGen = AtomicLong(1)
        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override suspend fun insert(item: SyncQueueEntity): Long {
            val id = if (item.id == 0L) idGen.getAndIncrement() else item.id
            queue[id] = item.copy(id = id)
            return id
        }
        override suspend fun update(item: SyncQueueEntity) { queue[item.id] = item }
        override suspend fun delete(item: SyncQueueEntity) { queue.remove(item.id) }
        override suspend fun deleteById(id: Long) { queue.remove(id) }
        override suspend fun getById(id: Long): SyncQueueEntity? = queue[id]
        override suspend fun deleteByTransactionId(transactionId: String) {}
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = null
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {}
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount) }
        }
        override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount, lastErrorKind = kind, lastErrorDetail = detail) }
        }
        override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement, lastErrorKind = kind, lastErrorDetail = detail) }
        }
        override suspend fun resetQueueItem(id: Long) {
            queue[id]?.let { queue[id] = it.copy(status = SyncQueueEntity.STATUS_PENDING, retryCount = 0, lastErrorKind = null, lastErrorDetail = null) }
        }
        override suspend fun recoverRetryableFailedItems(): Int = 0
        override suspend fun countPendingOperations(opType: String): Int = 0
        override suspend fun updatePlanJson(id: Long, planJson: String?) {}
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
    }

    @Test
    fun testOnConfirmAndSave_logsNewExpenseAndMarksCaptureDone() = runTest {
        val tRepo = FakeTransactionRepo()
        val pDao = FakePendingCaptureDao()
        val qDao = FakeTestSyncQueueDao()
        val capture = PendingCaptureEntity(
            id = "cap_save_test",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 120.0,
            payee = "Tea Stall",
            inferredAccount = "Cash",
            timestamp = 1730000000000L,
            kind = "DEBIT",
            flags = "",
            state = "WAITING",
            notificationKey = "key_save",
            contentHash = "hash_save",
            updatedAt = 1730000000000L
        )
        pDao.insert(capture)

        val vm = QuickLogViewModel(
            transactionRepository = tRepo,
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { pDao },
            syncQueueDaoProvider = { qDao },
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        vm.loadCapture("cap_save_test")
        advanceUntilIdle()

        var savedMessage: UiText? = null
        vm.onConfirmAndSave { msg -> savedMessage = msg }
        advanceUntilIdle()

        assertNotNull(savedMessage)
        assertEquals(R.string.quicklog_msg_saved_will_sync, savedMessage?.resId)
        assertEquals(listOf("120.00"), savedMessage?.args)
        assertEquals(1, tRepo.inserted.size)
        val insertedTxn = tRepo.inserted.first()
        assertEquals(120.0, insertedTxn.totalAmount, 0.001)
        assertEquals("Tea Stall", insertedTxn.merchant)
        // Timestamp must propagate from capture
        assertEquals(1730000000000L, insertedTxn.timestamp)
    }

    @Test
    fun testOnConfirmAndSave_rejectsFutureCaptureTimestamp() = runTest {
        val tRepo = FakeTransactionRepo()
        val pDao = FakePendingCaptureDao()
        val future = System.currentTimeMillis() + 3 * 24 * 3_600_000L
        pDao.insert(
            PendingCaptureEntity(
                id = "cap_future", sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 50.0, payee = "Tea Stall", inferredAccount = "Cash", timestamp = future,
                kind = "DEBIT", flags = "", state = "WAITING", notificationKey = "k_f",
                contentHash = "h_f", updatedAt = future
            )
        )
        val vm = QuickLogViewModel(
            transactionRepository = tRepo,
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { pDao },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()
        vm.loadCapture("cap_future")
        advanceUntilIdle()
        var saved = false
        vm.onConfirmAndSave { saved = true }
        advanceUntilIdle()
        assertTrue(!saved)
        assertEquals(0, tRepo.inserted.size)
        assertEquals(R.string.quicklog_err_future_date, vm.uiState.value.errorMessage?.resId)
    }

    @Test
    fun testSetManualMode_doesNotSetNeedsAmount() = runTest {
        val vm = QuickLogViewModel(
            transactionRepository = FakeTransactionRepo(),
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()
        vm.setManualMode()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(null, state.captureId)
        assertEquals("manual", state.sourcePackage)
        assertEquals(false, state.isNeedsAmount)
    }

    @Test
    fun testSetManualMode_keepsWhatWasTypedWhenTheScreenIsRecreated() = runTest {
        val vm = QuickLogViewModel(
            transactionRepository = FakeTransactionRepo(),
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()
        vm.setManualMode()
        advanceUntilIdle()
        vm.onAmountChanged("250")
        vm.onPayeeChanged("Chai Point")
        advanceUntilIdle()

        // rotation, dark mode or font scale: the activity is recreated and asks for manual mode again
        vm.setManualMode()
        advanceUntilIdle()

        assertEquals("250", vm.uiState.value.amountText)
        assertEquals(250.0, vm.uiState.value.amount, 0.0)
        assertEquals("Chai Point", vm.uiState.value.payee)
    }

    @Test
    fun testSetManualMode_startsEmptyAfterTheLastEntryWasSaved() = runTest {
        val vm = QuickLogViewModel(
            transactionRepository = FakeTransactionRepo(),
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()
        vm.setManualMode()
        vm.onAmountChanged("99")
        vm.onPayeeChanged("Old")
        vm.onDismiss()
        advanceUntilIdle()
        vm.setManualMode()
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.amountText)
        assertEquals("", vm.uiState.value.payee)
    }

    @Test
    fun testEditExpense_restoresAllFields_preselectsCategoryAndAccount() = runTest {
        val tRepo = FakeTransactionRepo()
        val peopleRepo = FakePeopleRepo()
        val catRepo = FakeCategoryRepo()
        val accRepo = FakeAccountRepo()
        val pDao = FakePendingCaptureDao()
        val qDao = FakeTestSyncQueueDao()

        val companion = Companion(id = "comp_alice", name = "Alice")
        peopleRepo.insertCompanion(companion)

        val existingTimestamp = 1731000000000L
        val txn = Transaction(
            id = "txn_edit_test",
            timestamp = existingTimestamp,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "UPI",
            merchant = "Starbucks",
            category = "Food & Drink",
            totalAmount = 900.0,
            companions = "Alice",
            myShare = 450.0,
            owedToMe = 450.0,
            status = TransactionStatus.PENDING
        )
        tRepo.insertTransaction(txn)

        val vm = QuickLogViewModel(
            transactionRepository = tRepo,
            peopleRepository = peopleRepo,
            categoryRepository = catRepo,
            accountRepository = accRepo,
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { pDao },
            syncQueueDaoProvider = { qDao },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.loadTransaction("txn_edit_test")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("txn_edit_test", state.transactionId)
        assertEquals("Starbucks", state.payee)
        assertEquals(900.0, state.amount, 0.001)
        assertEquals("900", state.amountText)
        assertEquals("Food & Drink", state.selectedCategory)
        assertEquals("UPI", state.selectedAccount)
        assertEquals(existingTimestamp, state.captureTimestamp)
        assertEquals(SplitMode.SPLIT_EQUALLY, state.splitMode)
        assertEquals(450.0, state.myShare, 0.001)
        assertEquals(450.0, state.owedToMe, 0.001)
        assertEquals(setOf("1"), state.selectedCompanionIds)
    }

    @Test
    fun testOnDateSelected_updatesCaptureTimestampAndCapsFuture() = runTest {
        val vm = QuickLogViewModel(
            transactionRepository = FakeTransactionRepo(),
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val pastTs = System.currentTimeMillis() - 86400000L
        vm.onDateSelected(pastTs)
        assertEquals(pastTs, vm.uiState.value.captureTimestamp)

        val farFutureTs = System.currentTimeMillis() + 86400000L * 30
        vm.onDateSelected(farFutureTs)
        assertTrue(vm.uiState.value.captureTimestamp!! <= System.currentTimeMillis())
    }

    @Test
    fun testOnPayeeChanged_remembersAndPreselectsLastAccountAndCategory() = runTest {
        val tRepo = FakeTransactionRepo()
        val accRepo = FakeAccountRepo()
        val catRepo = FakeCategoryRepo()
        val ts = System.currentTimeMillis()

        // Insert historical transaction with specific account and category
        tRepo.insertTransaction(
            Transaction(
                id = "txn_prev",
                timestamp = ts - 100000L,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "SBI",
                merchant = "Blue Tokai Coffee",
                category = "Food & Drink",
                totalAmount = 350.0,
                companions = "Personal",
                myShare = 350.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            )
        )

        val vm = QuickLogViewModel(
            transactionRepository = tRepo,
            peopleRepository = FakePeopleRepo(),
            categoryRepository = catRepo,
            accountRepository = accRepo,
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        // Set manual mode and type payee
        vm.setManualMode()
        vm.onPayeeChanged("Blue Tokai Coffee")
        advanceUntilIdle()

        // Account and category should be preselected from past transaction
        assertEquals("SBI", vm.uiState.value.selectedAccount)
        assertEquals("Food & Drink", vm.uiState.value.selectedCategory)
    }

    @Test
    fun testCalculatorAmounts_evaluatesExpressionInAmountField() = runTest {
        val vm = QuickLogViewModel(
            transactionRepository = FakeTransactionRepo(),
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.onAmountChanged("120+80")
        assertEquals("120+80", vm.uiState.value.amountText)
        assertEquals(200.0, vm.uiState.value.amount, 0.001)

        vm.onAmountChanged("450/3")
        assertEquals("450/3", vm.uiState.value.amountText)
        assertEquals(150.0, vm.uiState.value.amount, 0.001)

        vm.onAmountChanged("99.5*2")
        assertEquals("99.5*2", vm.uiState.value.amountText)
        assertEquals(199.0, vm.uiState.value.amount, 0.001)

        vm.onAmountChanged("120+")
        assertEquals("120+", vm.uiState.value.amountText)
        assertEquals(0.0, vm.uiState.value.amount, 0.001)
    }

    @Test
    fun testCalculatorAmounts_evaluatesExpressionInCustomShareField() = runTest {
        val vm = QuickLogViewModel(
            transactionRepository = FakeTransactionRepo(),
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.onAmountChanged("500")
        vm.onSplitModeChanged(SplitMode.CUSTOM)
        vm.onCustomShareChanged("150+50")

        assertEquals("150+50", vm.uiState.value.customShareText)
        assertEquals(200.0, vm.uiState.value.customShare!!, 0.001)
        assertEquals(300.0, vm.uiState.value.owedToMe, 0.001)
    }

    @Test
    fun testCustomSplit_withOwedAmountAndNoCompanions_saveIsDisabled() = runTest {
        val repo = FakeTransactionRepo()
        val peopleRepo = FakePeopleRepo()
        val vm = QuickLogViewModel(
            transactionRepository = repo,
            peopleRepository = peopleRepo,
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.onAmountChanged("500")
        vm.onSplitModeChanged(SplitMode.CUSTOM)
        vm.onCustomShareChanged("200") // Owed = 300, no companion selected

        assertEquals(false, vm.uiState.value.isSaveEnabled)
        vm.onConfirmAndSave { }
        advanceUntilIdle()
        assertTrue("No transaction should be saved when custom split owes money without companions", repo.inserted.isEmpty())

        // Now select a companion -> Save becomes enabled
        vm.onCompanionToggled("1")
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.isSaveEnabled)

        // Deselect companion, but set custom share equal to total amount (owed = 0) -> Save becomes enabled
        vm.onCompanionToggled("1")
        vm.onCustomShareChanged("500")
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.isSaveEnabled)
    }

    @Test
    fun onVoid_invokesRepoVoidAndMarksVoided() = runTest {
        val txnRepo = FakeTransactionRepo()
        val txn = Transaction(
            id = "txn_to_void",
            timestamp = System.currentTimeMillis(),
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Test Shop",
            category = "Food & Drink",
            totalAmount = 250.0,
            companions = "Personal",
            myShare = 250.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        )
        txnRepo.insertTransaction(txn)

        val vm = QuickLogViewModel(
            transactionRepository = txnRepo,
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            tripTagRepository = FakeTripTagRepo(),
            accountRepository = FakeAccountRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.loadTransaction("txn_to_void")
        advanceUntilIdle()
        assertEquals("txn_to_void", vm.uiState.value.transactionId)

        var callbackCalled = false
        vm.onVoid { callbackCalled = true }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isVoided)
        assertTrue(callbackCalled)
    }

    @Test
    fun onAddNewCompanion_recalculatesSplitImmediatelyWithNewCompanion() = runTest {
        val vm = QuickLogViewModel(
            transactionRepository = FakeTransactionRepo(),
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            tripTagRepository = FakeTripTagRepo(),
            accountRepository = FakeAccountRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.onAmountChanged("600")
        vm.onSplitModeChanged(SplitMode.SPLIT_EQUALLY)
        // With 0 companions selected in SPLIT_EQUALLY, it's 1 person (just me = 600)
        assertEquals(600.0, vm.uiState.value.myShare, 0.01)

        // Add a companion -> now 1 companion selected (Alice + me = 2 people, 300 each)
        vm.onCompanionToggled("1")
        advanceUntilIdle()
        assertEquals(300.0, vm.uiState.value.myShare, 0.01)
        assertEquals(300.0, vm.uiState.value.owedToMe, 0.01)

        // Add a brand new companion -> immediately 2 companions selected + me = 3 people, 200 each
        vm.onAddNewCompanion("Charlie")
        advanceUntilIdle()
        assertEquals(200.0, vm.uiState.value.myShare, 0.01)
        assertEquals(400.0, vm.uiState.value.owedToMe, 0.01)
        assertTrue(vm.uiState.value.availableCompanions.any { it.name == "Charlie" })
    }

    @Test
    fun updateExisting_retainsTripTagAndUpdatesPendingStatus() = runTest {
        val txnRepo = FakeTransactionRepo()
        val original = Transaction(
            id = "txn_edit_test",
            timestamp = System.currentTimeMillis() - 10000,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Old Merchant",
            category = "Food & Drink",
            totalAmount = 300.0,
            companions = "Personal",
            myShare = 300.0,
            owedToMe = 0.0,
            status = TransactionStatus.PERSONAL,
            tripTag = "Goa Trip"
        )
        txnRepo.insertTransaction(original)

        val vm = QuickLogViewModel(
            transactionRepository = txnRepo,
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            tripTagRepository = FakeTripTagRepo(),
            accountRepository = FakeAccountRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.loadTransaction("txn_edit_test")
        advanceUntilIdle()

        // Change split to split equally with Alice
        vm.onSplitModeChanged(SplitMode.SPLIT_EQUALLY)
        vm.onCompanionToggled("1")
        advanceUntilIdle()

        var saved = false
        vm.onConfirmAndSave { _, _ -> saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        val updated = txnRepo.getTransactionById("txn_edit_test")
        assertNotNull(updated)
        // Should retain original trip tag, not blank or overwritten
        assertEquals("Goa Trip", updated!!.tripTag)
        // Should update status to PENDING
        assertEquals(TransactionStatus.PENDING, updated.status)
        assertEquals(150.0, updated.myShare, 0.01)
        assertEquals(150.0, updated.owedToMe, 0.01)
    }

    @Test
    fun updateExisting_onSettledRow_preservesFinancialFieldsAndTripTag() = runTest {
        val txnRepo = FakeTransactionRepo()
        val settled = Transaction(
            id = "txn_settled",
            timestamp = System.currentTimeMillis() - 20000,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Settled Shop",
            category = "Food & Drink",
            totalAmount = 500.0,
            companions = "Alice",
            myShare = 250.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED,
            tripTag = "Paris 2026"
        )
        txnRepo.insertTransaction(settled)

        val vm = QuickLogViewModel(
            transactionRepository = txnRepo,
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            tripTagRepository = FakeTripTagRepo(),
            accountRepository = FakeAccountRepo(),
            pendingCaptureDaoProvider = { FakePendingCaptureDao() },
            syncQueueDaoProvider = { FakeTestSyncQueueDao() },
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.loadTransaction("txn_settled")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isFinancialLocked)

        vm.onPayeeChanged("New Merchant Name")
        vm.onCategorySelected("Shopping")

        var saved = false
        vm.onConfirmAndSave { _, _ -> saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        val updated = txnRepo.getTransactionById("txn_settled")
        assertNotNull(updated)
        assertEquals("New Merchant Name", updated!!.merchant)
        assertEquals("Shopping", updated.category)
        // Financials must remain untouched
        assertEquals(500.0, updated.totalAmount, 0.01)
        assertEquals(250.0, updated.myShare, 0.01)
        assertEquals(0.0, updated.owedToMe, 0.01)
        assertEquals(TransactionStatus.SETTLED, updated.status)
        assertEquals("Alice", updated.companions)
        assertEquals("Paris 2026", updated.tripTag)
    }
}
