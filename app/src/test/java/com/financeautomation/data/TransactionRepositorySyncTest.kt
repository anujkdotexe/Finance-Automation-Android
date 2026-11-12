package com.financeautomation.data

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.entity.LineageEntity
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.LogCreditRequest
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
import org.junit.Assert.assertNotNull
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
        override suspend fun getBySheetRowIndex(rowIndex: Int): TransactionEntity? =
            transactions.values.firstOrNull { it.sheetRowIndex == rowIndex }
        override suspend fun getRecentSimilar(minTimestamp: Long, maxTimestamp: Long, minAmount: Double, maxAmount: Double): List<TransactionEntity> = emptyList()
        override suspend fun insert(transaction: TransactionEntity) { transactions[transaction.id] = transaction }
        override suspend fun insertAll(transactions: List<TransactionEntity>) { transactions.forEach { insert(it) } }
        override suspend fun update(transaction: TransactionEntity) { transactions[transaction.id] = transaction }
        override suspend fun delete(transaction: TransactionEntity) { transactions.remove(transaction.id) }
        override suspend fun deleteById(id: String) { transactions.remove(id) }
        override suspend fun voidTransaction(id: String, voidTitle: String) {
            transactions[id]?.let {
                transactions[id] = it.copy(
                    totalAmount = 0.0,
                    myShare = 0.0,
                    owedToMe = 0.0,
                    status = "Void",
                    syncState = SyncState.VOID.name,
                    merchant = voidTitle
                )
            }
        }
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
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(queue.values.filter { it.status == SyncQueueEntity.STATUS_FAILED || it.retryCount >= 8 })
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

    private class FakePayableDao : PayableDao {
        val payables = ConcurrentHashMap<String, PayableEntity>()

        override fun observeAll(): Flow<List<PayableEntity>> = flowOf(payables.values.toList())
        override fun observePending(): Flow<List<PayableEntity>> = flowOf(
            payables.values.filter { it.repaymentStatus.equals("pending", ignoreCase = true) }
        )
        override suspend fun getPending(): List<PayableEntity> =
            payables.values.filter { it.repaymentStatus.equals("pending", ignoreCase = true) }
        override suspend fun getAll(): List<PayableEntity> = payables.values.toList()
        override suspend fun getByUuid(uuid: String): PayableEntity? = payables[uuid]
        override suspend fun upsert(payable: PayableEntity) { payables[payable.uuid] = payable }
        override suspend fun upsertAll(payablesList: List<PayableEntity>) {
            payablesList.forEach { payables[it.uuid] = it }
        }
        override suspend fun deleteByUuid(uuid: String) { payables.remove(uuid) }
        override suspend fun deleteAll() { payables.clear() }
    }

    private class FakeLineageDao : LineageDao {
        val records = ConcurrentHashMap<String, LineageEntity>()
        override suspend fun insert(entity: LineageEntity) { records[entity.uuid] = entity }
        override suspend fun insertAll(entities: List<LineageEntity>) { entities.forEach { insert(it) } }
        override suspend fun getByUuid(uuid: String): LineageEntity? = records[uuid]
        override suspend fun getByCarriedFrom(carriedFromUuid: String): LineageEntity? =
            records.values.firstOrNull { it.carriedFromUuid == carriedFromUuid }
        override suspend fun getAll(): List<LineageEntity> = records.values.toList()
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
        var customBatchGetResponse: String? = null
        var failBatchGetOnce: Boolean = false

        val fakeTokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }

        fun createClient(): GoogleSheetsClient {
            val interceptor = Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val method = request.method

                if (method == "GET" && url.contains("values:batchGet") && failBatchGetOnce) {
                    failBatchGetOnce = false
                    return@Interceptor Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("Service Unavailable".toResponseBody(JSON_MEDIA_TYPE))
                        .build()
                }

                val responseBody: String = when {
                    // values:batchGet (readLedgerSnapshot)
                    method == "GET" && url.contains("values:batchGet") -> {
                        customBatchGetResponse ?: """{"valueRanges":[
                            {"range":"'Nov26'!A4:K","majorDimension":"ROWS","values":[]},
                            {"range":"'Nov26'!AC4:AC","majorDimension":"ROWS","values":[]},
                            {"range":"'Nov26'!M4:T","majorDimension":"ROWS","values":[]},
                            {"range":"'Nov26'!AD4:AD","majorDimension":"ROWS","values":[]},
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
                    // getCellString for AC column
                    method == "GET" && url.contains("!AC") -> {
                        val row = Regex("""AC(\d+)""").find(url)?.groupValues?.get(1) ?: "4"
                        val uuid = writtenUuids["AC$row"] ?: "txn_test_void"
                        """{"range":"'Nov26'!AC$row","majorDimension":"ROWS","values":[["$uuid"]]}"""
                    }
                    // findOrphanDebitRow or table range read
                    method == "GET" && url.contains("/values/") -> {
                        """{"range":"'Nov26'!A3:K","majorDimension":"ROWS","values":[]}"""
                    }
                    // All metadata GET requests (tabs, grid capacity, sheet properties)
                    method == "GET" && !url.contains("/values/") -> {
                        """{"spreadsheetId":"test_sheet_id","sheets":[{"properties":{"sheetId":0,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}"""
                    }
                    // appendTableScoped (values.update to row range or legacy append)
                    (method == "POST" && url.contains(":append")) || (method == "PUT" && url.contains("/values/") && (url.contains(":K") || url.contains("%3AK"))) -> {
                        val count = appendDebitCount.incrementAndGet()
                        val rowMatch = Regex("""\d+""").find(url)?.value?.toIntOrNull() ?: (3 + count)
                        """{"spreadsheetId":"test_sheet_id","updatedRange":"'Nov26'!A$rowMatch:K$rowMatch","updatedCells":11}"""
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
                    // values:batchUpdate (atomic table append + uuid or multi-cell edits)
                    method == "POST" && url.contains("values:batchUpdate") -> {
                        val bodyText = request.body?.let { b ->
                            val buf = okio.Buffer()
                            b.writeTo(buf)
                            buf.readUtf8()
                        }.orEmpty()
                        if (bodyText.contains(":K") || bodyText.contains(":k") || bodyText.contains("%3AK")) {
                            appendDebitCount.incrementAndGet()
                        }
                        val acMatch = Regex("""'Nov26'!(AC\d+)""").find(bodyText)
                        val acRef = acMatch?.groupValues?.get(1)
                        if (acRef != null) {
                            val uuidMatch = Regex("""'Nov26'!AC\d+.*?"values":\s*\[\s*\[\s*"([^"]+)"""").find(bodyText)
                                ?: Regex(""""values":\s*\[\s*\[\s*"([^"]+)""").findAll(bodyText).lastOrNull()
                            val uuidVal = uuidMatch?.groupValues?.get(1).orEmpty()
                            writtenUuids[acRef] = uuidVal
                        }
                        """{"totalUpdatedCells":12,"responses":[]}"""
                    }
                    // batchUpdate (sheets metadata or layout)
                    method == "POST" && url.contains("batchUpdate") -> {
                        """{"totalUpdatedCells":0,"replies":[{"addSheet":{"properties":{"sheetId":101,"title":"Dec26"}}}]}"""
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

    @Test
    fun testSettlementAndNettingPlanUpdatesLocalRoomDatabaseMatchingSheet() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val mock = CountingSheetsMock()
        val client = mock.createClient()

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            googleSheetsClient = client,
            preferencesRepository = FakePreferencesRepository("test_spreadsheet_id")
        )

        // Pre-populate Room DB with 2 transactions representing Dinner (row 5) and Lunch (row 7)
        val dinnerTxn = TransactionEntity(
            id = "txn_dinner_row5",
            timestamp = 1794380000000L,
            dateSerial = 46338,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food & Drink",
            totalAmount = 300.0,
            companions = "AliceBobk, Charlie",
            myShare = 100.0,
            owedToMe = 200.0,
            status = "Pending",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED.name,
            syncState = SyncState.SYNCED.name,
            sheetRowIndex = 5,
            isPossibleDuplicate = false
        )
        val lunchTxn = TransactionEntity(
            id = "txn_lunch_row7",
            timestamp = 1794381000000L,
            dateSerial = 46338,
            timeSerial = 0.55,
            account = "SBI",
            merchant = "Lunch",
            tripTag = "",
            category = "Food & Drink",
            totalAmount = 150.0,
            companions = "Charlie",
            myShare = 75.0,
            owedToMe = 75.0,
            status = "Pending",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED.name,
            syncState = SyncState.SYNCED.name,
            sheetRowIndex = 7,
            isPossibleDuplicate = false
        )
        tDao.insert(dinnerTxn)
        tDao.insert(lunchTxn)

        val initialOwed = tDao.transactions.values.sumOf { it.owedToMe }
        assertEquals(275.0, initialOwed, 0.001)

        // Queue a credit/settlement operation with an AccountingPlan applying edits
        val plan = com.financeautomation.domain.model.AccountingPlan(
            edits = listOf(
                com.financeautomation.domain.model.CellEdit(tab = "Nov26", row = 5, column = "H", value = "AliceBobk"),
                com.financeautomation.domain.model.CellEdit(tab = "Nov26", row = 5, column = "J", value = 62.5),
                com.financeautomation.domain.model.CellEdit(tab = "Nov26", row = 5, column = "K", value = "Pending"),
                com.financeautomation.domain.model.CellEdit(tab = "Nov26", row = 7, column = "J", value = 37.5),
                com.financeautomation.domain.model.CellEdit(tab = "Nov26", row = 7, column = "K", value = "Pending")
            ),
            settled = listOf(
                com.financeautomation.domain.model.SettledRef(tab = "Nov26", row = 5, uuid = dinnerTxn.id, applied = 137.5, remaining = 62.5),
                com.financeautomation.domain.model.SettledRef(tab = "Nov26", row = 7, uuid = lunchTxn.id, applied = 37.5, remaining = 37.5)
            )
        )
        val planJson = com.financeautomation.data.remote.AccountingPlanJsonAdapter.toJson(plan)

        qDao.insert(
            SyncQueueEntity(
                transactionId = dinnerTxn.id,
                operationType = SyncQueueEntity.OP_VOID, // Triggers applyPlan with planJson
                payloadJson = dinnerTxn.id,
                planJson = planJson
            )
        )

        // Execute sync
        val syncRes = repo.syncPendingTransactions()
        assertTrue(syncRes is Result.Success)

        // Verify local Room DB transactions updated strictly matching sheet plan edits
        val updatedDinner = tDao.getById(dinnerTxn.id)
        val updatedLunch = tDao.getById(lunchTxn.id)

        org.junit.Assert.assertNotNull(updatedDinner)
        org.junit.Assert.assertNotNull(updatedLunch)

        assertEquals("AliceBobk", updatedDinner!!.companions)
        assertEquals(62.5, updatedDinner.owedToMe, 0.001)
        assertEquals("Pending", updatedDinner.status)

        assertEquals(37.5, updatedLunch!!.owedToMe, 0.001)
        assertEquals("Pending", updatedLunch.status)

        val finalOwed = tDao.transactions.values.sumOf { it.owedToMe }
        assertEquals("Total local owed must equal 100.0 matching sheet state (62.5 + 37.5)", 100.0, finalOwed, 0.001)
    }

    @Test
    fun testReconcileWithSheet_healsStaleLocalDebitsAndPayables() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()

        // Stale local transactions:
        // Dinner local has owed=125, companions="AliceBobk, Charlie"
        val staleDinner = Transaction(
            id = "txn_dinner_uuid_123",
            timestamp = 1794387699000L,
            dateSerial = 46338,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Dinner",
            category = "Food & Drink",
            totalAmount = 200.0,
            companions = "AliceBobk, Charlie",
            myShare = 75.0,
            owedToMe = 125.0,
            status = TransactionStatus.PENDING,
            syncState = SyncState.SYNCED,
            reviewState = ReviewState.CONFIRMED,
            sheetRowIndex = 5
        )
        // Lunch local has owed=37.5, status=Pending
        val staleLunch = Transaction(
            id = "txn_lunch_uuid_456",
            timestamp = 1794387699000L,
            dateSerial = 46338,
            timeSerial = 0.4,
            account = "SBI",
            merchant = "Lunch",
            category = "Food & Drink",
            totalAmount = 75.0,
            companions = "Charlie",
            myShare = 37.5,
            owedToMe = 37.5,
            status = TransactionStatus.PENDING,
            syncState = SyncState.SYNCED,
            reviewState = ReviewState.CONFIRMED,
            sheetRowIndex = 4
        )
        tDao.insert(TransactionEntity.fromDomain(staleDinner))
        tDao.insert(TransactionEntity.fromDomain(staleLunch))

        // Remote sheet has Dinner with owed=62.5 and companions="AliceBobk", Lunch settled to 0.0,
        // and payables: Coffee (60.0) and Excess credit (5061.5)
        mock.customBatchGetResponse = """{
            "valueRanges": [
                {
                    "range": "'Nov26'!A4:K",
                    "majorDimension": "ROWS",
                    "values": [
                        [46338, 0.4, "SBI", "Lunch", "", "Food & Drink", 75.0, "Charlie", 37.5, 0.0, "Settled"],
                        [46338, 0.5, "SBI", "Dinner", "", "Food & Drink", 200.0, "AliceBobk", 75.0, 62.5, "Pending"]
                    ]
                },
                {
                    "range": "'Nov26'!AC4:AC",
                    "majorDimension": "ROWS",
                    "values": [
                        ["txn_lunch_uuid_456"],
                        ["txn_dinner_uuid_123"]
                    ]
                },
                {
                    "range": "'Nov26'!M4:T",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!AD4:AD",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!V4:AB",
                    "majorDimension": "ROWS",
                    "values": [
                        [46338, 0.5, "Charlie", "Excess settlement credit", 5061.5, "", "Pending"],
                        [46338, 0.6, "Charlie", "Coffee", 60.0, "", "Pending"]
                    ]
                },
                {
                    "range": "'Nov26'!AE4:AE",
                    "majorDimension": "ROWS",
                    "values": [
                        ["pay_excess_uuid_789"],
                        ["pay_coffee_uuid_012"]
                    ]
                }
            ]
        }"""

        val sheets = mock.createClient()
        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        // Execute reconcileWithSheet
        val reconcileRes = repo.reconcileWithSheet("test_sheet_id")
        assertTrue(reconcileRes is Result.Success)

        // Verify local debits are brought to sheet values
        val healedDinner = tDao.getById("txn_dinner_uuid_123")
        org.junit.Assert.assertNotNull(healedDinner)
        assertEquals(62.5, healedDinner!!.owedToMe, 0.001)
        assertEquals("AliceBobk", healedDinner.companions)
        assertEquals("Pending", healedDinner.status)

        val healedLunch = tDao.getById("txn_lunch_uuid_456")
        org.junit.Assert.assertNotNull(healedLunch)
        assertEquals(0.0, healedLunch!!.owedToMe, 0.001)
        assertEquals("Settled", healedLunch.status)

        // Verify payables are synced to local DB
        val payables = pDao.getAll()
        assertEquals(2, payables.size)
        val totalIOwe = pDao.getPending().sumOf { it.amountIOwe }
        assertEquals(5121.5, totalIOwe, 0.001)
    }

    @Test
    fun testFailedSnapshotRead_doesNotSavePlanAndRetries() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheets,
            googleDriveClient = GoogleDriveClient(okHttpClient = OkHttpClient(), tokenProvider = mock.fakeTokenProvider),
            syncQueueDao = qDao,
            lineageDao = object : LineageDao {
                override suspend fun insert(entity: LineageEntity) {}
                override suspend fun insertAll(entities: List<LineageEntity>) {}
                override suspend fun getByUuid(uuid: String): LineageEntity? = null
                override suspend fun getByCarriedFrom(carriedFromUuid: String): LineageEntity? = null
                override suspend fun getAll(): List<LineageEntity> = emptyList()
            },
            transactionDao = tDao
        )

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = executor,
            ioDispatcher = Dispatchers.IO
        )

        // Queue item for Charlie paying 50.0
        val creditReq = LogCreditRequest(
            requestId = "inf_req_1",
            settler = "Charlie",
            amount = 50.0,
            account = "SBI",
            inflowType = "Friend Settlement",
            reason = "Lunch settlement",
            timestamp = 1794387699000L
        )
        val queueItem = SyncQueueEntity(
            id = 1L,
            transactionId = "inf_charlie_50",
            operationType = SyncQueueEntity.OP_LOG_CREDIT,
            payloadJson = AccountingRequestJsonAdapter.creditRequestToJson(creditReq),
            status = SyncQueueEntity.STATUS_PENDING,
            retryCount = 0
        )
        qDao.insert(queueItem)

        // Setup snapshot data with an open debt from Charlie
        mock.customBatchGetResponse = """{
            "valueRanges": [
                {
                    "range": "'Nov26'!A4:K",
                    "majorDimension": "ROWS",
                    "values": [
                        [46338, 0.4, "SBI", "Lunch", "", "Food & Drink", 75.0, "Charlie", 37.5, 37.5, "Pending"]
                    ]
                },
                {
                    "range": "'Nov26'!AC4:AC",
                    "majorDimension": "ROWS",
                    "values": [["txn_lunch_123"]]
                },
                {
                    "range": "'Nov26'!M4:T",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!AD4:AD",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!V4:AB",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!AE4:AE",
                    "majorDimension": "ROWS",
                    "values": []
                }
            ]
        }"""

        // First attempt: simulate 503 Service Unavailable on readLedgerSnapshot
        mock.failBatchGetOnce = true
        val syncResult1 = repo.syncPendingTransactions()

        // With the bug: snapshot error was swallowed into emptyList(), so an unlinked plan was generated and saved
        // With the fix: Result.Error is returned, no plan is saved, item is marked for retry
        val queueItemAfterFail = qDao.queue[1L]
        org.junit.Assert.assertNotNull(queueItemAfterFail)
        org.junit.Assert.assertNull(
            "Plan must not be saved when snapshot read fails!",
            queueItemAfterFail?.planJson
        )
        assertEquals(SyncQueueEntity.STATUS_PENDING, queueItemAfterFail?.status)
        assertEquals(1, queueItemAfterFail?.retryCount)

        // Second attempt: succeeds
        val syncResult2 = repo.syncPendingTransactions()
        assertTrue("Sync should succeed on retry", syncResult2 is Result.Success)
        // Queue item deleted after successful sync
        org.junit.Assert.assertNull(qDao.queue[1L])
    }

    @Test
    fun testCarryForward_ensuresTargetTabExists_andCarriesUnsettledRows() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val lineageDao = FakeLineageDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheets,
            googleDriveClient = GoogleDriveClient(okHttpClient = OkHttpClient(), tokenProvider = mock.fakeTokenProvider),
            syncQueueDao = qDao,
            lineageDao = lineageDao,
            transactionDao = tDao
        )

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = executor,
            ioDispatcher = Dispatchers.IO
        )

        // Setup snapshot data with Nov26 tab containing 1 pending debit, 1 settled debit, 1 pending payable
        mock.customBatchGetResponse = """{
            "valueRanges": [
                {
                    "range": "'Nov26'!A4:K",
                    "majorDimension": "ROWS",
                    "values": [
                        [46338, 0.4, "SBI", "Dinner", "", "Food & Drink", 100.0, "Charlie", 50.0, 50.0, "Pending"],
                        [46338, 0.5, "SBI", "Lunch", "", "Food & Drink", 50.0, "Bob", 50.0, 0.0, "Settled"]
                    ]
                },
                {
                    "range": "'Nov26'!AC4:AC",
                    "majorDimension": "ROWS",
                    "values": [["txn_dinner_123"], ["txn_lunch_456"]]
                },
                {
                    "range": "'Nov26'!M4:T",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!AD4:AD",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!V4:AB",
                    "majorDimension": "ROWS",
                    "values": [
                        [46338, 0.6, "Bob", "Movie ticket", 200.0, "", "Pending"]
                    ]
                },
                {
                    "range": "'Nov26'!AE4:AE",
                    "majorDimension": "ROWS",
                    "values": [["pay_movie_789"]]
                }
            ]
        }"""

        // Trigger carry forward for Dec26
        val cfRes = repo.carryForward(targetTab = "Dec26")
        assertTrue(cfRes is Result.Success)

        // Queue item should exist
        val pendingQueue = qDao.getAllPending(8)
        assertEquals(1, pendingQueue.size)
        assertEquals(SyncQueueEntity.OP_CARRY_FORWARD, pendingQueue[0].operationType)

        // Execute sync
        val syncRes = repo.syncPendingTransactions()
        assertTrue("Carry forward sync must succeed", syncRes is Result.Success)

        // Queue item should be deleted on success
        val remainingQueue = qDao.getAllPending(8)
        assertTrue("Queue must be empty after successful rollover", remainingQueue.isEmpty())

        // Lineage records created for the carried debit and payable
        val lineage = lineageDao.getAll()
        assertEquals(2, lineage.size)
        assertTrue(lineage.any { it.carriedFromUuid == "txn_dinner_123" })
        assertTrue(lineage.any { it.carriedFromUuid == "pay_movie_789" })
    }

    @Test
    fun testCarryForward_retriesOnTransportFailure() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val lineageDao = FakeLineageDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheets,
            googleDriveClient = GoogleDriveClient(okHttpClient = OkHttpClient(), tokenProvider = mock.fakeTokenProvider),
            syncQueueDao = qDao,
            lineageDao = lineageDao,
            transactionDao = tDao
        )

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = executor,
            ioDispatcher = Dispatchers.IO
        )

        // Enqueue carry forward
        repo.carryForward(targetTab = "Dec26")
        val queueItem = qDao.getAllPending(8).first()

        // Simulate transport failure on first sync attempt
        mock.failBatchGetOnce = true
        val syncRes1 = repo.syncPendingTransactions()

        // Item must not be deleted and should have retry count incremented
        val queueAfterFail = qDao.queue[queueItem.id]
        org.junit.Assert.assertNotNull(queueAfterFail)
        assertEquals(1, queueAfterFail?.retryCount)
        assertEquals(SyncQueueEntity.STATUS_PENDING, queueAfterFail?.status)
    }

    @Test
    fun testVoidTransaction_syncsVoidEditsToSheetAndUpdatesLocalRoom() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val mock = CountingSheetsMock()
        val client = mock.createClient()
        val prefs = FakePreferencesRepository("test_sheet_id")

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            googleSheetsClient = client,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val txnId = "txn_void_test_123"
        val existingTxn = TransactionEntity(
            id = txnId,
            timestamp = 1794380000000L,
            dateSerial = 46338,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Coffee",
            tripTag = "",
            category = "Food & Drink",
            totalAmount = 150.0,
            companions = "Personal",
            myShare = 150.0,
            owedToMe = 0.0,
            status = "Personal",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED.name,
            syncState = SyncState.SYNCED.name,
            sheetRowIndex = 6,
            isPossibleDuplicate = false
        )
        tDao.insert(existingTxn)
        mock.writtenUuids["AC6"] = txnId

        // Execute void transaction
        val voidRes = repo.voidTransaction(txnId)
        assertTrue(voidRes is Result.Success)

        // Verify local Room DB immediately marks void
        val localAfterVoid = tDao.getById(txnId)
        assertNotNull(localAfterVoid)
        assertEquals(0.0, localAfterVoid!!.totalAmount, 0.001)
        assertEquals(0.0, localAfterVoid.myShare, 0.001)
        assertEquals(0.0, localAfterVoid.owedToMe, 0.001)
        assertEquals("Void", localAfterVoid.status)
        assertEquals("[Void] Coffee", localAfterVoid.merchant)
        assertEquals(SyncState.VOID.name, localAfterVoid.syncState)

        // Verify sync queue has OP_VOID queued
        val pendingOps = qDao.getAllPending(10)
        assertEquals(1, pendingOps.size)
        assertEquals(SyncQueueEntity.OP_VOID, pendingOps.first().operationType)

        // Execute sync
        val syncRes = repo.syncPendingTransactions()
        assertTrue(syncRes is Result.Success)

        // Queue item is cleared
        assertEquals(0, qDao.getAllPending(10).size)

        // Local DB remains void and synced
        val finalTxn = tDao.getById(txnId)
        assertNotNull(finalTxn)
        assertEquals(SyncState.VOID.name, finalTxn!!.syncState)
        assertEquals(0.0, finalTxn.totalAmount, 0.001)
    }

    @Test
    fun testFirstExpenseInNextMonth_rollsOverDebtsAndDoesNotDuplicateOnRepeat() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val lineageDao = FakeLineageDao()
        val prefs = FakePreferencesRepository("test_sheet_id")
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheets,
            googleDriveClient = GoogleDriveClient(okHttpClient = OkHttpClient(), tokenProvider = mock.fakeTokenProvider),
            syncQueueDao = qDao,
            lineageDao = lineageDao,
            transactionDao = tDao
        )

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = executor,
            ioDispatcher = Dispatchers.IO
        )

        // Snapshot has Nov26 with open debit for Charlie (owed 100)
        mock.customBatchGetResponse = """{
            "valueRanges": [
                {
                    "range": "'Nov26'!A4:K",
                    "majorDimension": "ROWS",
                    "values": [
                        [46340, 0.5, "Cash", "Dinner", "", "Food & Drink", 200.0, "Charlie", 100.0, 100.0, "Pending"]
                    ]
                },
                {
                    "range": "'Nov26'!AC4:AC",
                    "majorDimension": "ROWS",
                    "values": [["txn_nov_charlie_1"]]
                },
                {
                    "range": "'Nov26'!M4:T",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!AD4:AD",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!V4:AB",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!AE4:AE",
                    "majorDimension": "ROWS",
                    "values": []
                }
            ]
        }"""

        // Dec 2026 timestamp: 1796120000000L (~Dec 1, 2026)
        val decTxn = TransactionEntity(
            id = "txn_dec_1",
            timestamp = 1796120000000L,
            dateSerial = 46358,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Dec Coffee",
            category = "Food & Drink",
            totalAmount = 50.0,
            companions = "Personal",
            myShare = 50.0,
            owedToMe = 0.0,
            status = "Personal",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED.name,
            syncState = SyncState.PENDING.name,
            sheetRowIndex = null
        )
        tDao.insert(decTxn)
        qDao.insert(SyncQueueEntity(transactionId = decTxn.id, operationType = SyncQueueEntity.OP_INSERT, payloadJson = ""))

        val syncRes = repo.syncPendingTransactions()
        assertTrue("Sync of first Dec26 expense should succeed", syncRes is Result.Success)

        // Lineage records created for carried debit
        val lineage = lineageDao.getAll()
        assertEquals(1, lineage.size)
        assertEquals("txn_nov_charlie_1", lineage[0].carriedFromUuid)

        // After first carry forward, update mock snapshot:
        // Nov26 row status is now "Carried Fwd" and Dec26 contains the carried debit
        mock.customBatchGetResponse = """{
            "valueRanges": [
                {
                    "range": "'Nov26'!A4:K",
                    "majorDimension": "ROWS",
                    "values": [
                        [46340, 0.5, "Cash", "Dinner", "", "Food & Drink", 200.0, "Charlie", 100.0, 100.0, "Carried Fwd"]
                    ]
                },
                {
                    "range": "'Nov26'!AC4:AC",
                    "majorDimension": "ROWS",
                    "values": [["txn_nov_charlie_1"]]
                },
                {
                    "range": "'Nov26'!M4:T",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!AD4:AD",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!V4:AB",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Nov26'!AE4:AE",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Dec26'!A4:K",
                    "majorDimension": "ROWS",
                    "values": [
                        [46358, 0.0, "Cash", "Carried Fwd: Dinner", "", "Food & Drink", 100.0, "Charlie", 0.0, 100.0, "Pending"]
                    ]
                },
                {
                    "range": "'Dec26'!AC4:AC",
                    "majorDimension": "ROWS",
                    "values": [["${lineage[0].uuid}"]]
                },
                {
                    "range": "'Dec26'!M4:T",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Dec26'!AD4:AD",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Dec26'!V4:AB",
                    "majorDimension": "ROWS",
                    "values": []
                },
                {
                    "range": "'Dec26'!AE4:AE",
                    "majorDimension": "ROWS",
                    "values": []
                }
            ]
        }"""

        // Repeating carry-forward does not duplicate
        val repeatRes = repo.carryForward(targetTab = "Dec26")
        assertTrue(repeatRes is Result.Success)
        val repeatSync = repo.syncPendingTransactions()
        assertTrue(repeatSync is Result.Success)

        // Lineage still has only 1 record (no duplicate)
        assertEquals(1, lineageDao.getAll().size)
    }
}
