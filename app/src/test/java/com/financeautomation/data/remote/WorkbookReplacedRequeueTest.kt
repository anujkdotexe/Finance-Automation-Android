package com.financeautomation.data.remote

import com.financeautomation.R
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.testutil.InMemoryInflowDao
import com.financeautomation.testutil.InMemoryPayableDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import kotlinx.coroutines.Dispatchers
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
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime

class WorkbookReplacedRequeueTest {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val tokens = object : TokenProvider {
        override fun getAccessToken(): String = "t"
        override fun refreshAccessToken(): String = "t"
    }
    private val year = 2026
    private val yearIds = mutableMapOf<Int, String>()

    private val prefs: PreferencesRepository = Proxy.newProxyInstance(
        PreferencesRepository::class.java.classLoader, arrayOf(PreferencesRepository::class.java)
    ) { _, m, args ->
        when (m.name) {
            "getSpreadsheetIdForYearSync" -> yearIds[args!![0] as Int]
            "setSpreadsheetIdForYear" -> { yearIds[args!![0] as Int] = args[1] as String; null }
            "getSpreadsheetIdSync" -> yearIds[year]
            "setSpreadsheetId" -> { yearIds[year] = args!![0] as String; null }
            "getGoogleAccountEmailSync" -> "test@example.com"
            "setSpreadsheetTitle" -> null
            "getPreference" -> null
            "setPreference" -> null
            else -> if (kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(m.returnType)) flowOf<Any?>(null) else null
        }
    } as PreferencesRepository

    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody(json)).build()

    private fun sampleTransaction(
        id: String,
        timestamp: Long,
        status: String = "CONFIRMED",
        reviewState: String = "CONFIRMED",
        syncState: String = SyncState.SYNCED.name,
        sheetRowIndex: Int? = 5
    ) = TransactionEntity(
        id = id,
        timestamp = timestamp,
        dateSerial = 46338,
        timeSerial = 0.5,
        account = "SBI",
        merchant = "Test Store",
        totalAmount = 250.0,
        myShare = 250.0,
        owedToMe = 0.0,
        category = "Food & Drink",
        sourcePackage = "com.google.android.apps.messaging",
        status = status,
        syncState = syncState,
        reviewState = reviewState,
        sheetRowIndex = sheetRowIndex
    )

    @Test
    fun replacingAWorkbookRequeuesSyncedRowsForTheNewOne() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()

        // 2026 timestamp: Nov 11 2026
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        // 2025 timestamp: Nov 11 2025
        val nov2025 = ZonedDateTime.of(2025, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        val txn1 = sampleTransaction("txn_1", nov2026, syncState = SyncState.SYNCED.name, sheetRowIndex = 4)
        val txn2 = sampleTransaction("txn_2", nov2026 + 1000, syncState = SyncState.SYNCED.name, sheetRowIndex = 12)
        val txnVoid = sampleTransaction("txn_v", nov2026 + 2000, status = "Void", syncState = SyncState.VOID.name, sheetRowIndex = 15)
        val txnReview = sampleTransaction("txn_r", nov2026 + 3000, reviewState = "NEEDS_REVIEW", syncState = SyncState.PENDING.name, sheetRowIndex = null)
        val txn2025 = sampleTransaction("txn_old", nov2025, syncState = SyncState.SYNCED.name, sheetRowIndex = 8)

        tDao.insert(txn1)
        tDao.insert(txn2)
        tDao.insert(txnVoid)
        tDao.insert(txnReview)
        tDao.insert(txn2025)

        var syncRequested = false
        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            preferencesRepository = prefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
            onTriggerSync = { syncRequested = true },
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.onWorkbookReplaced(2026, "old_id_123", "new_id_456")

        // Assert sync requested
        assertTrue("requestSync must be called on workbook replacement", syncRequested)

        // Assert notice shown
        assertNotNull("workbookReplacedNotice must be set", repo.workbookReplacedNotice.value)
        assertEquals(R.string.workbook_replaced_notice, repo.workbookReplacedNotice.value?.resId)

        // Assert exactly 2 items queued (txn1 and txn2)
        val queued = qDao.queue.values.toList()
        assertEquals("Expected exactly 2 requeued items", 2, queued.size)
        val queuedTxnIds = queued.map { it.transactionId }.toSet()
        assertTrue("txn_1 must be in queue", queuedTxnIds.contains("txn_1"))
        assertTrue("txn_2 must be in queue", queuedTxnIds.contains("txn_2"))
        assertFalse("Void transaction must not be in queue", queuedTxnIds.contains("txn_v"))
        assertFalse("Review transaction must not be in queue", queuedTxnIds.contains("txn_r"))
        assertFalse("Other year transaction must not be in queue", queuedTxnIds.contains("txn_old"))

        // Assert all queued items have OP_INSERT and STATUS_PENDING
        for (item in queued) {
            assertEquals(SyncQueueEntity.OP_INSERT, item.operationType)
            assertEquals(SyncQueueEntity.STATUS_PENDING, item.status)
        }

        // Assert transactions updated: syncState reset to PENDING and sheetRowIndex cleared to null
        val updated1 = tDao.getById("txn_1")!!
        assertEquals(SyncState.PENDING.name, updated1.syncState)
        assertNull("sheetRowIndex must be cleared to null", updated1.sheetRowIndex)

        val updated2 = tDao.getById("txn_2")!!
        assertEquals(SyncState.PENDING.name, updated2.syncState)
        assertNull("sheetRowIndex must be cleared to null", updated2.sheetRowIndex)

        // Second call must NOT duplicate items
        syncRequested = false
        repo.onWorkbookReplaced(2026, "old_id_123", "new_id_456")
        assertEquals("Duplicate call must not add extra items to queue", 2, qDao.queue.size)
    }

    @Test
    fun healthyWorkbookDoesNotRequeueAnything() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()

        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        val txn = sampleTransaction("txn_healthy", nov2026, syncState = SyncState.SYNCED.name, sheetRowIndex = 5)
        tDao.insert(txn)

        var replacedCalls = 0
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (r.url.encodedPath.endsWith("/files/healthy_wb")) {
                    response(r, 200, """{"id":"healthy_wb","trashed":false}""")
                } else response(r, 200, "{}")
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, """{"spreadsheetId":"healthy_wb","sheets":[{"properties":{"sheetId":0,"title":"Dashboard"}}]}""")
            }).build(), tokens
        )

        yearIds[year] = "healthy_wb"
        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            onWorkbookReplaced = { _, _, _ -> replacedCalls++ }
        )

        val res = resolver.resolveForYear(year, forceValidateTabs = false)
        assertTrue(res is Result.Success)
        assertEquals("healthy_wb", (res as Result.Success).data)

        assertEquals("onWorkbookReplaced must not be called for healthy workbook", 0, replacedCalls)
        assertEquals("Queue must remain empty", 0, qDao.queue.size)
        assertEquals("Transaction must remain SYNCED", SyncState.SYNCED.name, tDao.getById("txn_healthy")?.syncState)
        assertEquals(5, tDao.getById("txn_healthy")?.sheetRowIndex)
    }

    @Test
    fun resolverCallsOnWorkbookReplacedWhenNewWorkbookCreated() = runBlocking {
        yearIds[year] = "unusable_old_id"

        var replacedYear: Int? = null
        var replacedOld: String? = null
        var replacedNew: String? = null

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.url.encodedPath.endsWith("/files/unusable_old_id") ->
                        response(r, 200, """{"id":"unusable_old_id","trashed":true}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[]}""")
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_1","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"created_sheet_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" -> response(r, 200, """{"spreadsheetId":"created_sheet_id","sheets":[{"properties":{"sheetId":0,"title":"Sheet1"}}]}""")
                    else -> response(r, 200, """{"replies":[]}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            onWorkbookReplaced = { y, old, newId ->
                replacedYear = y
                replacedOld = old
                replacedNew = newId
            }
        )

        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue(res is Result.Success)
        assertEquals("created_sheet_id", (res as Result.Success).data)

        assertEquals(year, replacedYear)
        assertEquals("unusable_old_id", replacedOld)
        assertEquals("created_sheet_id", replacedNew)
    }

    @Test
    fun restoreInflowWritesTheStoredRowWithoutTheWaterfall() = runBlocking {
        val inDao = InMemoryInflowDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        val inflow = InflowEntity(
            id = "cred_restore_1",
            timestamp = nov2026,
            amount = 200.0,
            account = "HDFC",
            inflowType = "Income / Salary",
            source = "Company",
            note = "Bonus",
            status = "CONFIRMED"
        )
        inDao.insert(inflow)

        val recordedCalls = mutableListOf<String>()
        val appendedBodies = mutableListOf<String>()

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val url = r.url.toString()
                recordedCalls += "${r.method} $url"
                when {
                    url.contains("!AD4:AD") -> response(r, 200, """{"values":[]}""")
                    r.method == "POST" && (url.contains(":append") || url.contains("values:batchUpdate")) -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        appendedBodies += buffer.readUtf8()
                        response(r, 200, """{"responses":[]}""")
                    }
                    r.method == "POST" && url.contains("batchUpdate") -> {
                        response(r, 200, """{"replies":[]}""")
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val executor = createTestAccountingSyncExecutor(
            sheetsClient = sheets,
            inflowDao = inDao
        )

        val queueItem = SyncQueueEntity(
            id = 1L,
            transactionId = "cred_restore_1",
            operationType = SyncQueueEntity.OP_RESTORE_INFLOW,
            payloadJson = "{}"
        )

        val res = executor.executeRestoreInflow(queueItem, "sheet_123", "Nov26")
        assertTrue("Restore inflow must succeed", res is Result.Success)

        // Must NOT read all debits (Table 1 range A4:K or snapshot)
        assertFalse("Must not read debits for waterfall", recordedCalls.any { it.contains("!A4:K") })
        // Must append to Table 2
        assertEquals("Must make exactly one append call", 1, appendedBodies.size)
        val body = appendedBodies.first()
        assertTrue("Must contain amount 200.0", body.contains("200"))
        assertTrue("Must contain account HDFC", body.contains("HDFC"))
        assertTrue("Must contain source Company", body.contains("Company"))
        assertTrue("Must contain note Bonus", body.contains("Bonus"))
    }

    @Test
    fun restoreIsIdempotentWhenTheHiddenIdAlreadyExists() = runBlocking {
        val inDao = InMemoryInflowDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        val inflow = InflowEntity(
            id = "cred_exist_1",
            timestamp = nov2026,
            amount = 150.0,
            account = "SBI",
            inflowType = "Income / Salary",
            source = "Client",
            note = "Project",
            status = "CONFIRMED"
        )
        inDao.insert(inflow)

        val pDao = InMemoryPayableDao()
        val payable = PayableEntity(
            uuid = "pay_exist_1",
            dateIncurredSerial = 46338,
            timeSerial = 0.5,
            paidBy = "Alice",
            whatFor = "Lunch",
            amountIOwe = 250.0,
            dateRepaidSerial = null,
            repaymentStatus = "Pending",
            tabName = "Nov26",
            sheetRowIndex = null
        )
        pDao.upsert(payable)

        var appendCalls = 0
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val url = r.url.toString()
                when {
                    url.contains("!AD4:AD") -> response(r, 200, """{"values":[["other_id"],["cred_exist_1"]]}""")
                    url.contains("!AE4:AE") -> response(r, 200, """{"values":[["pay_exist_1"]]}""")
                    r.method == "POST" && url.contains(":append") -> {
                        appendCalls++
                        response(r, 200, """{"updates":{"updatedRows":1}}""")
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val executor = createTestAccountingSyncExecutor(
            sheetsClient = sheets,
            inflowDao = inDao,
            payableDao = pDao
        )

        val inflowItem = SyncQueueEntity(
            id = 1L,
            transactionId = "cred_exist_1",
            operationType = SyncQueueEntity.OP_RESTORE_INFLOW,
            payloadJson = "{}"
        )
        val inflowRes = executor.executeRestoreInflow(inflowItem, "sheet_123", "Nov26")
        assertTrue(inflowRes is Result.Success)

        val payableItem = SyncQueueEntity(
            id = 2L,
            transactionId = "pay_exist_1",
            operationType = SyncQueueEntity.OP_RESTORE_PAYABLE,
            payloadJson = "{}"
        )
        val payableRes = executor.executeRestorePayable(payableItem, "sheet_123", "Nov26")
        assertTrue(payableRes is Result.Success)

        assertEquals("Zero append calls must be made when IDs already exist", 0, appendCalls)
    }

    @Test
    fun restorePayableAndRepaymentRows() = runBlocking {
        val pDao = InMemoryPayableDao()
        val p1 = PayableEntity(
            uuid = "pay_p1",
            dateIncurredSerial = 46338,
            timeSerial = 0.25,
            paidBy = "Charlie",
            whatFor = "Dinner",
            amountIOwe = 300.0,
            dateRepaidSerial = null,
            repaymentStatus = "Pending",
            tabName = "Nov26",
            sheetRowIndex = null
        )
        val p2 = PayableEntity(
            uuid = "pay_p2",
            dateIncurredSerial = 46330,
            timeSerial = 0.5,
            paidBy = "David",
            whatFor = "Cab",
            amountIOwe = 150.0,
            dateRepaidSerial = 46335,
            repaymentStatus = "Repaid",
            tabName = "Nov26",
            sheetRowIndex = null
        )
        pDao.upsert(p1)
        pDao.upsert(p2)

        val appendedBodies = mutableListOf<String>()
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val url = r.url.toString()
                when {
                    url.contains("!AE4:AE") -> response(r, 200, """{"values":[]}""")
                    r.method == "POST" && (url.contains(":append") || url.contains("values:batchUpdate")) -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        appendedBodies += buffer.readUtf8()
                        response(r, 200, """{"responses":[]}""")
                    }
                    r.method == "POST" && url.contains("batchUpdate") -> {
                        response(r, 200, """{"replies":[]}""")
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val executor = createTestAccountingSyncExecutor(
            sheetsClient = sheets,
            payableDao = pDao
        )

        val res1 = executor.executeRestorePayable(
            SyncQueueEntity(id = 1L, transactionId = "pay_p1", operationType = SyncQueueEntity.OP_RESTORE_PAYABLE, payloadJson = "{}"),
            "sheet_123",
            "Nov26"
        )
        val res2 = executor.executeRestorePayable(
            SyncQueueEntity(id = 2L, transactionId = "pay_p2", operationType = SyncQueueEntity.OP_RESTORE_PAYABLE, payloadJson = "{}"),
            "sheet_123",
            "Nov26"
        )
        val res3 = executor.executeRestoreRepayment(
            SyncQueueEntity(id = 3L, transactionId = "pay_p2", operationType = SyncQueueEntity.OP_RESTORE_REPAYMENT, payloadJson = "{}"),
            "sheet_123",
            "Nov26"
        )

        assertTrue(res1 is Result.Success)
        assertTrue(res2 is Result.Success)
        assertTrue(res3 is Result.Success)

        assertEquals("Must append 2 payable rows", 2, appendedBodies.size)
        val b1 = appendedBodies[0]
        val b2 = appendedBodies[1]
        assertTrue("Pending payable must contain Charlie and 300.0", b1.contains("Charlie") && b1.contains("300"))
        assertTrue("Repaid payable must contain David, 150.0, and Repaid", b2.contains("David") && b2.contains("150") && b2.contains("Repaid"))
    }

    @Test
    fun replacementRestoresTransactionsInflowsAndPayables() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val inDao = InMemoryInflowDao()
        val pDao = InMemoryPayableDao()
        val qDao = InMemoryQueueDao()

        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        val txn1 = sampleTransaction("txn_e2e_1", nov2026, syncState = SyncState.SYNCED.name, sheetRowIndex = 4)
        val txn2 = sampleTransaction("txn_e2e_2", nov2026 + 1000, syncState = SyncState.SYNCED.name, sheetRowIndex = 5)
        tDao.insert(txn1)
        tDao.insert(txn2)

        val inflow = InflowEntity(
            id = "cred_e2e_1",
            timestamp = nov2026,
            amount = 500.0,
            account = "HDFC",
            inflowType = "Income / Salary",
            source = "Bonus",
            note = "Festival bonus",
            status = "CONFIRMED"
        )
        inDao.insert(inflow)

        val payable = PayableEntity(
            uuid = "pay_e2e_1",
            dateIncurredSerial = 46338,
            timeSerial = 0.5,
            paidBy = "Frank",
            whatFor = "Dinner",
            amountIOwe = 400.0,
            dateRepaidSerial = null,
            repaymentStatus = "Pending",
            tabName = "Nov26",
            sheetRowIndex = null
        )
        pDao.upsert(payable)

        val appendsByRange = mutableListOf<String>()
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val url = r.url.toString()
                when {
                    url.contains("!AC4:AC") || url.contains("!AD4:AD") || url.contains("!AE4:AE") ->
                        response(r, 200, """{"values":[]}""")
                    r.method == "POST" && (url.contains(":append") || url.contains("values:batchUpdate")) -> {
                        appendsByRange += url
                        response(r, 200, """{"responses":[]}""")
                    }
                    r.method == "GET" && url.contains("/values/") -> response(r, 200, """{"values":[]}""")
                    r.method == "GET" -> response(r, 200, """{"spreadsheetId":"new_wb","sheets":[{"properties":{"sheetId":0,"title":"Nov26"}}]}""")
                    r.method == "POST" && url.contains("batchUpdate") -> response(r, 200, """{"replies":[]}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        yearIds[year] = "new_wb"

        val executor = createTestAccountingSyncExecutor(
            sheetsClient = sheets,
            syncQueueDao = qDao,
            transactionDao = tDao,
            inflowDao = inDao,
            payableDao = pDao
        )

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            inflowDao = inDao,
            payableDao = pDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = executor,
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.onWorkbookReplaced(2026, "old_wb", "new_wb")

        // Assert 4 items queued: 2 transactions, 1 inflow, 1 payable
        val queued = qDao.queue.values.toList()
        assertEquals("Expected exactly 4 items queued for replacement", 4, queued.size)
        assertEquals(2, queued.count { it.operationType == SyncQueueEntity.OP_INSERT })
        assertEquals(1, queued.count { it.operationType == SyncQueueEntity.OP_RESTORE_INFLOW })
        assertEquals(1, queued.count { it.operationType == SyncQueueEntity.OP_RESTORE_PAYABLE })

        // Execute sync
        val syncRes = repo.syncPendingTransactions()
        assertTrue("Sync should succeed", syncRes is Result.Success)
        assertEquals(4, (syncRes as Result.Success).data)

        // Assert appends made: Table 1 (2 debits), Table 2 (1 inflow), Table 3 (1 payable)
        assertEquals(4, appendsByRange.size)
        assertEquals("Queue should be empty after sync", 0, qDao.queue.size)
    }
}
