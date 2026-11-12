package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.TransactionRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(emptyList())
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(emptyList())
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(emptyList())
        override suspend fun insert(item: SyncQueueEntity): Long {
            val id = if (item.id == 0L) idGen.getAndIncrement() else item.id
            queue[id] = item.copy(id = id)
            return id
        }
        override suspend fun update(item: SyncQueueEntity) { queue[item.id] = item }
        override suspend fun delete(item: SyncQueueEntity) { queue.remove(item.id) }
        override suspend fun deleteById(id: Long) { queue.remove(id) }
        override suspend fun deleteByTransactionId(transactionId: String) {}
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = null
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {}
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {}
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
            totalAmount = 500.0,
            companions = "Alice, Bob",
            myShare = 100.0,
            owedToMe = 400.0,
            status = TransactionStatus.PENDING
        )
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
        )
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
        // Current burn must only include foodTxn.myShare (100.0), NOT savings (5000.0)
        assertEquals(100.0, state.currentBurn, 0.001)
        assertEquals(5000.0, state.savings, 0.001)
        assertEquals(400.0, state.owedToMe, 0.001)
        assertEquals(120.0, state.iOwePeople, 0.001)

        // Per-person owed split tokens
        val topOwed = state.topOwedToMe
        assertEquals(2, topOwed.size)
        assertTrue(topOwed.any { it.name == "Alice" && it.amount == 200.0 })
        assertTrue(topOwed.any { it.name == "Bob" && it.amount == 200.0 })
    }
}
