package com.financeautomation.data

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class TransactionRepositoryTest {

    private lateinit var fakeTransactionDao: FakeTransactionDao
    private lateinit var fakeSyncQueueDao: FakeSyncQueueDao
    private lateinit var repository: TransactionRepositoryImpl

    private val sampleTxn = Transaction(
        id = "txn_test_001",
        timestamp = 1730811600000L,
        dateSerial = 46331,
        timeSerial = 0.5,
        account = "SBI",
        merchant = "Cafe Coffee Day",
        tripTag = "",
        category = "Food & Drink",
        totalAmount = 220.0,
        companions = "Personal",
        myShare = 220.0,
        owedToMe = 0.0,
        status = TransactionStatus.SETTLED,
        sourcePackage = "com.google.android.apps.nbu.paisa.user",
        reviewState = ReviewState.NEEDS_REVIEW,
        syncState = SyncState.PENDING
    )

    @Before
    fun setUp() {
        fakeTransactionDao = FakeTransactionDao()
        fakeSyncQueueDao = FakeSyncQueueDao()
        repository = TransactionRepositoryImpl(
            transactionDao = fakeTransactionDao,
            syncQueueDao = fakeSyncQueueDao,
            googleSheetsClient = null,
            preferencesRepository = null,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testInsertTransactionPersistsAndEnqueuesSync() = runBlocking {
        val result = repository.insertTransaction(sampleTxn)
        assertTrue(result is Result.Success)

        val stored = repository.getTransactionById("txn_test_001")
        assertNotNull(stored)
        assertEquals("Cafe Coffee Day", stored?.merchant)
        assertEquals(220.0, stored?.totalAmount ?: 0.0, 0.001)

        val pendingQueue = fakeSyncQueueDao.getAllPending()
        assertEquals(1, pendingQueue.size)
        assertEquals("txn_test_001", pendingQueue[0].transactionId)
        assertEquals(SyncQueueEntity.OP_INSERT, pendingQueue[0].operationType)
    }

    @Test
    fun testGetTransactionsFlowEmitsMappedDomainObjects() = runBlocking {
        repository.insertTransaction(sampleTxn)
        val list = repository.getTransactions().first()
        assertEquals(1, list.size)
        assertEquals("txn_test_001", list[0].id)
    }

    @Test
    fun testGetNeedsReviewFlowFiltersCorrectly() = runBlocking {
        repository.insertTransaction(sampleTxn) // NEEDS_REVIEW

        val confirmedTxn = sampleTxn.copy(
            id = "txn_confirmed_002",
            reviewState = ReviewState.CONFIRMED
        )
        repository.insertTransaction(confirmedTxn)

        val needsReviewList = repository.getNeedsReview().first()
        assertEquals(1, needsReviewList.size)
        assertEquals("txn_test_001", needsReviewList[0].id)
    }

    @Test
    fun testUpdateTransactionUpdatesDaoAndEnqueuesQueue() = runBlocking {
        repository.insertTransaction(sampleTxn)
        val updated = sampleTxn.copy(
            category = "Shopping",
            reviewState = ReviewState.CONFIRMED
        )
        val result = repository.updateTransaction(updated)
        assertTrue(result is Result.Success)

        val stored = repository.getTransactionById("txn_test_001")
        assertEquals("Shopping", stored?.category)
        assertEquals(ReviewState.CONFIRMED, stored?.reviewState)

        val queue = fakeSyncQueueDao.getAllPending()
        assertEquals(2, queue.size)
        assertEquals(SyncQueueEntity.OP_UPDATE, queue[1].operationType)
    }

    @Test
    fun testVoidTransactionZeroesSharesAndSetsStatusVoid() = runBlocking {
        repository.insertTransaction(sampleTxn)
        val voidResult = repository.voidTransaction("txn_test_001")
        assertTrue(voidResult is Result.Success)

        val voided = repository.getTransactionById("txn_test_001")
        assertNotNull(voided)
        assertEquals("[Void] Cafe Coffee Day", voided?.merchant)
        assertEquals(0.0, voided?.myShare ?: -1.0, 0.001)
        assertEquals(0.0, voided?.owedToMe ?: -1.0, 0.001)
        assertEquals(TransactionStatus.VOID, voided?.status)
        assertEquals(SyncState.VOID, voided?.syncState)
    }

    @Test
    fun testGetTransactionByIdReturnsMatchingOrNull() = runBlocking {
        repository.insertTransaction(sampleTxn)
        val found = repository.getTransactionById("txn_test_001")
        assertNotNull(found)
        assertEquals("txn_test_001", found?.id)

        val notFound = repository.getTransactionById("non_existent_id")
        assertNull(notFound)
    }

    @Test
    fun testFindRecentSimilarMatchesAmountWithinTolerance() = runBlocking {
        val now = System.currentTimeMillis()
        val recentTxn = sampleTxn.copy(
            id = "txn_recent_003",
            timestamp = now - 5000L,
            totalAmount = 220.005
        )
        repository.insertTransaction(recentTxn)

        val matches = repository.findRecentSimilar(220.0, 60000L)
        assertEquals(1, matches.size)
        assertEquals("txn_recent_003", matches[0].id)
    }

    @Test
    fun testSyncPendingTransactionsUnconfiguredLeavesQueueIntact() = runBlocking {
        val confirmedTxn = sampleTxn.copy(reviewState = ReviewState.CONFIRMED)
        repository.insertTransaction(confirmedTxn)
        assertEquals(1, fakeSyncQueueDao.getAllPending().size)

        // repository has null googleSheetsClient & preferencesRepository
        val syncResult = repository.syncPendingTransactions()
        assertTrue(syncResult is Result.Success)
        assertEquals(0, (syncResult as Result.Success).data)

        // Queue must remain intact; transaction must remain PENDING
        val pendingQueue = fakeSyncQueueDao.getAllPending()
        assertEquals(1, pendingQueue.size)
        assertEquals("txn_test_001", pendingQueue[0].transactionId)
        val storedTxn = repository.getTransactionById("txn_test_001")
        assertEquals(SyncState.PENDING, storedTxn?.syncState)
    }

    @Test
    fun testSyncPendingTransactionsSkipsNeedsReviewItems() = runBlocking {
        // Unconfirmed transaction (sampleTxn has reviewState = NEEDS_REVIEW)
        val fakePrefs = FakePreferencesRepository("test_spreadsheet_id")
        val mockClient = createMockSheetsClient(18)
        val repo = TransactionRepositoryImpl(
            transactionDao = fakeTransactionDao,
            syncQueueDao = fakeSyncQueueDao,
            googleSheetsClient = mockClient,
            preferencesRepository = fakePrefs,
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.insertTransaction(sampleTxn)
        assertEquals(1, fakeSyncQueueDao.getAllPending().size)

        val syncResult = repo.syncPendingTransactions()
        assertTrue(syncResult is Result.Success)
        assertEquals(0, (syncResult as Result.Success).data)

        // Item remains in queue awaiting confirmation
        val pendingQueue = fakeSyncQueueDao.getAllPending()
        assertEquals(1, pendingQueue.size)
        val storedTxn = repo.getTransactionById("txn_test_001")
        assertEquals(SyncState.PENDING, storedTxn?.syncState)
    }

    @Test
    fun testSyncPendingTransactionsProcessesQueueWithConfiguredTarget() = runBlocking {
        val confirmedTxn = sampleTxn.copy(reviewState = ReviewState.CONFIRMED)
        val fakePrefs = FakePreferencesRepository("test_spreadsheet_id")
        val mockClient = createMockSheetsClient(18)
        val repo = TransactionRepositoryImpl(
            transactionDao = fakeTransactionDao,
            syncQueueDao = fakeSyncQueueDao,
            googleSheetsClient = mockClient,
            preferencesRepository = fakePrefs,
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.insertTransaction(confirmedTxn)
        assertEquals(1, fakeSyncQueueDao.getAllPending().size)

        val syncResult = repo.syncPendingTransactions()
        assertTrue(syncResult is Result.Success)
        assertEquals(1, (syncResult as Result.Success).data)

        // Queue must be drained upon genuine remote sync
        assertEquals(0, fakeSyncQueueDao.getAllPending().size)
        val syncedTxn = repo.getTransactionById("txn_test_001")
        assertEquals(SyncState.SYNCED, syncedTxn?.syncState)
        assertEquals(18, syncedTxn?.sheetRowIndex)
    }

    private fun createMockSheetsClient(rowIndexToReturn: Int): GoogleSheetsClient {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val responseBody = """
                {
                    "spreadsheetId": "test_spreadsheet_id",
                    "tableRange": "Nov26!A3:K17",
                    "updates": {
                        "spreadsheetId": "test_spreadsheet_id",
                        "updatedRange": "Nov26!A$rowIndexToReturn:K$rowIndexToReturn",
                        "updatedRows": 1,
                        "updatedColumns": 11,
                        "updatedCells": 11
                    }
                }
            """.trimIndent()

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }
        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return GoogleSheetsClient(okHttpClient)
    }

    private class FakePreferencesRepository(
        initialSpreadsheetId: String? = null
    ) : PreferencesRepository {
        private val spreadsheetIdFlow = MutableStateFlow(initialSpreadsheetId)
        private val defaultAccountFlow = MutableStateFlow("SBI")
        private val activeTripFlow = MutableStateFlow<String?>(null)
        private val googleEmailFlow = MutableStateFlow<String?>("user@example.com")
        private val settleDelayFlow = MutableStateFlow(120)

        override fun getSpreadsheetId(): Flow<String?> = spreadsheetIdFlow.asStateFlow()
        override suspend fun getSpreadsheetIdSync(): String? = spreadsheetIdFlow.value
        override suspend fun setSpreadsheetId(id: String) { spreadsheetIdFlow.value = id }
        override fun getDefaultAccount(): Flow<String> = defaultAccountFlow.asStateFlow()
        override suspend fun getDefaultAccountSync(): String = defaultAccountFlow.value
        override suspend fun setDefaultAccount(account: String) { defaultAccountFlow.value = account }
        override fun getActiveTrip(): Flow<String?> = activeTripFlow.asStateFlow()
        override suspend fun setActiveTrip(trip: String?) { activeTripFlow.value = trip }
        override fun getGoogleAccountEmail(): Flow<String?> = googleEmailFlow.asStateFlow()
        override suspend fun getGoogleAccountEmailSync(): String? = googleEmailFlow.value
        override suspend fun setGoogleAccountEmail(email: String?) { googleEmailFlow.value = email }
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = spreadsheetIdFlow.asStateFlow()
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = spreadsheetIdFlow.value
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) { spreadsheetIdFlow.value = id }
        override fun getSettleDelaySeconds(): Flow<Int> = settleDelayFlow.asStateFlow()
        override suspend fun getSettleDelaySecondsSync(): Int = settleDelayFlow.value
        override suspend fun setSettleDelaySeconds(seconds: Int) { settleDelayFlow.value = seconds }
    }

    // In-memory test doubles for TransactionDao and SyncQueueDao
    private class FakeTransactionDao : TransactionDao {
        private val storage = ConcurrentHashMap<String, TransactionEntity>()
        private val flow = MutableStateFlow<List<TransactionEntity>>(emptyList())

        private fun notifyChange() {
            flow.value = storage.values.sortedByDescending { it.timestamp }
        }

        override fun getTransactionsFlow(): Flow<List<TransactionEntity>> = flow.asStateFlow()

        override fun getNeedsReviewFlow(): Flow<List<TransactionEntity>> {
            val needsReviewFlow = MutableStateFlow<List<TransactionEntity>>(emptyList())
            needsReviewFlow.value = storage.values.filter { it.reviewState == ReviewState.NEEDS_REVIEW.name }
            return needsReviewFlow
        }

        override fun getConfirmedUnsyncedFlow(): Flow<List<TransactionEntity>> {
            val unsyncedFlow = MutableStateFlow<List<TransactionEntity>>(emptyList())
            unsyncedFlow.value = storage.values.filter {
                it.reviewState == ReviewState.CONFIRMED.name && it.syncState == SyncState.PENDING.name
            }
            return unsyncedFlow
        }

        override suspend fun getById(id: String): TransactionEntity? = storage[id]

        override suspend fun getRecentSimilar(
            minTimestamp: Long,
            maxTimestamp: Long,
            minAmount: Double,
            maxAmount: Double
        ): List<TransactionEntity> {
            return storage.values.filter {
                it.timestamp in minTimestamp..maxTimestamp && it.totalAmount in minAmount..maxAmount
            }
        }

        override suspend fun insert(transaction: TransactionEntity) {
            storage[transaction.id] = transaction
            notifyChange()
        }

        override suspend fun insertAll(transactions: List<TransactionEntity>) {
            transactions.forEach { storage[it.id] = it }
            notifyChange()
        }

        override suspend fun update(transaction: TransactionEntity) {
            storage[transaction.id] = transaction
            notifyChange()
        }

        override suspend fun delete(transaction: TransactionEntity) {
            storage.remove(transaction.id)
            notifyChange()
        }

        override suspend fun deleteById(id: String) {
            storage.remove(id)
            notifyChange()
        }

        override suspend fun voidTransaction(id: String, voidTitle: String) {
            val existing = storage[id] ?: return
            storage[id] = existing.copy(
                merchant = voidTitle,
                myShare = 0.0,
                owedToMe = 0.0,
                status = TransactionStatus.VOID.value,
                syncState = SyncState.VOID.name
            )
            notifyChange()
        }

        override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {
            val existing = storage[id] ?: return
            storage[id] = existing.copy(
                syncState = syncState,
                sheetRowIndex = sheetRowIndex ?: existing.sheetRowIndex
            )
            notifyChange()
        }

        override suspend fun updateReviewState(id: String, reviewState: String, category: String?) {
            val existing = storage[id] ?: return
            storage[id] = existing.copy(
                reviewState = reviewState,
                category = category ?: existing.category
            )
            notifyChange()
        }
    }

    private class FakeSyncQueueDao : SyncQueueDao {
        private val queue = mutableListOf<SyncQueueEntity>()
        private var nextId = 1L
        private val flow = MutableStateFlow<List<SyncQueueEntity>>(emptyList())

        private fun notifyChange() {
            flow.value = queue.filter { it.status == SyncQueueEntity.STATUS_PENDING }
        }

        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = flow.asStateFlow()
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = flow.asStateFlow()

        override suspend fun insert(item: SyncQueueEntity): Long {
            val id = nextId++
            queue.add(item.copy(id = id))
            notifyChange()
            return id
        }

        override suspend fun update(item: SyncQueueEntity) {
            val idx = queue.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                queue[idx] = item
                notifyChange()
            }
        }

        override suspend fun delete(item: SyncQueueEntity) {
            queue.removeAll { it.id == item.id }
            notifyChange()
        }

        override suspend fun deleteById(id: Long) {
            queue.removeAll { it.id == id }
            notifyChange()
        }

        override suspend fun deleteByTransactionId(transactionId: String) {
            queue.removeAll { it.transactionId == transactionId }
            notifyChange()
        }

        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? =
            queue.firstOrNull { it.status == SyncQueueEntity.STATUS_PENDING || (it.status == SyncQueueEntity.STATUS_FAILED && it.retryCount < maxRetries) }

        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> =
            queue.filter { it.status == SyncQueueEntity.STATUS_PENDING || (it.status == SyncQueueEntity.STATUS_FAILED && it.retryCount < maxRetries) }

        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> =
            queue.filter { it.status == SyncQueueEntity.STATUS_PENDING || (it.status == SyncQueueEntity.STATUS_FAILED && it.retryCount < maxRetries) }

        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {
            val idx = queue.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val item = queue[idx]
                queue[idx] = item.copy(status = status, retryCount = item.retryCount + retryIncrement)
                notifyChange()
            }
        }

        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {
            val idx = queue.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val item = queue[idx]
                queue[idx] = item.copy(status = status, retryCount = retryCount)
                notifyChange()
            }
        }
    }
}
