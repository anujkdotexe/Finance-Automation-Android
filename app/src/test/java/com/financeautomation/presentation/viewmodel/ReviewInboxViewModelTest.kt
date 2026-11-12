package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewInboxViewModelTest {

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
        val txns = MutableStateFlow<List<Transaction>>(emptyList())
        var updateCalls = 0
        override fun getTransactions(): Flow<List<Transaction>> = txns
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(txns.value.filter { it.reviewState == ReviewState.NEEDS_REVIEW })
        override suspend fun getTransactionById(id: String): Transaction? = txns.value.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            txns.value = txns.value + transaction
            return Result.Success(Unit)
        }
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            updateCalls++
            txns.value = txns.value.map { if (it.id == transaction.id) transaction else it }
            return Result.Success(Unit)
        }
        override suspend fun voidTransaction(id: String): Result<Unit> {
            txns.value = txns.value.map { if (it.id == id) it.copy(status = TransactionStatus.VOID) else it }
            return Result.Success(Unit)
        }
        override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> {
            txns.value = txns.value.map {
                if (it.id != id) it
                else if (voidIt) it.copy(
                    status = TransactionStatus.VOID,
                    merchant = if (it.merchant.startsWith("[Void] ")) it.merchant else "[Void] ${it.merchant}",
                    reviewState = ReviewState.CONFIRMED,
                    syncState = com.financeautomation.core.model.SyncState.VOID
                ) else it.copy(
                    reviewState = ReviewState.CONFIRMED,
                    syncState = com.financeautomation.core.model.SyncState.SYNCED
                )
            }
            return Result.Success(Unit)
        }
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
    }

    private class FakeSyncQueueDao : SyncQueueDao {
        val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
        private val idGen = AtomicLong(1)
        private val queueFlow = MutableStateFlow<List<SyncQueueEntity>>(emptyList())

        fun refreshFlow() { queueFlow.value = queue.values.toList() }

        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(emptyList())
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = queueFlow
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> =
            queueFlow.map { list -> list.filter { it.status == SyncQueueEntity.STATUS_FAILED || it.retryCount >= 8 } }
        override suspend fun insert(item: SyncQueueEntity): Long {
            val id = if (item.id == 0L) idGen.getAndIncrement() else item.id
            queue[id] = item.copy(id = id)
            refreshFlow()
            return id
        }
        override suspend fun update(item: SyncQueueEntity) { queue[item.id] = item; refreshFlow() }
        override suspend fun delete(item: SyncQueueEntity) { queue.remove(item.id); refreshFlow() }
        override suspend fun deleteById(id: Long) { queue.remove(id); refreshFlow() }
        override suspend fun getById(id: Long): SyncQueueEntity? = queue[id]
        override suspend fun deleteByTransactionId(transactionId: String) {}
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = null
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {}
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount); refreshFlow() }
        }
        override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount, lastErrorKind = kind, lastErrorDetail = detail); refreshFlow() }
        }
        override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement, lastErrorKind = kind, lastErrorDetail = detail); refreshFlow() }
        }
        override suspend fun resetQueueItem(id: Long) {
            queue[id]?.let { queue[id] = it.copy(status = SyncQueueEntity.STATUS_PENDING, retryCount = 0, lastErrorKind = null, lastErrorDetail = null); refreshFlow() }
        }
        override suspend fun countPendingOperations(opType: String): Int = 0
        override suspend fun updatePlanJson(id: Long, planJson: String?) {}
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
    }

    private class FakePendingCaptureDao : PendingCaptureDao {
        val captures = MutableStateFlow<List<PendingCaptureEntity>>(emptyList())
        override fun getWaitingCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(emptyList())
        override fun getNeedsReviewCapturesFlow(): Flow<List<PendingCaptureEntity>> = captures
        override fun getNewAccountCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(emptyList())
        override suspend fun getWaitingCaptures(): List<PendingCaptureEntity> = emptyList()
        override suspend fun getById(id: String): PendingCaptureEntity? = captures.value.find { it.id == id }
        override suspend fun getByNotificationKey(key: String): PendingCaptureEntity? = null
        override suspend fun getByState(state: String): List<PendingCaptureEntity> = emptyList()
        override suspend fun getExpiredWaitingCaptures(cutoffTime: Long): List<PendingCaptureEntity> = emptyList()
        override suspend fun getAwaitingSuccessCaptures(): List<PendingCaptureEntity> = emptyList()
        override suspend fun findSimilarByAmountAndTime(amount: Double, minTime: Long, maxTime: Long): List<PendingCaptureEntity> = emptyList()
        override suspend fun insert(capture: PendingCaptureEntity) { captures.value = captures.value + capture }
        override suspend fun update(capture: PendingCaptureEntity) { captures.value = captures.value.map { if (it.id == capture.id) capture else it } }
        override suspend fun updateState(id: String, state: String, updatedAt: Long) {
            captures.value = captures.value.map { if (it.id == id) it.copy(state = state, updatedAt = updatedAt) else it }
        }
        override suspend fun resetShownToWaiting(updatedAt: Long): Int = 0
        override suspend fun deleteById(id: String) { captures.value = captures.value.filter { it.id != id } }
        override suspend fun pruneOldCompletedCaptures(cutoffTime: Long) {}
    }

    private class FakeAccountRepo : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        override fun getAccounts(includeArchived: Boolean): Flow<List<Account>> = accounts
        override suspend fun getAllAccounts(includeArchived: Boolean): List<Account> = accounts.value
        override suspend fun getAccountById(id: String): Account? = accounts.value.find { it.id == id }
        override suspend fun getAccountByName(name: String): Account? = accounts.value.find { it.name.equals(name, ignoreCase = true) }
        override suspend fun getDefaultAccount(): Account? = accounts.value.find { it.isDefault }
        override suspend fun insertAccount(account: Account) { accounts.value = accounts.value + account }
        override suspend fun updateAccount(account: Account) { accounts.value = accounts.value.map { if (it.id == account.id) account else it } }
        override suspend fun setDefaultAccount(id: String) {
            accounts.value = accounts.value.map { it.copy(isDefault = it.id == id) }
        }
        override suspend fun archiveAccount(id: String) {}
        override suspend fun unarchiveAccount(id: String) {}
        override suspend fun deleteAccount(id: String) {}
        override suspend fun reorderAccounts(accountIds: List<String>) {}
    }

    @Test
    fun testConfirmAllEligible_skipsIneligibleTransactions() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cDao = FakePendingCaptureDao()
        val aRepo = FakeAccountRepo()

        val eligibleTxn = Transaction(
            id = "txn_eligible",
            timestamp = System.currentTimeMillis(),
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 250.0,
            companions = "Personal",
            myShare = 250.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            reviewState = ReviewState.NEEDS_REVIEW,
            isPossibleDuplicate = false
        )

        val duplicateTxn = eligibleTxn.copy(
            id = "txn_dup",
            isPossibleDuplicate = true
        )

        val unknownAccountTxn = eligibleTxn.copy(
            id = "txn_unknown_acc",
            account = ""
        )

        val blankCategoryTxn = eligibleTxn.copy(
            id = "txn_blank_cat",
            category = ""
        )

        val zeroAmountTxn = eligibleTxn.copy(
            id = "txn_zero_amount",
            totalAmount = 0.0
        )

        val voidTxn = eligibleTxn.copy(
            id = "txn_void",
            status = TransactionStatus.VOID
        )

        tRepo.insertTransaction(eligibleTxn)
        tRepo.insertTransaction(duplicateTxn)
        tRepo.insertTransaction(unknownAccountTxn)
        tRepo.insertTransaction(blankCategoryTxn)
        tRepo.insertTransaction(zeroAmountTxn)
        tRepo.insertTransaction(voidTxn)

        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = cDao,
            accountRepository = aRepo,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val confirmedCount = vm.confirmAllEligible()
        advanceUntilIdle()

        assertEquals(1, confirmedCount)
        assertEquals(ReviewState.CONFIRMED, tRepo.getTransactionById("txn_eligible")?.reviewState)
        assertEquals(ReviewState.NEEDS_REVIEW, tRepo.getTransactionById("txn_dup")?.reviewState)
        assertEquals(ReviewState.NEEDS_REVIEW, tRepo.getTransactionById("txn_unknown_acc")?.reviewState)
        assertEquals(ReviewState.NEEDS_REVIEW, tRepo.getTransactionById("txn_blank_cat")?.reviewState)
        assertEquals(ReviewState.NEEDS_REVIEW, tRepo.getTransactionById("txn_zero_amount")?.reviewState)
    }

    @Test
    fun testConfirmAllEligible_skipsTransactionWithFailedQueueItem() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val base = Transaction(
            id = "txn_ok", timestamp = System.currentTimeMillis(), dateSerial = 46330, timeSerial = 0.5,
            account = "HDFC", merchant = "Cafe", category = "Food & Drink", totalAmount = 250.0,
            companions = "Personal", myShare = 250.0, owedToMe = 0.0,
            status = TransactionStatus.PENDING, reviewState = ReviewState.NEEDS_REVIEW
        )
        tRepo.insertTransaction(base)
        tRepo.insertTransaction(base.copy(id = "txn_failed"))
        qDao.insert(
            SyncQueueEntity(
                id = 30L, transactionId = "txn_failed", operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "{}", status = SyncQueueEntity.STATUS_FAILED, retryCount = 8
            )
        )
        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo, directSyncQueueDao = qDao,
            directPendingCaptureDao = FakePendingCaptureDao(), accountRepository = FakeAccountRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val confirmed = vm.confirmAllEligible()
        advanceUntilIdle()

        assertEquals(1, confirmed)
        assertEquals(ReviewState.CONFIRMED, tRepo.getTransactionById("txn_ok")?.reviewState)
        assertEquals(ReviewState.NEEDS_REVIEW, tRepo.getTransactionById("txn_failed")?.reviewState)
    }

    @Test
    fun testAddAccountFromCapture_insertsAndClearsFlag() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cDao = FakePendingCaptureDao()
        val aRepo = FakeAccountRepo()

        val now = System.currentTimeMillis()
        val capture = PendingCaptureEntity(
            id = "cap_new_acc",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 1200.0,
            payee = "Store",
            inferredAccount = null,
            timestamp = now,
            kind = PendingCaptureEntity.KIND_DEBIT,
            flags = "${PendingCaptureEntity.FLAG_NEW_ACCOUNT},OTHER_FLAG",
            state = PendingCaptureEntity.STATE_NEEDS_REVIEW,
            notificationKey = null,
            contentHash = null,
            updatedAt = now,
            detectedAccountHint = "Axis Bank 5678"
        )
        cDao.insert(capture)

        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = cDao,
            accountRepository = aRepo,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.addAccountFromCapture(
            captureId = "cap_new_acc",
            name = "Axis Bank",
            lastFour = "5678"
        )
        advanceUntilIdle()

        val accounts = aRepo.getAllAccounts()
        assertEquals(1, accounts.size)
        val addedAccount = accounts.first()
        assertEquals("Axis Bank", addedAccount.name)
        assertEquals("5678", addedAccount.lastFourDigits)
        assertTrue(addedAccount.isDefault)

        val updatedCapture = cDao.getById("cap_new_acc")
        assertTrue(updatedCapture != null)
        assertFalse(updatedCapture!!.flags.contains(PendingCaptureEntity.FLAG_NEW_ACCOUNT))
        assertEquals("OTHER_FLAG", updatedCapture.flags)
        assertNull(updatedCapture.detectedAccountHint)
        assertEquals("Axis Bank", updatedCapture.inferredAccount)
    }

    private fun newAccountCapture(now: Long) = PendingCaptureEntity(
        id = "cap_v2",
        sourcePackage = "com.google.android.apps.nbu.paisa.user",
        amount = 10.0,
        payee = "Store",
        inferredAccount = "HDFC",
        timestamp = now,
        kind = PendingCaptureEntity.KIND_DEBIT,
        flags = PendingCaptureEntity.FLAG_NEW_ACCOUNT,
        state = PendingCaptureEntity.STATE_NEEDS_REVIEW,
        notificationKey = null,
        contentHash = null,
        updatedAt = now,
        detectedAccountHint = "Axis 1234"
    )

    private suspend fun runAddAccount(
        existing: List<Account>,
        name: String,
        lastFour: String,
        scope: kotlinx.coroutines.test.TestScope
    ): Triple<FakeAccountRepo, FakePendingCaptureDao, ReviewInboxViewModel> {
        val aRepo = FakeAccountRepo()
        aRepo.accounts.value = existing
        val cDao = FakePendingCaptureDao()
        cDao.insert(newAccountCapture(System.currentTimeMillis()))
        val vm = ReviewInboxViewModel(
            transactionRepository = FakeTransactionRepo(),
            directSyncQueueDao = FakeSyncQueueDao(),
            directPendingCaptureDao = cDao,
            accountRepository = aRepo,
            ioDispatcher = testDispatcher
        )
        scope.advanceUntilIdle()
        vm.addAccountFromCapture("cap_v2", name, lastFour)
        scope.advanceUntilIdle()
        return Triple(aRepo, cDao, vm)
    }

    @Test
    fun testAddAccountFromCapture_duplicateNameRejected() = runTest {
        val (aRepo, cDao, vm) = runAddAccount(
            listOf(Account(id = "a1", name = "Axis Bank", isDefault = true, isArchived = true)),
            "  axis bank ", "", this
        )
        assertEquals(1, aRepo.accounts.value.size)
        assertTrue(vm.uiState.value.errorMessage != null)
        assertTrue(cDao.getById("cap_v2")!!.flags.contains(PendingCaptureEntity.FLAG_NEW_ACCOUNT))
        assertEquals("HDFC", cDao.getById("cap_v2")!!.inferredAccount)
    }

    @Test
    fun testAddAccountFromCapture_cashAndOtherRejected() = runTest {
        val (aRepo, _, vm) = runAddAccount(emptyList(), "Cash", "", this)
        assertTrue(aRepo.accounts.value.isEmpty())
        assertTrue(vm.uiState.value.errorMessage != null)
        vm.clearMessages()
        vm.addAccountFromCapture("cap_v2", "other", "")
        advanceUntilIdle()
        assertTrue(aRepo.accounts.value.isEmpty())
        assertTrue(vm.uiState.value.errorMessage != null)
    }

    @Test
    fun testAddAccountFromCapture_badLastFourRejected() = runTest {
        val (aRepo, _, vm) = runAddAccount(emptyList(), "Axis", "12a4", this)
        assertTrue(aRepo.accounts.value.isEmpty())
        assertTrue(vm.uiState.value.errorMessage != null)
        vm.clearMessages()
        vm.addAccountFromCapture("cap_v2", "Axis", "123")
        advanceUntilIdle()
        assertTrue(aRepo.accounts.value.isEmpty())
        assertTrue(vm.uiState.value.errorMessage != null)
    }

    @Test
    fun testAddAccountFromCapture_blankLastFourStoredAsNull() = runTest {
        val (aRepo, cDao, _) = runAddAccount(emptyList(), "Kotak", "  ", this)
        assertEquals(1, aRepo.accounts.value.size)
        assertNull(aRepo.accounts.value.first().lastFourDigits)
        assertTrue(aRepo.accounts.value.first().isDefault)
        assertEquals("Kotak", cDao.getById("cap_v2")!!.inferredAccount)
    }

    @Test
    fun testDismissCapture_setsStateDismissed() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cDao = FakePendingCaptureDao()
        val aRepo = FakeAccountRepo()

        val now = System.currentTimeMillis()
        val capture = PendingCaptureEntity(
            id = "cap_dismiss",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 50.0,
            payee = "Tea",
            inferredAccount = null,
            timestamp = now,
            kind = PendingCaptureEntity.KIND_DEBIT,
            state = PendingCaptureEntity.STATE_NEEDS_REVIEW,
            notificationKey = null,
            contentHash = null,
            updatedAt = now
        )
        cDao.insert(capture)

        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = cDao,
            accountRepository = aRepo,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.dismissCapture("cap_dismiss")
        advanceUntilIdle()

        val updatedCapture = cDao.getById("cap_dismiss")
        assertEquals(PendingCaptureEntity.STATE_DISMISSED, updatedCapture?.state)
    }

    @Test
    fun testKeepBoth_confirmsBothAndClearsFlags() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cDao = FakePendingCaptureDao()
        val aRepo = FakeAccountRepo()

        val t1 = Transaction(
            id = "txn_1",
            timestamp = 1000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 250.0,
            companions = "Personal",
            myShare = 250.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            reviewState = ReviewState.NEEDS_REVIEW,
            isPossibleDuplicate = true,
            duplicateOfId = null
        )
        val t2 = Transaction(
            id = "txn_2",
            timestamp = 2000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 250.0,
            companions = "Personal",
            myShare = 250.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            reviewState = ReviewState.NEEDS_REVIEW,
            isPossibleDuplicate = true,
            duplicateOfId = "txn_1"
        )
        tRepo.insertTransaction(t1)
        tRepo.insertTransaction(t2)

        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = cDao,
            accountRepository = aRepo,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.keepBoth("txn_2")
        advanceUntilIdle()

        val r1 = tRepo.getTransactionById("txn_1")!!
        val r2 = tRepo.getTransactionById("txn_2")!!

        assertEquals(ReviewState.CONFIRMED, r1.reviewState)
        assertFalse(r1.isPossibleDuplicate)
        assertNull(r1.duplicateOfId)

        assertEquals(ReviewState.CONFIRMED, r2.reviewState)
        assertFalse(r2.isPossibleDuplicate)
        assertNull(r2.duplicateOfId)
    }

    @Test
    fun testKeepOne_voidsNewerEntryOnly() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cDao = FakePendingCaptureDao()
        val aRepo = FakeAccountRepo()

        val olderTxn = Transaction(
            id = "txn_older",
            timestamp = 1000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Store",
            category = "Shopping",
            totalAmount = 500.0,
            companions = "Personal",
            myShare = 500.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            reviewState = ReviewState.NEEDS_REVIEW,
            isPossibleDuplicate = true,
            duplicateOfId = null
        )
        val newerTxn = Transaction(
            id = "txn_newer",
            timestamp = 2000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Store",
            category = "Shopping",
            totalAmount = 500.0,
            companions = "Personal",
            myShare = 500.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            reviewState = ReviewState.NEEDS_REVIEW,
            isPossibleDuplicate = true,
            duplicateOfId = "txn_older"
        )
        tRepo.insertTransaction(olderTxn)
        tRepo.insertTransaction(newerTxn)

        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = cDao,
            accountRepository = aRepo,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.keepOne("txn_newer")
        advanceUntilIdle()

        val rOlder = tRepo.getTransactionById("txn_older")!!
        val rNewer = tRepo.getTransactionById("txn_newer")!!

        assertEquals(ReviewState.CONFIRMED, rOlder.reviewState)
        assertFalse(rOlder.isPossibleDuplicate)
        assertNull(rOlder.duplicateOfId)
        assertEquals(TransactionStatus.PENDING, rOlder.status)

        // Newer one must be voided
        assertEquals(TransactionStatus.VOID, rNewer.status)
    }

    @Test
    fun testDiscardQueueItem_insertVoidsLocally() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cDao = FakePendingCaptureDao()
        val aRepo = FakeAccountRepo()

        val txn = Transaction(
            id = "txn_failed_insert",
            timestamp = 1000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 500.0,
            companions = "Personal",
            myShare = 500.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            reviewState = ReviewState.NEEDS_REVIEW
        )
        tRepo.insertTransaction(txn)

        val queueItem = SyncQueueEntity(
            id = 10L,
            transactionId = "txn_failed_insert",
            operationType = SyncQueueEntity.OP_INSERT,
            payloadJson = "{}",
            status = SyncQueueEntity.STATUS_FAILED,
            retryCount = 8,
            lastErrorKind = "AUTH_EXPIRED",
            lastErrorDetail = "401 Unauthenticated"
        )
        qDao.insert(queueItem)

        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = cDao,
            accountRepository = aRepo,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.discardQueueItem(10L)
        advanceUntilIdle()

        // Queue item must be deleted
        assertNull(qDao.getById(10L))

        // Transaction must be marked VOID locally, not in NEEDS_REVIEW
        val updatedTxn = tRepo.getTransactionById("txn_failed_insert")!!
        assertEquals(TransactionStatus.VOID, updatedTxn.status)
        assertEquals(500.0, updatedTxn.totalAmount, 0.001)
        assertEquals(500.0, updatedTxn.myShare, 0.001)
        assertTrue(updatedTxn.merchant.startsWith("[Void] "))
        assertEquals(ReviewState.CONFIRMED, updatedTxn.reviewState)
        assertEquals(com.financeautomation.core.model.SyncState.VOID, updatedTxn.syncState)
        // No updateTransaction call means no new sync_queue row can have been created
        assertEquals(0, tRepo.updateCalls)
        assertTrue(qDao.queue.isEmpty())
    }

    @Test
    fun testDiscardQueueItem_updateMarksSyncedWithoutNewQueueRow() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cDao = FakePendingCaptureDao()
        val aRepo = FakeAccountRepo()

        tRepo.insertTransaction(
            Transaction(
                id = "txn_failed_update", timestamp = 1000L, dateSerial = 46330, timeSerial = 0.5,
                account = "HDFC", merchant = "Cafe", category = "Food & Drink", totalAmount = 500.0,
                companions = "Personal", myShare = 500.0, owedToMe = 0.0,
                status = TransactionStatus.PENDING, reviewState = ReviewState.NEEDS_REVIEW
            )
        )
        qDao.insert(
            SyncQueueEntity(
                id = 11L, transactionId = "txn_failed_update", operationType = SyncQueueEntity.OP_UPDATE,
                payloadJson = "{}", status = SyncQueueEntity.STATUS_FAILED, retryCount = 8
            )
        )
        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo, directSyncQueueDao = qDao, directPendingCaptureDao = cDao,
            accountRepository = aRepo, ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.discardQueueItem(11L)
        advanceUntilIdle()

        assertNull(qDao.getById(11L))
        assertTrue(qDao.queue.isEmpty())
        val t = tRepo.getTransactionById("txn_failed_update")!!
        assertEquals(com.financeautomation.core.model.SyncState.SYNCED, t.syncState)
        assertEquals(ReviewState.CONFIRMED, t.reviewState)
        assertEquals(500.0, t.totalAmount, 0.001)
        assertEquals(0, tRepo.updateCalls)
    }

    @Test
    fun testRetryQueueItem_resetsStatusAndErrors() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cDao = FakePendingCaptureDao()
        val aRepo = FakeAccountRepo()

        val queueItem = SyncQueueEntity(
            id = 20L,
            transactionId = "txn_20",
            operationType = SyncQueueEntity.OP_INSERT,
            payloadJson = "{}",
            status = SyncQueueEntity.STATUS_FAILED,
            retryCount = 8,
            lastErrorKind = "RATE_LIMITED",
            lastErrorDetail = "429 Too Many Requests"
        )
        qDao.insert(queueItem)

        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = cDao,
            accountRepository = aRepo,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.retryQueueItem(20L)
        advanceUntilIdle()

        val updatedQueue = qDao.getById(20L)!!
        assertEquals(SyncQueueEntity.STATUS_PENDING, updatedQueue.status)
        assertEquals(0, updatedQueue.retryCount)
        assertNull(updatedQueue.lastErrorKind)
        assertNull(updatedQueue.lastErrorDetail)
    }
}

