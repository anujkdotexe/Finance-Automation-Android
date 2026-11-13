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
import com.financeautomation.data.remote.AccountingPlanJsonAdapter
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.SettledRef
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.model.LogCreditRequest
import com.financeautomation.domain.model.ThemeMode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        override fun getAccountTransactionCounts(from: Long, to: Long): Flow<List<com.financeautomation.data.local.dao.AccountTransactionCount>> = emptyFlow()
        override suspend fun getById(id: String): TransactionEntity? = transactions[id]
        override suspend fun getBySheetRowIndex(rowIndex: Int): TransactionEntity? =
            transactions.values.firstOrNull { it.sheetRowIndex == rowIndex }
        override suspend fun getAllBySheetRowIndex(rowIndex: Int): List<TransactionEntity> =
            transactions.values.filter { it.sheetRowIndex == rowIndex }
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
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(queue.values.toList())
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
        override suspend fun getById(id: Long): SyncQueueEntity? = queue[id]
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
        override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount, lastErrorKind = kind, lastErrorDetail = detail) }
        }
        override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement, lastErrorKind = kind, lastErrorDetail = detail) }
        }
        override suspend fun resetQueueItem(id: Long) {
            queue[id]?.let { queue[id] = it.copy(status = SyncQueueEntity.STATUS_PENDING, retryCount = 0, lastErrorKind = null, lastErrorDetail = null) }
        }
        override suspend fun countPendingOperations(opType: String): Int =
            queue.values.count { it.operationType == opType && it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun updatePlanJson(id: Long, planJson: String?) {
            queue[id]?.let { queue[id] = it.copy(planJson = planJson) }
        }
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {
            queue.values.filter { it.transactionId == oldTransactionId }.forEach {
                queue[it.id] = it.copy(transactionId = newTransactionId)
            }
        }
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {
            queue.values.filter { it.payloadJson.contains(oldId) }.forEach {
                queue[it.id] = it.copy(payloadJson = it.payloadJson.replace(oldId, newId))
            }
        }
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {
            queue.values.filter { it.planJson != null && it.planJson!!.contains(oldId) }.forEach {
                queue[it.id] = it.copy(planJson = it.planJson!!.replace(oldId, newId))
            }
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
        override suspend fun updateUuid(oldUuid: String, newUuid: String) {
            val existing = records.remove(oldUuid)
            if (existing != null) {
                records[newUuid] = existing.copy(uuid = newUuid)
            }
        }
        override suspend fun updateCarriedFromUuid(oldUuid: String, newUuid: String) {
            records.values.filter { it.carriedFromUuid == oldUuid }.forEach {
                records[it.uuid] = it.copy(carriedFromUuid = newUuid)
            }
        }
        override suspend fun updateRootUuid(oldUuid: String, newUuid: String) {
            records.values.filter { it.rootUuid == oldUuid }.forEach {
                records[it.uuid] = it.copy(rootUuid = newUuid)
            }
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
        override fun getDeveloperMode(): Flow<Boolean> = flowOf(false)
        override suspend fun getDeveloperModeSync(): Boolean = false
        override suspend fun setDeveloperMode(enabled: Boolean) {}
        override fun getThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun getThemeModeSync(): ThemeMode = ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun getPreference(key: String): String? = null
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {}
        override suspend fun deletePreference(key: String) {}
    }

    private class CountingSheetsMock {
        val appendDebitCount = AtomicInteger(0)
        val writtenUuids = ConcurrentHashMap<String, String>() // cell -> uuid
        var customBatchGetResponse: String? = null
        var failBatchGetOnce: Boolean = false
        var customGetHandler: ((url: String) -> String?)? = null

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

                val customResp = if (method == "GET") customGetHandler?.invoke(url) else null
                val responseBody: String = if (customResp != null) {
                    customResp
                } else when {
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
                            """{"range":"'Sheet'!AC3:AC","majorDimension":"ROWS","values":[]}"""
                        } else {
                            val maxRow = writtenUuids.keys.mapNotNull { Regex("""\d+""").find(it)?.value?.toIntOrNull() }.maxOrNull() ?: 10
                            val rows = (4..maxRow).map { rowIdx ->
                                val uuid = writtenUuids["AC$rowIdx"] ?: ""
                                if (uuid.isNotEmpty()) "[\"$uuid\"]" else "[\"\"]"
                            }
                            """{"range":"'Sheet'!AC4:AC","majorDimension":"ROWS","values":[${rows.joinToString(",")}]}"""
                        }
                    }
                    // getCellString for AC column
                    method == "GET" && url.contains("!AC") -> {
                        val row = Regex("""AC(\d+)""").find(url)?.groupValues?.get(1) ?: "4"
                        val uuid = writtenUuids["AC$row"] ?: "txn_test_void"
                        """{"range":"'Sheet'!AC$row","majorDimension":"ROWS","values":[["$uuid"]]}"""
                    }
                    // findOrphanDebitRow or table range read
                    method == "GET" && url.contains("/values/") -> {
                        val matchingEntries = writtenUuids.entries.filter { it.key.startsWith("AC") }
                        if (matchingEntries.isEmpty()) {
                            """{"range":"'Sheet'!A3:K","majorDimension":"ROWS","values":[]}"""
                        } else {
                            val maxRow = writtenUuids.keys.mapNotNull { Regex("""\d+""").find(it)?.value?.toIntOrNull() }.maxOrNull() ?: 3
                            val rows = (4..maxRow).map { rowIdx ->
                                val hasEntry = writtenUuids.containsKey("AC$rowIdx")
                                if (hasEntry) "[\"46358\"]" else "[\"\"]"
                            }
                            """{"range":"'Sheet'!A4:A","majorDimension":"ROWS","values":[${rows.joinToString(",")}]}"""
                        }
                    }
                    // All metadata GET requests (tabs, grid capacity, sheet properties)
                    method == "GET" && !url.contains("/values/") -> {
                        """{"spreadsheetId":"test_sheet_id","sheets":[{"properties":{"sheetId":0,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}"""
                    }
                    // appendTableScoped (values.update to row range or legacy append)
                    (method == "POST" && url.contains(":append")) || (method == "PUT" && url.contains("/values/") && (url.contains(":K") || url.contains("%3AK"))) -> {
                        val count = appendDebitCount.incrementAndGet()
                        val rowMatch = Regex("""\d+""").find(url)?.value?.toIntOrNull() ?: (3 + count)
                        """{"spreadsheetId":"test_sheet_id","updatedRange":"'Sheet'!A$rowMatch:K$rowMatch","updatedCells":11}"""
                    }
                    // updateCell (AC rowIdx)
                    method == "PUT" && url.contains("/values/") -> {
                        val cellMatch = Regex("""'[^']+'!([A-Z0-9]+)""").find(url)
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
                        val acMatch = Regex("""'[^']+'!(AC\d+)""").find(bodyText)
                        val acRef = acMatch?.groupValues?.get(1)
                        if (acRef != null) {
                            val uuidMatch = Regex("""'[^']+'!AC\d+.*?"values":\s*\[\s*\[\s*"([^"]+)"""").find(bodyText)
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
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = sheets,
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
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
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = sheets,
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
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
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = sheets,
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
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
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = sheets,
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
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
            preferencesRepository = FakePreferencesRepository("test_spreadsheet_id"),
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = client,
                syncQueueDao = qDao,
                transactionDao = tDao
            )
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
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = sheets,
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
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
    fun testReconcileWithSheet_importsMissingRowsAndCorrectsFields() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()

        // Existing local row with wrong amount, merchant, and category
        val staleRow = Transaction(
            id = "txn_to_correct_1",
            timestamp = 1794387699000L,
            dateSerial = 46338,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Old Merchant",
            category = "Old Category",
            totalAmount = 100.0,
            companions = "Personal",
            myShare = 100.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED,
            syncState = SyncState.SYNCED,
            reviewState = ReviewState.CONFIRMED,
            sheetRowIndex = 4
        )
        tDao.insert(TransactionEntity.fromDomain(staleRow))

        // Remote sheet has updated row 4, plus a brand new row 5 missing locally
        mock.customBatchGetResponse = """{
            "valueRanges": [
                {
                    "range": "'Nov26'!A4:K",
                    "majorDimension": "ROWS",
                    "values": [
                        [46338, 0.5, "SBI", "New Merchant", "", "Groceries", 250.0, "Personal", 250.0, 0.0, "Settled"],
                        [46338, 0.6, "Cash", "Imported Missing", "", "Shopping", 400.0, "Alice", 200.0, 200.0, "Pending"]
                    ]
                },
                {
                    "range": "'Nov26'!AC4:AC",
                    "majorDimension": "ROWS",
                    "values": [
                        ["txn_to_correct_1"],
                        ["txn_imported_missing_2"]
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
                    "values": []
                },
                {
                    "range": "'Nov26'!AE4:AE",
                    "majorDimension": "ROWS",
                    "values": []
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
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = sheets,
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
            ioDispatcher = Dispatchers.IO
        )

        val res = repo.reconcileWithSheet("test_sheet_id")
        assertTrue(res is Result.Success)

        // Verify corrected fields
        val corrected = tDao.getById("txn_to_correct_1")
        assertNotNull(corrected)
        assertEquals("New Merchant", corrected!!.merchant)
        assertEquals("Groceries", corrected.category)
        assertEquals(250.0, corrected.totalAmount, 0.001)

        // Verify imported missing row
        val imported = tDao.getById("txn_imported_missing_2")
        assertNotNull(imported)
        assertEquals("Imported Missing", imported!!.merchant)
        assertEquals("Shopping", imported.category)
        assertEquals(400.0, imported.totalAmount, 0.001)
        assertEquals(200.0, imported.owedToMe, 0.001)
    }

    @Test
    fun testReconcile_doesNotDuplicateExistingLocalRow() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()

        // Local row lacking tab and row index (e.g. freshly logged offline or pending sync)
        val localTxn = TransactionEntity(
            id = "txn_groceries_uuid_789",
            timestamp = 1794387699000L,
            dateSerial = 46338,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Groceries",
            category = "Food & Drink",
            totalAmount = 500.0,
            companions = "AliceBobk",
            myShare = 120.0,
            owedToMe = 380.0,
            status = "Pending",
            tripTag = "Transit",
            sheetRowIndex = null,
            syncState = SyncState.PENDING.name,
            reviewState = ReviewState.CONFIRMED.name
        )
        tDao.insert(localTxn)

        // Remote sheet has row 4 carrying the exact same UUID in column AC
        mock.customBatchGetResponse = """{
            "valueRanges": [
                {
                    "range": "'Nov26'!A4:K",
                    "majorDimension": "ROWS",
                    "values": [
                        [46338, 0.5, "Cash", "Groceries", "Transit", "Food & Drink", 500.0, "AliceBobk", 120.0, 380.0, "Pending"]
                    ]
                },
                {
                    "range": "'Nov26'!AC4:AC",
                    "majorDimension": "ROWS",
                    "values": [
                        ["txn_groceries_uuid_789"]
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
                    "values": []
                },
                {
                    "range": "'Nov26'!AE4:AE",
                    "majorDimension": "ROWS",
                    "values": []
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
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = sheets,
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
            ioDispatcher = Dispatchers.IO
        )

        val res = repo.reconcileWithSheet("test_sheet_id")
        assertTrue(res is Result.Success)

        // Must assert exactly one local row afterwards
        val allTxns = tDao.transactions.values.toList()
        assertEquals(1, allTxns.size)
        val reconciled = allTxns.first()
        assertEquals("txn_groceries_uuid_789", reconciled.id)
        assertEquals(4, reconciled.sheetRowIndex) // updated from sheet rowNumber
        assertEquals(SyncState.SYNCED.name, reconciled.syncState)
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
            lineageDao = FakeLineageDao(),
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
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                sheetsClient = client,
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
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

    private fun undoTestTxn(id: String, sheetRowIndex: Int?) = TransactionEntity(
        id = id,
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
        syncState = SyncState.PENDING.name,
        sheetRowIndex = sheetRowIndex,
        isPossibleDuplicate = false
    )

    @Test
    fun testUndoRecentSave_pendingInsertIsDroppedWithoutSheetWrite() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
            ioDispatcher = Dispatchers.IO
        )
        tDao.insert(undoTestTxn("txn_undo_1", sheetRowIndex = null))
        qDao.insert(
            SyncQueueEntity(
                transactionId = "txn_undo_1",
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "txn_undo_1"
            )
        )

        val res = repo.undoRecentSave("txn_undo_1")
        assertTrue(res is Result.Success)

        // The insert item is gone and no void operation was queued
        assertEquals(0, qDao.queue.size)
        val local = tDao.getById("txn_undo_1")!!
        assertEquals("Void", local.status)
        assertEquals("[Void] Coffee", local.merchant)
        assertEquals(SyncState.VOID.name, local.syncState)
    }

    @Test
    fun testUndoRecentSave_fallsBackToVoidWhenAlreadyInSheet() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(
                syncQueueDao = qDao,
                transactionDao = tDao
            ),
            ioDispatcher = Dispatchers.IO
        )
        tDao.insert(undoTestTxn("txn_undo_2", sheetRowIndex = 7).copy(syncState = SyncState.SYNCED.name))

        val res = repo.undoRecentSave("txn_undo_2")
        assertTrue(res is Result.Success)

        val ops = qDao.queue.values.toList()
        assertEquals(1, ops.size)
        assertEquals(SyncQueueEntity.OP_VOID, ops.first().operationType)
        assertEquals("Void", tDao.getById("txn_undo_2")!!.status)
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

        // Verify ordering: row 4 contains the carried debit row; the new Dec26 transaction lands after it at row 5 and is not overwritten
        val syncedDec = tDao.getById("txn_dec_1")
        assertNotNull("Synced Dec26 transaction must exist in local database", syncedDec)
        assertEquals(SyncState.SYNCED.name, syncedDec?.syncState)
        assertEquals(5, syncedDec?.sheetRowIndex)

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

    @Test
    fun testApplyPlan_editForOct26Row5_doesNotAffectNov26Row5Transaction() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val lineageDao = FakeLineageDao()
        val mock = CountingSheetsMock()
        val sheets = mock.createClient()

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheets,
            googleDriveClient = GoogleDriveClient(okHttpClient = OkHttpClient(), tokenProvider = mock.fakeTokenProvider),
            syncQueueDao = qDao,
            lineageDao = lineageDao,
            transactionDao = tDao
        )

        // Oct26 transaction at row 5 (timestamp: mid-October 2026, 1792000000000L)
        val octTxn = TransactionEntity(
            id = "txn_oct_row5",
            timestamp = 1792000000000L,
            dateSerial = 46310,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Oct Groceries",
            category = "Food & Drink",
            totalAmount = 500.0,
            companions = "Charlie",
            myShare = 250.0,
            owedToMe = 250.0,
            status = "Pending",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED.name,
            syncState = SyncState.SYNCED.name,
            sheetRowIndex = 5
        )

        // Nov26 transaction at row 5 (timestamp: mid-November 2026, 1794600000000L)
        val novTxn = TransactionEntity(
            id = "txn_nov_row5",
            timestamp = 1794600000000L,
            dateSerial = 46340,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Nov Dinner",
            category = "Food & Drink",
            totalAmount = 300.0,
            companions = "Charlie",
            myShare = 150.0,
            owedToMe = 150.0,
            status = "Pending",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED.name,
            syncState = SyncState.SYNCED.name,
            sheetRowIndex = 5
        )

        tDao.insert(octTxn)
        tDao.insert(novTxn)

        // Create an AccountingPlan that settles/edits Oct26 row 5 (without UUID)
        val plan = AccountingPlan(
            edits = listOf(
                CellEdit(tab = "Oct26", row = 5, column = "J", value = 150.0),
                CellEdit(tab = "Oct26", row = 5, column = "K", value = "Pending")
            ),
            settled = listOf(
                SettledRef(tab = "Oct26", row = 5, uuid = null, applied = 100.0, remaining = 150.0)
            )
        )

        executor.applyPlanToLocalDatabase(plan)

        // Oct26 transaction must be updated
        val updatedOct = tDao.getById(octTxn.id)
        assertNotNull(updatedOct)
        assertEquals(150.0, updatedOct!!.owedToMe, 0.001)

        // Nov26 transaction at row 5 must remain completely untouched
        val untouchedNov = tDao.getById(novTxn.id)
        assertNotNull(untouchedNov)
        assertEquals("Nov Dinner", untouchedNov!!.merchant)
        assertEquals(150.0, untouchedNov.owedToMe, 0.001)
        assertEquals(300.0, untouchedNov.totalAmount, 0.001)
    }

    @Test
    fun testApplyPlan_splitExpenseStoresDebitRowNotPayableRow() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val pDao = FakePayableDao()
        val lineageDao = FakeLineageDao()
        val prefs = FakePreferencesRepository()
        val mock = CountingSheetsMock()

        // Table 1 (A4:A) has 2 occupied rows (rows 4, 5) -> next free row is 6 (debit append row)
        // Table 3 (V4:V) has 4 occupied rows (rows 4, 5, 6, 7) -> next free row is 8 (payable append row)
        mock.customGetHandler = { url ->
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            when {
                decoded.contains("Nov26") && (decoded.contains("!A4:A") || decoded.contains("A4:A")) ->
                    """{"range":"'Nov26'!A4:A","majorDimension":"ROWS","values":[["46338"],["46338"]]}"""
                decoded.contains("Nov26") && (decoded.contains("!V4:V") || decoded.contains("V4:V")) ->
                    """{"range":"'Nov26'!V4:V","majorDimension":"ROWS","values":[["46338"],["46338"],["46338"],["46338"]]}"""
                decoded.contains("Nov26") && (decoded.contains("AC") || decoded.contains("AE")) ->
                    """{"range":"'Nov26'!AC3:AC","majorDimension":"ROWS","values":[]}"""
                else -> null
            }
        }

        val sheets = mock.createClient()
        val drive = GoogleDriveClient(okHttpClient = OkHttpClient(), tokenProvider = mock.fakeTokenProvider)
        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheets,
            googleDriveClient = drive,
            syncQueueDao = qDao,
            lineageDao = lineageDao,
            transactionDao = tDao
        )

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            lineageDao = lineageDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = executor
        )

        val txnId = "txn_split_debit_vs_payable_test"
        val localTxn = TransactionEntity(
            id = txnId,
            timestamp = 1794387699000L,
            dateSerial = 46338,
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Groceries Split",
            category = "Food & Drink",
            totalAmount = 500.0,
            companions = "AliceBobk",
            myShare = 120.0,
            owedToMe = 380.0,
            status = "Pending",
            sheetRowIndex = null,
            syncState = SyncState.PENDING.name,
            reviewState = ReviewState.CONFIRMED.name
        )
        tDao.insert(localTxn)

        // OP_INSERT plan with one DEBIT append (row 6) and one PAYABLE append (row 8)
        val splitPlan = AccountingPlan(
            appends = listOf(
                NewRow(
                    table = TableType.DEBIT,
                    tab = "Nov26",
                    uuid = txnId,
                    cells = listOf(46338, 0.5, "Cash", "Groceries Split", "", "Food & Drink", 500.0, "AliceBobk", 120.0, 380.0, "Pending")
                ),
                NewRow(
                    table = TableType.PAYABLE,
                    tab = "Nov26",
                    uuid = "payable_split_row8",
                    cells = listOf(46338, 0.5, "AliceBobk", "Groceries Split", 380.0, "", "Pending")
                )
            )
        )
        val queueItem = SyncQueueEntity(
            transactionId = txnId,
            operationType = SyncQueueEntity.OP_INSERT,
            payloadJson = txnId,
            planJson = AccountingPlanJsonAdapter.toJson(splitPlan)
        )
        qDao.insert(queueItem)

        // Sync pending transactions
        val syncRes = repo.syncPendingTransactions()
        assertTrue(syncRes is Result.Success)

        // Assert local transaction has sheetRowIndex == 6 (debit row, NOT payable row 8)
        val syncedTxn = tDao.getById(txnId)
        assertNotNull(syncedTxn)
        assertEquals(6, syncedTxn!!.sheetRowIndex)
        assertEquals(SyncState.SYNCED.name, syncedTxn.syncState)

        // Reconcile against sheet with the same UUID at row 6
        mock.customBatchGetResponse = """{
            "valueRanges": [
                {
                    "range": "'Nov26'!A4:K",
                    "majorDimension":"ROWS",
                    "values": [
                        [""], [""],
                        [46338, 0.5, "Cash", "Groceries Split", "", "Food & Drink", 500.0, "AliceBobk", 120.0, 380.0, "Pending"]
                    ]
                },
                {
                    "range": "'Nov26'!AC4:AC",
                    "majorDimension":"ROWS",
                    "values": [
                        [""], [""],
                        ["$txnId"]
                    ]
                },
                {"range":"'Nov26'!M4:T","majorDimension":"ROWS","values":[]},
                {"range":"'Nov26'!AD4:AD","majorDimension":"ROWS","values":[]},
                {"range":"'Nov26'!V4:AB","majorDimension":"ROWS","values":[]},
                {"range":"'Nov26'!AE4:AE","majorDimension":"ROWS","values":[]}
            ]
        }"""

        val reconcileRes = repo.reconcileWithSheet("test_sheet_id")
        assertTrue(reconcileRes is Result.Success)

        // Assert exactly one local row afterwards
        assertEquals(1, tDao.transactions.values.size)
        val reconciledTxn = tDao.getById(txnId)
        assertNotNull(reconciledTxn)
        assertEquals(6, reconciledTxn!!.sheetRowIndex)
        assertEquals("Groceries Split", reconciledTxn.merchant)
    }

    // The old "realign local id by row number" behaviour was removed (audit S-5); see ReconcileSafetyTest.
}
