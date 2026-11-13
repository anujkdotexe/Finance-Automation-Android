package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.BudgetRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.BudgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

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
        override fun getTransactions(): Flow<List<Transaction>> = txns
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = txns.value.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            txns.value = txns.value + transaction
            return Result.Success(Unit)
        }
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            txns.value = txns.value.map { if (it.id == transaction.id) transaction else it }
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
    }

    private class FakeSyncQueueDao : SyncQueueDao {
        val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
        private val idGen = AtomicLong(1)
        private val queueFlow = MutableStateFlow<List<SyncQueueEntity>>(emptyList())

        fun refreshFlow() {
            queueFlow.value = queue.values.toList()
        }

        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(emptyList())
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = queueFlow
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(emptyList())
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

    private class FakePayableDao : PayableDao {
        val payables = MutableStateFlow<List<PayableEntity>>(emptyList())
        override fun observeAll(): Flow<List<PayableEntity>> = payables
        override fun observePending(): Flow<List<PayableEntity>> = flowOf(payables.value.filter { it.repaymentStatus.equals("pending", ignoreCase = true) })
        override suspend fun getAll(): List<PayableEntity> = payables.value
        override suspend fun getPending(): List<PayableEntity> = payables.value.filter { it.repaymentStatus.equals("pending", ignoreCase = true) }
        override suspend fun getByUuid(uuid: String): PayableEntity? = payables.value.find { it.uuid == uuid }
        override suspend fun upsert(payable: PayableEntity) {
            payables.value = payables.value.filter { it.uuid != payable.uuid } + payable
        }
        override suspend fun upsertAll(payables: List<PayableEntity>) {
            val newUuids = payables.map { it.uuid }.toSet()
            this.payables.value = this.payables.value.filter { it.uuid !in newUuids } + payables
        }
        override suspend fun deleteByUuid(uuid: String) { payables.value = payables.value.filter { it.uuid != uuid } }
        override suspend fun deleteAll() { payables.value = emptyList() }
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
        override suspend fun updateState(id: String, state: String, updatedAt: Long) {}
        override suspend fun resetShownToWaiting(updatedAt: Long): Int = 0
        override suspend fun deleteById(id: String) { captures.value = captures.value.filter { it.id != id } }
        override suspend fun pruneOldCompletedCaptures(cutoffTime: Long) {}
    }

    private class FakeCategoryRepo : CategoryRepository {
        override fun getCategories(includeArchived: Boolean): Flow<List<Category>> = flowOf(
            listOf(
                Category("1", "Food & Drink", isExcludedFromLivingBurn = false),
                Category("2", "Savings", isExcludedFromLivingBurn = true),
                Category("3", "Shopping", isExcludedFromLivingBurn = false)
            )
        )
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

    private class FakeBudgetRepo(initial: List<Budget> = emptyList()) : BudgetRepository {
        val budgets = MutableStateFlow(initial)
        override fun observeBudgets(): Flow<List<Budget>> = budgets
        override suspend fun setBudget(category: String, monthlyAmount: Double): Result<Unit> {
            budgets.value = budgets.value.filter { it.category != category } + Budget(category, monthlyAmount, System.currentTimeMillis())
            return Result.Success(Unit)
        }
        override suspend fun removeBudget(category: String): Result<Unit> {
            budgets.value = budgets.value.filter { it.category != category }
            return Result.Success(Unit)
        }
    }

    @Test
    fun testDashboardMetrics_burnExcludesSavings_andAggregatesOwedPerPerson() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val cRepo = FakeCategoryRepo()

        val now = System.currentTimeMillis()
        val foodTxn = Transaction(
            id = "txn_food",
            timestamp = now,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 300.0,
            companions = "Alice, Bob",
            myShare = 100.0,
            owedToMe = 200.0,
            status = TransactionStatus.PENDING
        ,
        reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        val savingsTxn = Transaction(
            id = "txn_savings",
            timestamp = now,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Index Fund",
            category = "Savings",
            totalAmount = 5000.0,
            companions = "Personal",
            myShare = 5000.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        ,
        reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        tRepo.insertTransaction(foodTxn)
        tRepo.insertTransaction(savingsTxn)

        pDao.upsert(
            PayableEntity(
                uuid = "pay_charlie",
                dateIncurredSerial = 46330,
                timeSerial = 0.5,
                paidBy = "Charlie",
                whatFor = "Tickets",
                amountIOwe = 120.0,
                dateRepaidSerial = null,
                repaymentStatus = "Pending",
                tabName = "Nov26",
                sheetRowIndex = 4,
                updatedAt = now
            )
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPayableDao = pDao,
            categoryRepository = cRepo,
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(100.0, state.currentBurn, 0.001)
        assertEquals(5000.0, state.savings, 0.001)
        assertEquals(200.0, state.owedToMe, 0.001)
        assertEquals(120.0, state.iOwePeople, 0.001)

        val topOwed = state.topOwedToMe
        assertEquals(2, topOwed.size)
        assertTrue(topOwed.any { it.name == "Alice" && it.amount == 100.0 })
        assertTrue(topOwed.any { it.name == "Bob" && it.amount == 100.0 })
    }

    @Test
    fun testDashboardMetrics_sameDayLastMonthPacing_onTrackTrue() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cRepo = FakeCategoryRepo()

        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz)
        val currentDay = nowCal.get(Calendar.DAY_OF_MONTH)

        val currentMonthTxnCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 10)
        }
        tRepo.insertTransaction(
            Transaction(
                id = "txn_cur",
                timestamp = currentMonthTxnCal.timeInMillis,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Grocery",
                category = "Food & Drink",
                totalAmount = 200.0,
                companions = "Personal",
                myShare = 200.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )

        val priorMonthTxn1Cal = Calendar.getInstance(kolkataTz).apply {
            add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 10)
        }
        tRepo.insertTransaction(
            Transaction(
                id = "txn_prior_1",
                timestamp = priorMonthTxn1Cal.timeInMillis,
                dateSerial = 46300,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Grocery Prior",
                category = "Food & Drink",
                totalAmount = 300.0,
                companions = "Personal",
                myShare = 300.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            categoryRepository = cRepo,
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(200.0, state.currentBurn, 0.001)
        assertEquals(300.0, state.lastMonthSameDayBurn, 0.001)
        assertTrue(state.onTrack)
        val delta = state.paceDeltaPercent
        assertTrue(delta != null)
        assertEquals(((200.0 - 300.0) / 300.0) * 100.0, delta!!, 0.001)
    }

    @Test
    fun testDashboardMetrics_sameDayLastMonthPacing_onTrackFalse() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cRepo = FakeCategoryRepo()

        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val currentMonthTxnCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 10)
        }
        tRepo.insertTransaction(
            Transaction(
                id = "txn_cur",
                timestamp = currentMonthTxnCal.timeInMillis,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Electronics",
                category = "Shopping",
                totalAmount = 500.0,
                companions = "Personal",
                myShare = 500.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )

        val priorMonthTxnCal = Calendar.getInstance(kolkataTz).apply {
            add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 10)
        }
        tRepo.insertTransaction(
            Transaction(
                id = "txn_prior",
                timestamp = priorMonthTxnCal.timeInMillis,
                dateSerial = 46300,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Shopping Prior",
                category = "Shopping",
                totalAmount = 250.0,
                companions = "Personal",
                myShare = 250.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            categoryRepository = cRepo,
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(500.0, state.currentBurn, 0.001)
        assertEquals(250.0, state.lastMonthSameDayBurn, 0.001)
        assertFalse(state.onTrack)
        val delta = state.paceDeltaPercent
        assertTrue(delta != null)
        assertEquals(((500.0 - 250.0) / 250.0) * 100.0, delta!!, 0.001)
    }

    @Test
    fun testDashboardMetrics_zeroLastMonth_paceDeltaNull() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val cRepo = FakeCategoryRepo()

        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val currentMonthTxnCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 10)
        }
        tRepo.insertTransaction(
            Transaction(
                id = "txn_cur",
                timestamp = currentMonthTxnCal.timeInMillis,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Coffee",
                category = "Food & Drink",
                totalAmount = 150.0,
                companions = "Personal",
                myShare = 150.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            categoryRepository = cRepo,
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(150.0, state.currentBurn, 0.001)
        assertEquals(0.0, state.lastMonthSameDayBurn, 0.001)
        assertNull(state.paceDeltaPercent)
        assertFalse(state.onTrack)
    }

    @Test
    fun testDashboardMetrics_syncCountsAndNeedsReviewTotal() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val captureDao = FakePendingCaptureDao()
        val cRepo = FakeCategoryRepo()

        val now = System.currentTimeMillis()
        tRepo.insertTransaction(
            Transaction(
                id = "txn_review",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Unconfirmed Merchant",
                category = "Food & Drink",
                totalAmount = 250.0,
                companions = "Personal",
                myShare = 250.0,
                owedToMe = 0.0,
                status = TransactionStatus.PENDING,
                reviewState = ReviewState.NEEDS_REVIEW
            )
        )

        captureDao.insert(
            PendingCaptureEntity(
                id = "cap_review",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 99.0,
                payee = "Tea Stall",
                inferredAccount = "HDFC",
                timestamp = now,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_NEEDS_REVIEW,
                notificationKey = null,
                contentHash = null,
                updatedAt = now
            )
        )

        qDao.insert(
            SyncQueueEntity(
                id = 1L,
                transactionId = "txn_1",
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "{}",
                status = SyncQueueEntity.STATUS_PENDING
            )
        )
        qDao.insert(
            SyncQueueEntity(
                id = 2L,
                transactionId = "txn_2",
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "{}",
                status = SyncQueueEntity.STATUS_IN_PROGRESS
            )
        )
        qDao.insert(
            SyncQueueEntity(
                id = 3L,
                transactionId = "txn_3",
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "{}",
                status = SyncQueueEntity.STATUS_FAILED
            )
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPayableDao = null,
            directPendingCaptureDao = captureDao,
            categoryRepository = cRepo,
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.waitingCount)
        assertEquals(1, state.failedCount)
        assertEquals(1, state.needsReviewCount)
        // needsReviewTotal = 1 txn + 1 capture in NEEDS_REVIEW + 1 failed queue item = 3
        assertEquals(3, state.needsReviewTotal)
    }

    @Test
    fun testNeedsReviewTotal_failedSyncOfReviewTransactionCountsOnce() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        val now = System.currentTimeMillis()
        tRepo.insertTransaction(
            Transaction(
                id = "txn_failed_review",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Cafe",
                category = "Food & Drink",
                totalAmount = 250.0,
                companions = "Personal",
                myShare = 250.0,
                owedToMe = 0.0,
                status = TransactionStatus.PENDING,
                reviewState = ReviewState.NEEDS_REVIEW
            )
        )
        qDao.insert(
            SyncQueueEntity(
                id = 1L,
                transactionId = "txn_failed_review",
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "{}",
                status = SyncQueueEntity.STATUS_FAILED
            )
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.failedCount)
        assertEquals(1, state.needsReviewCount)
        assertEquals(1, state.needsReviewTotal)
    }

    private fun offlineTxn(id: String, merchant: String, amount: Double) = Transaction(
        id = id,
        timestamp = System.currentTimeMillis(),
        dateSerial = 46330,
        timeSerial = 0.5,
        account = "UPI",
        merchant = merchant,
        category = "Food & Drink",
        totalAmount = amount,
        companions = "Personal",
        myShare = amount,
        owedToMe = 0.0,
        status = TransactionStatus.PERSONAL,
        reviewState = ReviewState.CONFIRMED
    )

    @Test
    fun testOfflineFlagFollowsConnectivityState() = runTest {
        val online = kotlinx.coroutines.flow.MutableStateFlow(false)
        val vm = DashboardViewModel(
            transactionRepository = FakeTransactionRepo(),
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            onlineState = online,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isOffline)

        online.value = true
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isOffline)
    }

    @Test
    fun testQueuedItemsMatchPendingQueueJoinedToTransactions() = runTest {
        val tRepo = FakeTransactionRepo()
        val qDao = FakeSyncQueueDao()
        tRepo.insertTransaction(offlineTxn("t_a", "Cafe", 120.0))
        tRepo.insertTransaction(offlineTxn("t_b", "Taxi", 340.5))
        tRepo.insertTransaction(offlineTxn("t_c", "Done Already", 10.0))
        qDao.insert(SyncQueueEntity(id = 1L, transactionId = "t_a", operationType = SyncQueueEntity.OP_INSERT, payloadJson = "t_a", status = SyncQueueEntity.STATUS_PENDING, createdAt = 100L))
        qDao.insert(SyncQueueEntity(id = 2L, transactionId = "t_b", operationType = SyncQueueEntity.OP_INSERT, payloadJson = "t_b", status = SyncQueueEntity.STATUS_PENDING, createdAt = 200L))
        qDao.insert(SyncQueueEntity(id = 3L, transactionId = "t_c", operationType = SyncQueueEntity.OP_INSERT, payloadJson = "t_c", status = SyncQueueEntity.STATUS_FAILED, createdAt = 300L))
        // A queue item with no transaction (for example a credit request) is not listed
        qDao.insert(SyncQueueEntity(id = 4L, transactionId = "cred_1", operationType = SyncQueueEntity.OP_LOG_CREDIT, payloadJson = "{}", status = SyncQueueEntity.STATUS_PENDING, createdAt = 400L))

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            onlineState = kotlinx.coroutines.flow.MutableStateFlow(false),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(
            listOf(QueuedItem("Cafe", 120.0, 100L), QueuedItem("Taxi", 340.5, 200L)),
            vm.uiState.value.queuedItems
        )
    }

    private class FakeInflowRepo : com.financeautomation.domain.repository.InflowRepository {
        val inflows = MutableStateFlow<List<com.financeautomation.domain.model.Inflow>>(emptyList())
        override fun getInflowsByRange(start: Long, end: Long): Flow<List<com.financeautomation.domain.model.Inflow>> =
            flowOf(inflows.value.filter { it.timestamp in start until end })
        override fun getAllInflows(): Flow<List<com.financeautomation.domain.model.Inflow>> = inflows
        override suspend fun getInflowById(id: String): com.financeautomation.domain.model.Inflow? =
            inflows.value.find { it.id == id }
        override suspend fun insertInflow(inflow: com.financeautomation.domain.model.Inflow) {
            inflows.value = inflows.value + inflow
        }
        override suspend fun updateStatus(id: String, status: String) {
            inflows.value = inflows.value.map { if (it.id == id) it.copy(status = status) else it }
        }
        override suspend fun voidInflow(id: String): com.financeautomation.core.model.Result<Unit> {
            if (inflows.value.none { it.id == id }) {
                return com.financeautomation.core.model.Result.Error(IllegalArgumentException("not found"))
            }
            updateStatus(id, "VOID")
            return com.financeautomation.core.model.Result.Success(Unit)
        }
    }

    @Test
    fun testWidgetSummaryEqualsTheDashboardFigureForTheSameData() = runTest {
        val now = System.currentTimeMillis()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val lastMonth = Calendar.getInstance(kolkataTz).apply {
            add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 10)
        }.timeInMillis

        fun txn(id: String, category: String, share: Double, total: Double = share, at: Long = now,
                status: TransactionStatus = TransactionStatus.PERSONAL) = Transaction(
            id = id, timestamp = at, dateSerial = 46330, timeSerial = 0.5, account = "UPI", merchant = id,
            category = category, totalAmount = total, companions = "Personal", myShare = share,
            owedToMe = total - share, status = status
        ,
        reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)

        val tRepo = FakeTransactionRepo()
        listOf(
            txn("food", "Food & Drink", 100.25),
            txn("split", "Shopping", 250.0, total = 500.0),
            txn("savings", "Savings", 300.0),
            txn("transfer", "Transfer", 40.0),
            txn("void", "Food & Drink", 999.0, status = TransactionStatus.VOID),
            txn("old", "Food & Drink", 77.0, at = lastMonth)
        ).forEach { tRepo.insertTransaction(it) }

        val qDao = FakeSyncQueueDao()
        qDao.insert(SyncQueueEntity(transactionId = "food", operationType = SyncQueueEntity.OP_INSERT, payloadJson = "food"))
        qDao.insert(SyncQueueEntity(transactionId = "split", operationType = SyncQueueEntity.OP_INSERT, payloadJson = "split"))

        val categories = listOf(
            Category("1", "Food & Drink", isExcludedFromLivingBurn = false),
            Category("2", "Savings", isExcludedFromLivingBurn = true),
            Category("3", "Shopping", isExcludedFromLivingBurn = false)
        )
        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = qDao,
            categoryRepository = FakeCategoryRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()
        val dashboard = vm.uiState.value

        val widget = com.financeautomation.domain.usecase.MonthBurnSummaryUseCase()(
            transactions = tRepo.txns.value,
            categories = categories,
            waitingCount = dashboard.waitingCount,
            nowMillis = now
        )

        assertEquals(350.25, dashboard.currentBurn, 0.0001)
        assertEquals("widget burn equals the Dashboard burn", dashboard.currentBurn, widget.livingBurn, 0.0)
        assertEquals("widget average equals the Dashboard average", dashboard.dailyAverage, widget.perDayAverage, 0.0)
        assertEquals(2, dashboard.waitingCount)
        assertEquals(com.financeautomation.domain.usecase.WidgetSyncState.Waiting(2), widget.syncState)
    }

    @Test
    fun testVoidingAnInflowReducesTheIncomeFigure() = runTest {
        val now = System.currentTimeMillis()
        val inflowRepo = FakeInflowRepo()
        inflowRepo.inflows.value = listOf(
            com.financeautomation.domain.model.Inflow("inf_a", now, 50000.0, "SBI", "Income / Salary", "Employer", "Salary"),
            com.financeautomation.domain.model.Inflow("inf_b", now, 2500.0, "SBI", "Refund / Return", "Amazon", "Return")
        )
        val vm = DashboardViewModel(
            transactionRepository = FakeTransactionRepo(),
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            inflowRepository = inflowRepo,
            onlineState = kotlinx.coroutines.flow.MutableStateFlow(true),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()
        assertEquals("the refund is not income", 50000.0, vm.uiState.value.income, 0.001)

        inflowRepo.voidInflow("inf_a")
        advanceUntilIdle()

        assertEquals(0.0, vm.uiState.value.income, 0.001)
    }

    @Test
    fun testIncomeAggregation_countsIncomeOnly_excludesRefundFriendSettlementTransferVoidAndOtherMonths() = runTest {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            timeInMillis = now
            add(Calendar.MONTH, -2)
        }
        val twoMonthsAgo = cal.timeInMillis

        val inflowRepo = FakeInflowRepo()
        inflowRepo.inflows.value = listOf(
            com.financeautomation.domain.model.Inflow("inf_1", now, 50000.0, "SBI", "Income / Salary", "Employer", "Salary"),
            com.financeautomation.domain.model.Inflow("inf_2", now, 2500.0, "SBI", "Refund / Return", "Amazon", "Return"),
            com.financeautomation.domain.model.Inflow("inf_3", now, 1200.0, "HDFC", "Friend Settlement", "Alice", "Lunch debt"),
            com.financeautomation.domain.model.Inflow("inf_4", now, 10000.0, "HDFC", "Self Transfer", "Self", "Transfer"),
            com.financeautomation.domain.model.Inflow("inf_5", now, 30000.0, "SBI", "Income / Salary", "Employer", "Voided", status = "VOID"),
            com.financeautomation.domain.model.Inflow("inf_6", twoMonthsAgo, 45000.0, "SBI", "Income / Salary", "Employer", "Old salary")
        )

        val vm = DashboardViewModel(
            transactionRepository = FakeTransactionRepo(),
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            inflowRepository = inflowRepo,
            onlineState = kotlinx.coroutines.flow.MutableStateFlow(true),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(50000.0, vm.uiState.value.income, 0.001)
    }

    @Test
    fun testRefundReducesLivingBurnAndIsNotIncome() = runTest {
        val now = System.currentTimeMillis()
        val tRepo = FakeTransactionRepo()
        val inflowRepo = FakeInflowRepo()

        // 1000.0 living expense in Groceries
        tRepo.insertTransaction(
            Transaction(
                id = "txn_grocery",
                timestamp = now,
                dateSerial = 45000,
                timeSerial = 0.5,
                account = "SBI",
                merchant = "Supermarket",
                category = "Groceries",
                totalAmount = 1000.0,
                companions = "Personal",
                myShare = 1000.0,
                owedToMe = 0.0,
                status = TransactionStatus.PERSONAL,
                reviewState = ReviewState.CONFIRMED
            )
        )

        // 200.0 refund to SBI
        inflowRepo.inflows.value = listOf(
            com.financeautomation.domain.model.Inflow("inf_refund", now, 200.0, "SBI", "Refund / Return", "Supermarket", "Item return")
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            inflowRepository = inflowRepo,
            onlineState = kotlinx.coroutines.flow.MutableStateFlow(true),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        // Income reflects the refund
        assertEquals("a refund lowers the burn, it is not income as well", 0.0, vm.uiState.value.income, 0.001)
        // Owner rule: a refund reduces the month's living burn (1000 spent, 200 refunded)
        assertEquals(800.0, vm.uiState.value.currentBurn, 0.001)
        assertEquals(200.0, vm.uiState.value.refunds, 0.001)
        assertEquals(1000.0, vm.uiState.value.grossBurn, 0.001)
        // Rows of Where it went add up to the gross total with the Refunds line, so percentages use the gross figure
        assertEquals(1.0f, vm.uiState.value.allCategories.single().percentage, 0.001f)
    }

    @Test
    fun testSetMonthOffset_updatesMonthOffsetAndState() = runTest {
        val tRepo = FakeTransactionRepo()
        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.monthOffset)

        vm.setMonthOffset(-2)
        advanceUntilIdle()

        assertEquals(-2, vm.uiState.value.monthOffset)
    }

    @Test
    fun testFirstRunHint_dismissAndPersist() = runTest {
        val tRepo = FakeTransactionRepo()
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true, ioDispatcher = testDispatcher)
        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            preferencesRepository = prefs,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isHintDismissed)

        vm.dismissFirstRunHint()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isHintDismissed)
        assertTrue(prefs.isDashboardHintDismissed())

        val vm2 = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            preferencesRepository = prefs,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()
        assertTrue(vm2.uiState.value.isHintDismissed)
    }

    @Test
    fun testWhereItWent_budgetProgressCalculated() = runTest {
        val tRepo = FakeTransactionRepo()
        val now = System.currentTimeMillis()
        tRepo.insertTransaction(
            Transaction(
                id = "txn1",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Grocery Store",
                category = "Groceries",
                totalAmount = 4000.0,
                companions = "Personal",
                myShare = 4000.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )
        tRepo.insertTransaction(
            Transaction(
                id = "txn2",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "Card",
                merchant = "Restaurant",
                category = "Food & Drink",
                totalAmount = 1500.0,
                companions = "Personal",
                myShare = 1500.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )

        val budgetRepo = FakeBudgetRepo(
            listOf(
                Budget(category = "Groceries", monthlyAmount = 5000.0, updatedAt = now)
            )
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            budgetRepository = budgetRepo,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val topCategories = vm.uiState.value.topCategories
        assertEquals(2, topCategories.size)

        val groceries = topCategories.first { it.category == "Groceries" }
        assertNotNull(groceries.budgetProgress)
        assertEquals(5000.0, groceries.budgetProgress!!.budget, 0.001)
        assertEquals(4000.0, groceries.budgetProgress!!.spent, 0.001)
        assertEquals(80.0, groceries.budgetProgress!!.percentUsed, 0.001)
        assertEquals(BudgetState.WARNING, groceries.budgetProgress!!.state)

        val food = topCategories.first { it.category == "Food & Drink" }
        assertNull(food.budgetProgress)
    }

    @Test
    fun testReceivables_mixedCaseNamesMerge_andCustomSplitDoesNotDivideEquallyByHeadCount() = runTest {
        val now = System.currentTimeMillis()
        val tRepo = FakeTransactionRepo()

        // Two transactions with mixed-case companion names: "Alice" and "alice"
        tRepo.insertTransaction(
            Transaction(
                id = "txn_alice_1",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Cafe",
                category = "Food & Drink",
                totalAmount = 200.0,
                companions = "Alice",
                myShare = 100.0,
                owedToMe = 100.0,
                status = TransactionStatus.PENDING
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )
        tRepo.insertTransaction(
            Transaction(
                id = "txn_alice_2",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Dinner",
                category = "Food & Drink",
                totalAmount = 100.0,
                companions = "alice",
                myShare = 50.0,
                owedToMe = 50.0,
                status = TransactionStatus.PENDING
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )

        // Custom split with multiple companions where individual shares are not stored
        // Total = 300, myShare = 150, owedToMe = 150. Expected equal share would be 100.
        tRepo.insertTransaction(
            Transaction(
                id = "txn_custom",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "UPI",
                merchant = "Hotel",
                category = "Food & Drink",
                totalAmount = 300.0,
                companions = "Bob, Charlie",
                myShare = 150.0,
                owedToMe = 150.0,
                status = TransactionStatus.PENDING
            ,
                reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED)
        )

        val vm = DashboardViewModel(
            transactionRepository = tRepo,
            directSyncQueueDao = FakeSyncQueueDao(),
            directPayableDao = null,
            directPendingCaptureDao = FakePendingCaptureDao(),
            categoryRepository = FakeCategoryRepo(),
            budgetRepository = null,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val owedList = vm.uiState.value.topOwedToMe
        val aliceEntries = owedList.filter { it.name.equals("Alice", ignoreCase = true) }

        // Mixed-case names must merge into a single entry with sum of 150.0
        assertEquals("Mixed-case names should merge into a single entry", 1, aliceEntries.size)
        assertEquals("Merged Alice balance should be 150.0", 150.0, aliceEntries[0].amount, 0.001)

        // Custom split must NOT divide equally by head count (Bob and Charlie must not each get 75.0)
        val bobEntry = owedList.find { it.name.equals("Bob", ignoreCase = true) }
        val charlieEntry = owedList.find { it.name.equals("Charlie", ignoreCase = true) }
        assertTrue("Custom split should not divide equally by head count for Bob", bobEntry == null || bobEntry.amount != 75.0)
        assertTrue("Custom split should not divide equally by head count for Charlie", charlieEntry == null || charlieEntry.amount != 75.0)

        // Custom split gives one combined row labelled "Bob, Charlie" with full owed amount
        val bobCharlieEntry = owedList.find { it.name == "Bob, Charlie" }
        assertNotNull("Custom split should give one row labelled 'Bob, Charlie'", bobCharlieEntry)
        assertEquals("Combined group row should have full owed amount 150.0", 150.0, bobCharlieEntry!!.amount, 0.001)

        // No row should ever be named Others
        val othersEntry = owedList.find { it.name.equals("Others", ignoreCase = true) }
        assertNull("No row should ever be named Others", othersEntry)
    }
}
