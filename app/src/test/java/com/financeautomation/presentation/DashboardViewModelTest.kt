package com.financeautomation.presentation

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class DashboardViewModelTest {

    private lateinit var fakeTransactionRepository: FakeTransactionRepository
    private lateinit var fakeSyncQueueDao: FakeSyncQueueDao
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() = runBlocking {
        fakeTransactionRepository = FakeTransactionRepository()
        fakeSyncQueueDao = FakeSyncQueueDao()

        val cal = Calendar.getInstance()
        val now = cal.timeInMillis

        val t1 = Transaction(
            id = "t1",
            dateSerial = 46331,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Starbucks",
            category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
            totalAmount = 350.0,
            myShare = 350.0,
            owedToMe = 0.0,
            companions = "Personal",
            status = TransactionStatus.SETTLED,
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING,
            timestamp = now
        )

        val t2 = Transaction(
            id = "t2",
            dateSerial = 46331,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Dinner with Friends",
            category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
            totalAmount = 1500.0,
            myShare = 500.0,
            owedToMe = 1000.0,
            companions = "Aman, Priya",
            status = TransactionStatus.PENDING,
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING,
            timestamp = now
        )

        val t3 = Transaction(
            id = "t3",
            dateSerial = 46331,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Nippon Mutual Fund",
            category = CategoryConstants.CATEGORY_SAVINGS,
            totalAmount = 5000.0,
            myShare = 5000.0,
            owedToMe = 0.0,
            companions = "Personal",
            status = TransactionStatus.SETTLED,
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING,
            timestamp = now
        )

        fakeTransactionRepository.transactionsFlow.value = listOf(t1, t2, t3)

        val q1 = SyncQueueEntity(
            id = 1L,
            transactionId = "t1",
            operationType = SyncQueueEntity.OP_INSERT,
            payloadJson = "{}",
            retryCount = 0,
            createdAt = now,
            status = SyncQueueEntity.STATUS_PENDING
        )
        fakeSyncQueueDao.queueFlow.value = listOf(q1)

        viewModel = DashboardViewModel(
            transactionRepository = fakeTransactionRepository,
            syncQueueDao = fakeSyncQueueDao,
            categoryRepository = null,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testCalculatesCurrentBurnExcludingSavings() {
        val state = viewModel.uiState.value
        // t1 (350) + t2 (500) = 850 living burn. t3 (5000) is Savings, so excluded from living burn.
        assertEquals(850.0, state.currentBurn, 0.01)
    }

    @Test
    fun testCalculatesSavingsCorrectly() {
        val state = viewModel.uiState.value
        // t3 is strictly CategoryConstants.CATEGORY_SAVINGS (5000)
        assertEquals(5000.0, state.savings, 0.01)
    }

    @Test
    fun testCalculatesOwedToMeCorrectly() {
        val state = viewModel.uiState.value
        // t2 owedToMe = 1000.0
        assertEquals(1000.0, state.owedToMe, 0.01)
    }

    @Test
    fun testObservesSyncQueueItems() {
        val state = viewModel.uiState.value
        assertEquals(1, state.syncQueueItems.size)
        assertEquals("t1", state.syncQueueItems[0].transactionId)
        assertEquals(SyncQueueEntity.STATUS_PENDING, state.syncQueueItems[0].status)
    }

    @Test
    fun testSyncNowSuccessUpdatesMessage() = runBlocking {
        fakeTransactionRepository.syncReturnValue = Result.Success(2)
        viewModel.syncNow(context = null)

        val state = viewModel.uiState.value
        assertEquals("Synced 2 transaction(s) to Google Sheets", state.syncMessage)
        assertFalse(state.isSyncing)
    }

    private class FakeTransactionRepository : TransactionRepository {
        val transactionsFlow = MutableStateFlow<List<Transaction>>(emptyList())
        var syncReturnValue: Result<Int> = Result.Success(0)

        override fun getTransactions(): Flow<List<Transaction>> = transactionsFlow.asStateFlow()
        override fun getNeedsReview(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? =
            transactionsFlow.value.firstOrNull { it.id == id }

        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            transactionsFlow.value = transactionsFlow.value + transaction
            return Result.Success(Unit)
        }

        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            transactionsFlow.value = transactionsFlow.value.map { if (it.id == transaction.id) transaction else it }
            return Result.Success(Unit)
        }

        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = syncReturnValue
    }

    private class FakeSyncQueueDao : SyncQueueDao {
        val queueFlow = MutableStateFlow<List<SyncQueueEntity>>(emptyList())

        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = queueFlow.asStateFlow()
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = queueFlow.asStateFlow()
        override suspend fun insert(item: SyncQueueEntity): Long = 1L
        override suspend fun update(item: SyncQueueEntity) {}
        override suspend fun delete(item: SyncQueueEntity) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteByTransactionId(transactionId: String) {}
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = queueFlow.value.firstOrNull()
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = queueFlow.value
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = queueFlow.value
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {}
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {}
    }
}

