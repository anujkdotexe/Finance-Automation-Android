package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.testutil.FakePendingCaptureDao
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class NeedsReviewPathTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestTransactionRepo : TransactionRepository {
        val txns = MutableStateFlow<List<Transaction>>(emptyList())
        override fun getTransactions(): Flow<List<Transaction>> = txns
        override fun getNeedsReview(): Flow<List<Transaction>> =
            txns.map { list -> list.filter { it.reviewState == ReviewState.NEEDS_REVIEW && it.status != TransactionStatus.VOID } }
        override suspend fun getTransactionById(id: String): Transaction? = txns.value.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            txns.value = txns.value + transaction
            return Result.Success(Unit)
        }
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            txns.value = txns.value.map { if (it.id == transaction.id) transaction else it }
            return Result.Success(Unit)
        }
        override suspend fun voidTransaction(id: String): Result<Unit> {
            txns.value = txns.value.map { if (it.id == id) it.copy(status = TransactionStatus.VOID) else it }
            return Result.Success(Unit)
        }
        override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> = Result.Success(Unit)
        override suspend fun discardQueueItem(queueItemId: Long): Result<Unit> = Result.Success(Unit)
        override suspend fun undoRecentSave(id: String): Result<Unit> = voidTransaction(id)
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

    private class TestSyncQueueDao : SyncQueueDao {
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
        override suspend fun recoverRetryableFailedItems(): Int = 0
        override suspend fun countPendingOperations(opType: String): Int = 0
        override suspend fun updatePlanJson(id: Long, planJson: String?) {}
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
        override suspend fun getActiveTransactionIds(): List<String> = emptyList()
        override suspend fun deletePendingForTransactions(transactionIds: List<String>) {
            val idSet = transactionIds.toSet()
            queue.values.filter {
                it.transactionId in idSet &&
                it.status == SyncQueueEntity.STATUS_PENDING &&
                (it.operationType == SyncQueueEntity.OP_INSERT || it.operationType == SyncQueueEntity.OP_UPDATE || it.operationType == SyncQueueEntity.OP_VOID)
            }.forEach { queue.remove(it.id) }
            refreshFlow()
        }
    }

    private class TestAccountRepo : AccountRepository {
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
        override fun getAccountTransactionCounts(from: Long, to: Long): Flow<Map<String, Int>> = flowOf(emptyMap())
    }

    @Test
    fun dismissedAndWaitingAndShownCapturesAppearInNeedsReview() = runTest {
        val captureDao = FakePendingCaptureDao()
        val now = System.currentTimeMillis()

        captureDao.insert(
            PendingCaptureEntity(
                id = "cap_waiting",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 120.0,
                payee = "Tea Stall",
                inferredAccount = "GPay",
                timestamp = now - 3000,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_WAITING,
                notificationKey = null,
                contentHash = null,
                updatedAt = now - 3000
            )
        )
        captureDao.insert(
            PendingCaptureEntity(
                id = "cap_shown",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 250.0,
                payee = "Book Store",
                inferredAccount = "GPay",
                timestamp = now - 2000,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_SHOWN,
                notificationKey = null,
                contentHash = null,
                updatedAt = now - 2000
            )
        )
        captureDao.insert(
            PendingCaptureEntity(
                id = "cap_dismissed",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 350.0,
                payee = "Supermarket",
                inferredAccount = "GPay",
                timestamp = now - 1000,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_DISMISSED,
                notificationKey = null,
                contentHash = null,
                updatedAt = now - 1000
            )
        )

        val vm = ReviewInboxViewModel(
            transactionRepository = TestTransactionRepo(),
            directSyncQueueDao = TestSyncQueueDao(),
            directPendingCaptureDao = captureDao,
            accountRepository = TestAccountRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val captures = vm.uiState.value.captures
        assertEquals(3, captures.size)
        assertTrue(captures.any { it.id == "cap_waiting" })
        assertTrue(captures.any { it.id == "cap_shown" })
        assertTrue(captures.any { it.id == "cap_dismissed" })
    }

    @Test
    fun newAccountCaptureAppearsInNewAccountCaptures() = runTest {
        val captureDao = FakePendingCaptureDao()
        val now = System.currentTimeMillis()

        captureDao.insert(
            PendingCaptureEntity(
                id = "cap_new_acc",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 999.0,
                payee = "Electronics",
                inferredAccount = null,
                detectedAccountHint = "HDFC 5678",
                flags = PendingCaptureEntity.FLAG_NEW_ACCOUNT,
                timestamp = now,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_WAITING,
                notificationKey = null,
                contentHash = null,
                updatedAt = now
            )
        )

        val vm = ReviewInboxViewModel(
            transactionRepository = TestTransactionRepo(),
            directSyncQueueDao = TestSyncQueueDao(),
            directPendingCaptureDao = captureDao,
            accountRepository = TestAccountRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val newAccCaptures = vm.uiState.value.newAccountCaptures
        assertEquals(1, newAccCaptures.size)
        assertEquals("cap_new_acc", newAccCaptures.first().id)
        // Also ensure it is not duplicated in regular captures
        assertTrue(vm.uiState.value.captures.none { it.id == "cap_new_acc" })
    }

    @Test
    fun duplicateResolution_keepBothAndKeepOneAndDismiss() = runTest {
        val tRepo = TestTransactionRepo()
        val now = System.currentTimeMillis()

        val txn1 = Transaction(
            id = "txn_dup_1",
            timestamp = now - 1000,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 200.0,
            myShare = 200.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            companions = "Personal",
            reviewState = ReviewState.NEEDS_REVIEW,
            syncState = SyncState.PENDING,
            isPossibleDuplicate = true,
            duplicateOfId = "txn_dup_2"
        )
        val txn2 = Transaction(
            id = "txn_dup_2",
            timestamp = now,
            dateSerial = 46330,
            timeSerial = 0.51,
            account = "SBI",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 200.0,
            myShare = 200.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            companions = "Personal",
            reviewState = ReviewState.NEEDS_REVIEW,
            syncState = SyncState.PENDING,
            isPossibleDuplicate = true,
            duplicateOfId = "txn_dup_1"
        )

        tRepo.insertTransaction(txn1)
        tRepo.insertTransaction(txn2)

        val vm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = TestSyncQueueDao(),
            directPendingCaptureDao = FakePendingCaptureDao(),
            accountRepository = TestAccountRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.items.size)

        // 1. Keep One: keeps older (txn1), voids newer (txn2)
        vm.keepOne("txn_dup_1")
        advanceUntilIdle()

        val keptOlder = tRepo.getTransactionById("txn_dup_1")
        val voidedNewer = tRepo.getTransactionById("txn_dup_2")

        assertEquals(ReviewState.CONFIRMED, keptOlder?.reviewState)
        assertEquals(false, keptOlder?.isPossibleDuplicate)
        assertEquals(TransactionStatus.VOID, voidedNewer?.status)

        // 2. Dismiss/void on another duplicate voids it
        val txn3 = Transaction(
            id = "txn_dup_3",
            timestamp = now,
            dateSerial = 46330,
            timeSerial = 0.52,
            account = "SBI",
            merchant = "Snacks",
            category = "Food & Drink",
            totalAmount = 50.0,
            myShare = 50.0,
            owedToMe = 0.0,
            status = TransactionStatus.PENDING,
            companions = "Personal",
            reviewState = ReviewState.NEEDS_REVIEW,
            syncState = SyncState.PENDING,
            isPossibleDuplicate = true
        )
        tRepo.insertTransaction(txn3)
        advanceUntilIdle()

        vm.voidTransaction("txn_dup_3")
        advanceUntilIdle()
        assertEquals(TransactionStatus.VOID, tRepo.getTransactionById("txn_dup_3")?.status)
    }

    @Test
    fun dismissCaptureFromReviewInbox_marksStateDone() = runTest {
        val captureDao = FakePendingCaptureDao()
        val now = System.currentTimeMillis()

        captureDao.insert(
            PendingCaptureEntity(
                id = "cap_to_dismiss",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 75.0,
                payee = "Juice Bar",
                inferredAccount = "GPay",
                timestamp = now,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_DISMISSED,
                notificationKey = null,
                contentHash = null,
                updatedAt = now
            )
        )

        val vm = ReviewInboxViewModel(
            transactionRepository = TestTransactionRepo(),
            directSyncQueueDao = TestSyncQueueDao(),
            directPendingCaptureDao = captureDao,
            accountRepository = TestAccountRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.captures.size)

        vm.dismissCapture("cap_to_dismiss")
        advanceUntilIdle()

        // Capture state is now DONE, so it is removed from Needs Review
        assertEquals(PendingCaptureEntity.STATE_DONE, captureDao.getById("cap_to_dismiss")?.state)
    }

    @Test
    fun dashboardBadgeMatchesNeedsReviewTotal() = runTest {
        val captureDao = FakePendingCaptureDao()
        val tRepo = TestTransactionRepo()
        val qDao = TestSyncQueueDao()
        val now = System.currentTimeMillis()

        // 1 unreviewed transaction
        tRepo.insertTransaction(
            Transaction(
                id = "txn_review_1",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "SBI",
                merchant = "Grocery",
                category = "Groceries",
                totalAmount = 500.0,
                myShare = 500.0,
                owedToMe = 0.0,
                status = TransactionStatus.PENDING,
                companions = "Personal",
                reviewState = ReviewState.NEEDS_REVIEW,
                syncState = SyncState.PENDING
            )
        )

        // 1 capture in DISMISSED state
        captureDao.insert(
            PendingCaptureEntity(
                id = "cap_dismissed_badge",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 150.0,
                payee = "Pharmacy",
                inferredAccount = "GPay",
                timestamp = now,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_DISMISSED,
                notificationKey = null,
                contentHash = null,
                updatedAt = now
            )
        )

        // 1 failed queue item
        qDao.insert(
            SyncQueueEntity(
                id = 1L,
                transactionId = "txn_failed_queue",
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "{}",
                status = SyncQueueEntity.STATUS_FAILED,
                retryCount = 8,
                createdAt = now
            )
        )

        val reviewVm = ReviewInboxViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = captureDao,
            accountRepository = TestAccountRepo(),
            ioDispatcher = testDispatcher
        )

        val dashboardVm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPendingCaptureDao = captureDao,
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        // Review inbox ui state: 1 txn + 1 capture + 1 failed queue item = 3
        assertEquals(3, reviewVm.uiState.value.totalCount)

        // Dashboard badge: needsReviewTotal = 3
        assertEquals(3, dashboardVm.uiState.value.needsReviewTotal)
        assertEquals(reviewVm.uiState.value.totalCount, dashboardVm.uiState.value.needsReviewTotal)
    }
}
