package com.financeautomation.data

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

class TransactionRepositorySyncTest {

    private class FakeTransactionDao : TransactionDao {
        val transactions = ConcurrentHashMap<String, TransactionEntity>()

        override fun getTransactionsFlow(): Flow<List<TransactionEntity>> = flowOf(transactions.values.toList())
        override fun getNeedsReviewFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override fun getConfirmedUnsyncedFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override suspend fun getById(id: String): TransactionEntity? = transactions[id]
        override suspend fun getRecentSimilar(minTimestamp: Long, maxTimestamp: Long, minAmount: Double, maxAmount: Double): List<TransactionEntity> = emptyList()
        override suspend fun insert(transaction: TransactionEntity) { transactions[transaction.id] = transaction }
        override suspend fun insertAll(transactions: List<TransactionEntity>) { transactions.forEach { insert(it) } }
        override suspend fun update(transaction: TransactionEntity) { transactions[transaction.id] = transaction }
        override suspend fun delete(transaction: TransactionEntity) { transactions.remove(transaction.id) }
        override suspend fun deleteById(id: String) { transactions.remove(id) }
        override suspend fun voidTransaction(id: String, voidTitle: String) {}
        override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {
            transactions[id]?.let {
                transactions[id] = it.copy(syncState = syncState, sheetRowIndex = sheetRowIndex ?: it.sheetRowIndex)
            }
        }
        override suspend fun updateReviewState(id: String, reviewState: String, category: String?) {
            transactions[id]?.let { transactions[id] = it.copy(reviewState = reviewState) }
        }
    }

    private class FakeSyncQueueDao : SyncQueueDao {
        val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
        private val idGen = AtomicInteger(1)

        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override suspend fun insert(item: SyncQueueEntity): Long {
            val id = if (item.id == 0L) idGen.getAndIncrement().toLong() else item.id
            val stored = item.copy(id = id)
            queue[id] = stored
            return id
        }
        override suspend fun update(item: SyncQueueEntity) { queue[item.id] = item }
        override suspend fun delete(item: SyncQueueEntity) { queue.remove(item.id) }
        override suspend fun deleteById(id: Long) { queue.remove(id) }
        override suspend fun deleteByTransactionId(transactionId: String) {
            queue.values.filter { it.transactionId == transactionId }.forEach { queue.remove(it.id) }
        }
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? =
            queue.values.firstOrNull { it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> =
            queue.values.filter { it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> =
            queue.values.filter { it.status == SyncQueueEntity.STATUS_PENDING }.sortedBy { it.createdAt }
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement) }
        }
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount) }
        }
        override suspend fun countPendingOperations(opType: String): Int =
            queue.values.count { it.operationType == opType && it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun updatePlanJson(id: Long, planJson: String?) {
            queue[id]?.let { queue[id] = it.copy(planJson = planJson) }
        }
    }

    private class FakePreferencesRepository(private val spreadsheetId: String = "test_sheet_id") : PreferencesRepository {
        override fun getSpreadsheetId(): Flow<String?> = flowOf(spreadsheetId)
        override suspend fun getSpreadsheetIdSync(): String? = spreadsheetId
        override suspend fun setSpreadsheetId(id: String) {}
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(spreadsheetId)
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = spreadsheetId
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {}
        override fun getDefaultAccount(): Flow<String> = flowOf("SBI")
        override suspend fun getDefaultAccountSync(): String = "SBI"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(trip: String?) {}
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
        override suspend fun getGoogleAccountEmailSync(): String? = null
        override suspend fun setGoogleAccountEmail(email: String?) {}
        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(5)
        override suspend fun getSettleDelaySecondsSync(): Int = 5
        override suspend fun setSettleDelaySeconds(seconds: Int) {}
        override suspend fun getOrCreateDeviceId(): String = "device_123"
        override suspend fun getDeviceIdSync(): String? = "device_123"
        override suspend fun getPreference(key: String): String? = null
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {}
        override suspend fun deletePreference(key: String) {}
    }

    private class CountingSheetsMock {
        val appendDebitCount = AtomicInteger(0)
        val writtenUuids = ConcurrentHashMap<String, String>() // cell -> uuid

        val fakeTokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }

        fun createClient(): GoogleSheetsClient {
            val interceptor = Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val method = request.method

                val responseBody: String = when {
                    // values:batchGet (readLedgerSnapshot)
                    method == "GET" && url.contains("values:batchGet") -> {
                        """{"valueRanges":[
                            {"range":"'Nov26'!A4:K","majorDimension":"ROWS","values":[]},
                            {"range":"'Nov26'!AC4:AC","majorDimension":"ROWS","values":[]},
                            {"range":"'Nov26'!V4:AB","majorDimension":"ROWS","values":[]},
                            {"range":"'Nov26'!AE4:AE","majorDimension":"ROWS","values":[]}
                        ]}"""
                    }
                    // findRowByUuid: AC3:AC
                    method == "GET" && (url.contains("AC3") || url.contains("AC")) && url.contains("/values/") -> {
                        val matchingEntries = writtenUuids.entries.filter { it.key.startsWith("AC") }
                        if (matchingEntries.isEmpty()) {
                            """{"range":"'Nov26'!AC3:AC","majorDimension":"ROWS","values":[]}"""
                        } else {
                            val rows = (3..10).map { rowIdx ->
                                val uuid = writtenUuids["AC$rowIdx"] ?: ""
                                if (uuid.isNotEmpty()) "[\"$uuid\"]" else "[\"\"]"
                            }
                            """{"range":"'Nov26'!AC3:AC","majorDimension":"ROWS","values":[${rows.joinToString(",")}]}"""
                        }
                    }
                    // findOrphanDebitRow or table range read
                    method == "GET" && url.contains("/values/") -> {
                        """{"range":"'Nov26'!A3:K","majorDimension":"ROWS","values":[]}"""
                    }
                    // All metadata GET requests (tabs, grid capacity, sheet properties)
                    method == "GET" && !url.contains("/values/") -> {
                        """{"spreadsheetId":"test_sheet_id","sheets":[{"properties":{"sheetId":0,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}"""
                    }
                    // appendTableScoped (appendDebit)
                    method == "POST" && url.contains(":append") -> {
                        val count = appendDebitCount.incrementAndGet()
                        val rowNum = 3 + count
                        """{"spreadsheetId":"test_sheet_id","updates":{"updatedRange":"'Nov26'!A$rowNum:K$rowNum"}}"""
                    }
                    // updateCell (AC rowIdx)
                    method == "PUT" && url.contains("/values/") -> {
                        val cellMatch = Regex("""'Nov26'!([A-Z0-9]+)""").find(url)
                        val cellRef = cellMatch?.groupValues?.get(1) ?: "AC4"
                        val bodyText = request.body?.let { b ->
                            val buf = okio.Buffer()
                            b.writeTo(buf)
                            buf.readUtf8()
                        }.orEmpty()
                        val uuidVal = Regex(""""values":\s*\[\s*\[\s*"([^"]+)""").find(bodyText)?.groupValues?.get(1).orEmpty()
                        writtenUuids[cellRef] = uuidVal
                        """{"updatedCells":1}"""
                    }
                    // batchUpdate (sheets or values)
                    method == "POST" && url.contains("batchUpdate") -> {
                        """{"totalUpdatedCells":0,"replies":[]}"""
                    }
                    else -> "{}"
                }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

            return GoogleSheetsClient(okHttpClient, fakeTokenProvider)
        }
    }

    @Test
    fun testSplitExpenseYieldsExactlyOneAppendDebit() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        // Split transaction: 300 total, 100 my share, 200 owed, two companions
        val splitTxn = Transaction(
            id = "txn_dinner_test_1234567890abcdef",
            timestamp = 1794387699000L, // Nov 2026
            dateSerial = 46338,
            timeSerial = 0.598,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food & Drink",
            totalAmount = 300.0,
            companions = "AliceBobk, Charlie",
            myShare = 100.0,
            owedToMe = 200.0,
            status = TransactionStatus.PENDING,
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING,
            sheetRowIndex = null,
            isPossibleDuplicate = false
        )

        val insertRes = repo.insertTransaction(splitTxn)

        // Trigger sync
        val syncResult = repo.syncPendingTransactions()
        assertTrue(syncResult is Result.Success)
        assertEquals(1, (syncResult as Result.Success).data)

        // Verify exactly ONE appendDebit occurred for the split expense
        assertEquals("Split expense must yield exactly ONE appendDebit call", 1, mock.appendDebitCount.get())
    }

    @Test
    fun testJustMeExpenseYieldsExactlyOneAppendDebit() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        // Just Me transaction: 10 total, 10 my share, 0 owed, Personal
        val justMeTxn = Transaction(
            id = "txn_chai_test_1234567890abcdef",
            timestamp = 1794387699000L, // Nov 2026
            dateSerial = 46338,
            timeSerial = 0.598,
            account = "SBI",
            merchant = "Chai",
            tripTag = "",
            category = "Food & Drink",
            totalAmount = 10.0,
            companions = "Personal",
            myShare = 10.0,
            owedToMe = 0.0,
            status = TransactionStatus.PERSONAL,
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING,
            sheetRowIndex = null,
            isPossibleDuplicate = false
        )

        repo.insertTransaction(justMeTxn)

        val syncResult = repo.syncPendingTransactions()
        assertTrue(syncResult is Result.Success)
        assertEquals(1, (syncResult as Result.Success).data)

        assertEquals("Just Me expense must yield exactly ONE appendDebit call", 1, mock.appendDebitCount.get())
    }

    @Test
    fun testConcurrentSyncOnSplitExpenseYieldsExactlyOneAppendDebit() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val splitTxn = Transaction(
            id = "txn_dinner_concurrent_1234567890",
            timestamp = 1794387699000L,
            dateSerial = 46338,
            timeSerial = 0.598,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food & Drink",
            totalAmount = 300.0,
            companions = "AliceBobk, Charlie",
            myShare = 100.0,
            owedToMe = 200.0,
            status = TransactionStatus.PENDING,
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING,
            sheetRowIndex = null,
            isPossibleDuplicate = false
        )

        val insertRes = repo.insertTransaction(splitTxn)

        // Launch two concurrent sync operations (simulating ViewModel syncNow + Expedited Worker)
        val sync1 = async(Dispatchers.IO) { repo.syncPendingTransactions() }
        val sync2 = async(Dispatchers.IO) { repo.syncPendingTransactions() }

        sync1.await()
        sync2.await()

        // Concurrent sync MUST NOT duplicate the split transaction
        assertEquals("Concurrent sync must yield exactly ONE appendDebit call", 1, mock.appendDebitCount.get())
    }

    @Test
    fun testRetrySyncOnSplitExpenseYieldsExactlyOneAppendDebit() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val splitTxn = Transaction(
            id = "txn_dinner_retry_1234567890abcdef",
            timestamp = 1794387699000L,
            dateSerial = 46338,
            timeSerial = 0.598,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food & Drink",
            totalAmount = 300.0,
            companions = "AliceBobk, Charlie",
            myShare = 100.0,
            owedToMe = 200.0,
            status = TransactionStatus.PENDING,
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING,
            sheetRowIndex = null,
            isPossibleDuplicate = false
        )

        val insertRes = repo.insertTransaction(splitTxn)

        // First sync
        val sync1 = repo.syncPendingTransactions()
        assertTrue(sync1 is Result.Success)

        // Simulate retry: re-insert item into queue with same transaction id
        qDao.insert(
            SyncQueueEntity(
                transactionId = splitTxn.id,
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = splitTxn.id
            )
        )

        // Second sync (retry)
        val sync2 = repo.syncPendingTransactions()
        assertTrue(sync2 is Result.Success)

        // Verify deduplication: retry MUST NOT append a second debit row
        assertEquals("Retry sync must yield exactly ONE appendDebit call via UUID deduplication", 1, mock.appendDebitCount.get())
    }
}
