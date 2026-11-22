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
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import com.financeautomation.core.utils.DateSerialUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import com.financeautomation.domain.model.AccountingPlan
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

    private val rawPrefs = mutableMapOf<String, String?>()

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
            "getPreference" -> rawPrefs[args!![0] as String]
            "setPreference" -> { rawPrefs[args!![0] as String] = args[1] as? String; null }
            else -> if (kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(m.returnType)) flowOf<Any?>(null) else null
        }
    } as PreferencesRepository

    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody(json)).build()

    /**
     * Answers the two reads that make a month tab count as a current 32 column tab: the tab list (Nov26 exists) and the
     * L3 header ("Note"). Since a failed tab repair is returned instead of ignored, a scripted server that leaves L3
     * empty would model an old tab whose repair never takes effect.
     */
    private fun tabReadyResponse(request: okhttp3.Request): Response? {
        if (request.method != "GET") return null
        val url = request.url.toString()
        return when {
            url.contains("L3%3AL3") || url.contains("L3:L3") -> response(request, 200, """{"values":[["Note"]]}""")
            url.contains("fields=sheets(properties(sheetId") && !url.contains("gridProperties") ->
                response(request, 200, """{"sheets":[{"properties":{"sheetId":0,"title":"Nov26"}}]}""")
            else -> null
        }
    }

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

        repo.requeueYearForNewWorkbook(2026)

        // Assert sync requested
        assertTrue("requestSync must be called on workbook replacement", syncRequested)

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
        repo.requeueYearForNewWorkbook(2026)
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
    fun callbackFiresWhenTheSearchFindsAnotherWorkbook() = runBlocking {
        yearIds[year] = "trashed_old_id"
        rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(year)] = "trashed_old_id"
        rawPrefs.remove(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)

        var replacedCalls = 0
        var replacedYear: Int? = null
        var replacedOld: String? = null
        var replacedNew: String? = null

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.url.encodedPath.endsWith("/files/trashed_old_id") ->
                        response(r, 200, """{"id":"trashed_old_id","trashed":true}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[{"id":"found_other_id","name":"Finance$year","trashed":false}]}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"found_other_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":0}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            onWorkbookReplaced = { y, old, newId ->
                replacedCalls++
                replacedYear = y
                replacedOld = old
                replacedNew = newId
            }
        )

        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue(res is Result.Success)
        assertEquals("found_other_id", (res as Result.Success).data)

        assertEquals("Callback must fire exactly once", 1, replacedCalls)
        assertEquals(year, replacedYear)
        assertEquals("trashed_old_id", replacedOld)
        assertEquals("found_other_id", replacedNew)
        assertEquals("Notice flag must be set", "true", rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])
    }

    @Test
    fun callbackFiresWhenANewWorkbookIsCreated() = runBlocking {
        yearIds[year] = "unusable_old_id"
        rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(year)] = "unusable_old_id"
        rawPrefs.remove(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)

        var replacedCalls = 0
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
                replacedCalls++
                replacedYear = y
                replacedOld = old
                replacedNew = newId
            }
        )

        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue(res is Result.Success)
        assertEquals("created_sheet_id", (res as Result.Success).data)

        assertEquals("Callback must fire exactly once", 1, replacedCalls)
        assertEquals(year, replacedYear)
        assertEquals("unusable_old_id", replacedOld)
        assertEquals("created_sheet_id", replacedNew)
        assertEquals("Notice flag must be set", "true", rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])
    }

    @Test
    fun callbackDoesNotFireOnFirstProvisioningWhenNoIdWasStored() = runBlocking {
        yearIds.remove(year)
        rawPrefs.remove(PreferencesRepository.keySpreadsheetIdForYear(year))
        rawPrefs.remove(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)

        var replacedCalls = 0

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[]}""")
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_1","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"first_provisioned_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, """{"replies":[]}""")
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            onWorkbookReplaced = { _, _, _ -> replacedCalls++ }
        )

        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue(res is Result.Success)
        assertEquals("first_provisioned_id", (res as Result.Success).data)

        assertEquals("onWorkbookReplaced must not be called on first provisioning when no ID was stored", 0, replacedCalls)
        assertNull("Missing notice must not be set on first provisioning", rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])
    }

    @Test
    fun callbackDoesNotFireWhenTheSameIdComesBack() = runBlocking {
        yearIds[year] = "same_wb_id"
        rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(year)] = "same_wb_id"
        rawPrefs.remove(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)

        var replacedCalls = 0

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.url.encodedPath.endsWith("/files/same_wb_id") ->
                        response(r, 200, """{"id":"same_wb_id","trashed":false}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[{"id":"same_wb_id","name":"Finance$year","trashed":false}]}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val url = r.url.encodedPath
                when {
                    r.method == "GET" && url.contains("/values/") ->
                        response(r, 200, """{"values":[["Date","Time","Account","Merchant","Category","Total","My Share","Owed to Me","Companions","Status"]]}""")
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"same_wb_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard"}},{"properties":{"sheetId":1,"title":"Jan26"}}]}""")
                    else -> response(r, 200, """{"replies":[]}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            onWorkbookReplaced = { _, _, _ -> replacedCalls++ }
        )

        val res = resolver.resolveForYear(year, forceValidateTabs = false)
        assertTrue(res is Result.Success)
        assertEquals("same_wb_id", (res as Result.Success).data)

        assertEquals("onWorkbookReplaced must not be called when the same ID comes back", 0, replacedCalls)
        assertNull("Notice flag must not be set when same ID comes back", rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])
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
                tabReadyResponse(r)?.let { return@Interceptor it }
                recordedCalls += "${r.method} $url"
                when {
                    url.contains("!AD4:AD") || url.contains("!AE4:AE") -> response(r, 200, """{"values":[]}""")
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

        // Must NOT read all debits (Table 1 range A4:K, A4:L or snapshot)
        assertFalse("Must not read debits for waterfall", recordedCalls.any { it.contains("!A4:K") || it.contains("!A4:L") })
        // Must append to Table 2 with strict column assertions
        assertEquals("Must make exactly one append call", 1, appendedBodies.size)
        val body = appendedBodies.first()
        val parsedJson = Json.parseToJsonElement(body).jsonObject
        val dataArray = parsedJson["data"]?.jsonArray.orEmpty()
        val table2Entry = dataArray.firstOrNull {
            val r = it.jsonObject["range"]?.jsonPrimitive?.content.orEmpty()
            r.contains("!N") || r.contains("!M")
        }?.jsonObject
        assertNotNull("Must contain table 2 range starting at column N or M", table2Entry)
        val t2Values = table2Entry!!["values"]?.jsonArray?.get(0)?.jsonArray
        assertNotNull(t2Values)
        assertEquals(8, t2Values!!.size)
        assertEquals("HDFC", t2Values[2].jsonPrimitive.content)
        assertEquals("Income / Salary", t2Values[3].jsonPrimitive.content)
        assertEquals("Company", t2Values[4].jsonPrimitive.content)
        assertEquals("Bonus", t2Values[5].jsonPrimitive.content)
        assertEquals(200.0, t2Values[6].jsonPrimitive.double, 0.001)

        val uuidEntry = dataArray.firstOrNull {
            val r = it.jsonObject["range"]?.jsonPrimitive?.content.orEmpty()
            r.contains("!AE") || r.contains("!AD")
        }?.jsonObject
        assertNotNull("Must write hidden UUID to column AE or AD", uuidEntry)
        val uuidValue = uuidEntry!!["values"]?.jsonArray?.get(0)?.jsonArray?.get(0)?.jsonPrimitive?.content
        assertEquals("cred_restore_1", uuidValue)
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
                tabReadyResponse(r)?.let { return@Interceptor it }
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
    fun restorePayableRowsPendingAndRepaid() = runBlocking {
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
                tabReadyResponse(r)?.let { return@Interceptor it }
                when {
                    url.contains("!AE4:AE") || url.contains("!AF4:AF") -> response(r, 200, """{"values":[]}""")
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

        assertTrue(res1 is Result.Success)
        assertTrue(res2 is Result.Success)

        assertEquals("Must append 2 payable rows", 2, appendedBodies.size)
        val b1 = appendedBodies[0]
        val json1 = Json.parseToJsonElement(b1).jsonObject
        val data1 = json1["data"]?.jsonArray.orEmpty()
        val t3Entry1 = data1.firstOrNull {
            val r = it.jsonObject["range"]?.jsonPrimitive?.content.orEmpty()
            r.contains("!W") || r.contains("!V")
        }?.jsonObject
        assertNotNull("Must contain table 3 range starting at column W or V", t3Entry1)
        val v1Values = t3Entry1!!["values"]?.jsonArray?.get(0)?.jsonArray
        assertNotNull(v1Values)
        assertEquals(7, v1Values!!.size)
        assertEquals("Charlie", v1Values[2].jsonPrimitive.content)
        assertEquals("Dinner", v1Values[3].jsonPrimitive.content)
        assertEquals(300.0, v1Values[4].jsonPrimitive.double, 0.001)
        assertEquals("Pending", v1Values[6].jsonPrimitive.content)

        val uuid1Entry = data1.firstOrNull {
            val r = it.jsonObject["range"]?.jsonPrimitive?.content.orEmpty()
            r.contains("!AF") || r.contains("!AE")
        }?.jsonObject
        assertNotNull("Must write hidden payable UUID to column AF or AE", uuid1Entry)
        assertEquals("pay_p1", uuid1Entry!!["values"]?.jsonArray?.get(0)?.jsonArray?.get(0)?.jsonPrimitive?.content)

        val b2 = appendedBodies[1]
        val json2 = Json.parseToJsonElement(b2).jsonObject
        val data2 = json2["data"]?.jsonArray.orEmpty()
        val t3Entry2 = data2.firstOrNull {
            val r = it.jsonObject["range"]?.jsonPrimitive?.content.orEmpty()
            r.contains("!W") || r.contains("!V")
        }?.jsonObject
        assertNotNull(t3Entry2)
        val v2Values = t3Entry2!!["values"]?.jsonArray?.get(0)?.jsonArray
        assertNotNull(v2Values)
        assertEquals(7, v2Values!!.size)
        assertEquals("David", v2Values[2].jsonPrimitive.content)
        assertEquals("Cab", v2Values[3].jsonPrimitive.content)
        assertEquals(150.0, v2Values[4].jsonPrimitive.double, 0.001)
        assertEquals(46335, v2Values[5].jsonPrimitive.int)
        assertEquals("Repaid", v2Values[6].jsonPrimitive.content)

        val uuid2Entry = data2.firstOrNull {
            val r = it.jsonObject["range"]?.jsonPrimitive?.content.orEmpty()
            r.contains("!AF") || r.contains("!AE")
        }?.jsonObject
        assertNotNull("Must write hidden payable UUID to column AF or AE", uuid2Entry)
        assertEquals("pay_p2", uuid2Entry!!["values"]?.jsonArray?.get(0)?.jsonArray?.get(0)?.jsonPrimitive?.content)
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
                tabReadyResponse(r)?.let { return@Interceptor it }
                when {
                    url.contains("!AC4:AC") || url.contains("!AD4:AD") || url.contains("!AE4:AE") || url.contains("!AF4:AF") ->
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

        repo.requeueYearForNewWorkbook(2026)

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

    @Test
    fun restorePayableWithNoDateIsStillRestored() = runBlocking {
        val pDao = InMemoryPayableDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        val pNullDate = PayableEntity(
            uuid = "pay_no_date",
            dateIncurredSerial = null,
            timeSerial = null,
            paidBy = "Grace",
            whatFor = "Snacks",
            amountIOwe = 75.0,
            dateRepaidSerial = null,
            repaymentStatus = "Pending",
            tabName = "Nov26",
            sheetRowIndex = null,
            updatedAt = nov2026
        )
        pDao.upsert(pNullDate)

        val startSerial = DateSerialUtils.toDateSerial(java.time.LocalDate.of(2026, 1, 1))
        val endSerial = DateSerialUtils.toDateSerial(java.time.LocalDate.of(2027, 1, 1))
        val startMillis = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        val endMillis = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        val found = pDao.getAllForYear(startSerial, endSerial, startMillis, endMillis)
        assertEquals(1, found.size)
        assertEquals("pay_no_date", found[0].uuid)

        val appendedBodies = mutableListOf<String>()
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val url = r.url.toString()
                tabReadyResponse(r)?.let { return@Interceptor it }
                when {
                    url.contains("!AE4:AE") || url.contains("!AF4:AF") -> response(r, 200, """{"values":[]}""")
                    r.method == "POST" && (url.contains(":append") || url.contains("values:batchUpdate")) -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        appendedBodies += buffer.readUtf8()
                        response(r, 200, """{"responses":[]}""")
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val executor = createTestAccountingSyncExecutor(sheetsClient = sheets, payableDao = pDao)
        val res = executor.executeRestorePayable(
            SyncQueueEntity(id = 1L, transactionId = "pay_no_date", operationType = SyncQueueEntity.OP_RESTORE_PAYABLE, payloadJson = "{}"),
            "sheet_123",
            "Nov26"
        )
        assertTrue(res is Result.Success)
        assertEquals(1, appendedBodies.size)
        val json = Json.parseToJsonElement(appendedBodies[0]).jsonObject
        val dataArray = json["data"]?.jsonArray.orEmpty()
        val t3Entry = dataArray.firstOrNull {
            val r = it.jsonObject["range"]?.jsonPrimitive?.content.orEmpty()
            r.contains("!W") || r.contains("!V")
        }?.jsonObject
        assertNotNull(t3Entry)
        val values = t3Entry!!["values"]?.jsonArray?.get(0)?.jsonArray
        assertNotNull(values)
        assertEquals("", values!![0].jsonPrimitive.content) // Null date incurred serial rendered as empty string
        assertEquals("Grace", values[2].jsonPrimitive.content)
        assertEquals(75.0, values[4].jsonPrimitive.double, 0.001)

        val uuidEntry = dataArray.firstOrNull {
            val r = it.jsonObject["range"]?.jsonPrimitive?.content.orEmpty()
            r.contains("!AF") || r.contains("!AE")
        }?.jsonObject
        assertNotNull(uuidEntry)
        assertEquals("pay_no_date", uuidEntry!!["values"]?.jsonArray?.get(0)?.jsonArray?.get(0)?.jsonPrimitive?.content)
    }

    @Test
    fun pendingUpdateIsFoldedIntoTheNewInsert() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        val txn = sampleTransaction("tx_upd", nov2026, status = "Personal", syncState = SyncState.PENDING.name, sheetRowIndex = 7)
        tDao.insert(txn)
        val pendingUpdate = SyncQueueEntity(
            id = 1L,
            transactionId = "tx_upd",
            operationType = SyncQueueEntity.OP_UPDATE,
            payloadJson = "{\"category\":\"Entertainment\"}",
            status = SyncQueueEntity.STATUS_PENDING
        )
        qDao.insert(pendingUpdate)

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            preferencesRepository = prefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.requeueYearForNewWorkbook(2026)

        val queueItems = qDao.queue.values.filter { it.transactionId == "tx_upd" }
        assertEquals("Pending UPDATE must be deleted and replaced by exactly one OP_INSERT", 1, queueItems.size)
        val queuedItem = queueItems.first()
        assertEquals(SyncQueueEntity.OP_INSERT, queuedItem.operationType)
        assertEquals(SyncQueueEntity.STATUS_PENDING, queuedItem.status)
        assertEquals("tx_upd", queuedItem.payloadJson)

        val updatedTxn = tDao.getById("tx_upd")
        assertNotNull(updatedTxn)
        assertEquals(SyncState.PENDING.name, updatedTxn?.syncState)
        assertNull(updatedTxn?.sheetRowIndex)
    }

    @Test
    fun pendingVoidOfAVoidedRowIsNotInserted() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        val txnVoid = sampleTransaction("tx_voided", nov2026, status = "Void", syncState = SyncState.VOID.name, sheetRowIndex = 14)
        tDao.insert(txnVoid)
        val pendingVoid = SyncQueueEntity(
            id = 2L,
            transactionId = "tx_voided",
            operationType = SyncQueueEntity.OP_VOID,
            payloadJson = "{\"reason\":\"Mistake\"}",
            status = SyncQueueEntity.STATUS_PENDING
        )
        qDao.insert(pendingVoid)

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            preferencesRepository = prefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.requeueYearForNewWorkbook(2026)

        val queueItems = qDao.queue.values.filter { it.transactionId == "tx_voided" }
        assertTrue("Voided transaction must have its pending void cleared and must not have OP_INSERT queued", queueItems.isEmpty())
    }

    @Test
    fun requeueWaitsForARunningSync() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        val txn = sampleTransaction("tx_active", nov2026, status = "Personal", syncState = SyncState.PENDING.name, sheetRowIndex = null)
        tDao.insert(txn)

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            preferencesRepository = prefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
            ioDispatcher = Dispatchers.Default
        )

        var requeueFinished = false
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()

        val holderJob = launch(Dispatchers.Default) {
            repo.syncMutex.withLock {
                lockHeld.complete(Unit)
                releaseLock.await()
            }
        }

        lockHeld.await()

        val requeueJob = launch(Dispatchers.Default) {
            repo.requeueYearForNewWorkbook(2026)
            requeueFinished = true
        }

        delay(100)
        assertFalse("Requeue must wait while syncMutex is held", requeueFinished)
        assertTrue("Requeue coroutine must be suspended waiting for syncMutex", requeueJob.isActive)

        releaseLock.complete(Unit)
        holderJob.join()
        requeueJob.join()

        assertTrue("Requeue must complete once syncMutex is released", requeueFinished)
    }

    @Test
    fun deletePendingForTransactionsWith1500TransactionsIsChunked() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        val count = 1500
        for (i in 1..count) {
            val id = "tx_$i"
            tDao.insert(sampleTransaction(id, nov2026, status = "Personal", syncState = SyncState.PENDING.name))
            qDao.insert(
                SyncQueueEntity(
                    id = i.toLong(),
                    transactionId = id,
                    operationType = SyncQueueEntity.OP_INSERT,
                    payloadJson = id,
                    status = SyncQueueEntity.STATUS_PENDING
                )
            )
        }

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            preferencesRepository = prefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.requeueYearForNewWorkbook(2026)

        val pending = qDao.getAllPending()
        assertEquals("All 1500 transactions should be re-queued in chunks of 500", count, pending.size)
        val queuedTxIds = pending.map { it.transactionId }.toSet()
        assertEquals(count, queuedTxIds.size)
    }

    @Test
    fun pendingCreditLoggedBeforeReplacementRunsAfterTheRestore() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val inDao = InMemoryInflowDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        val txn = sampleTransaction("tx_earlier", nov2026, status = "Personal", syncState = SyncState.SYNCED.name, sheetRowIndex = 4)
        tDao.insert(txn)

        val creditReq = com.financeautomation.domain.model.LogCreditRequest(
            requestId = "cred_pending",
            settler = "Alice",
            amount = 100.0,
            account = "SBI",
            inflowType = "Settlement",
            reason = "Debt settlement",
            timestamp = nov2026
        )
        val pendingCredit = SyncQueueEntity(
            id = 100L,
            transactionId = "cred_pending",
            operationType = SyncQueueEntity.OP_LOG_CREDIT,
            payloadJson = com.financeautomation.data.remote.AccountingRequestJsonAdapter.creditRequestToJson(creditReq),
            status = SyncQueueEntity.STATUS_PENDING,
            createdAt = 5_000_000L
        )
        qDao.insert(pendingCredit)
        inDao.insert(
            InflowEntity(
                id = "cred_pending",
                timestamp = nov2026,
                amount = 100.0,
                account = "SBI",
                inflowType = "Settlement",
                source = "Alice",
                note = "Debt settlement",
                createdAt = 5_000_000L,
                status = "CONFIRMED"
            )
        )

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            inflowDao = inDao,
            preferencesRepository = prefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.requeueYearForNewWorkbook(2026)

        val queueFifo = qDao.getPendingQueueFifo()
        assertTrue("Queue must contain at least restore items and the pending credit", queueFifo.size >= 2)
        assertEquals("First item in FIFO must be the restored transaction", "tx_earlier", queueFifo[0].transactionId)
        assertEquals(SyncQueueEntity.OP_INSERT, queueFifo[0].operationType)

        val lastItem = queueFifo.last()
        assertEquals("Pending credit must execute AFTER the restored items", "cred_pending", lastItem.transactionId)
        assertEquals(SyncQueueEntity.OP_LOG_CREDIT, lastItem.operationType)

        val restoreInflows = queueFifo.filter { it.operationType == SyncQueueEntity.OP_RESTORE_INFLOW }
        assertTrue("Pending credit must not be duplicated into an OP_RESTORE_INFLOW", restoreInflows.isEmpty())
    }

    @Test
    fun pendingPayableLoggedBeforeReplacementRunsAfterTheRestoreAndNets() = runBlocking {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val pDao = InMemoryPayableDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        // 1. Transaction where Bob owes 500
        val txn = sampleTransaction("tx_earlier", nov2026, status = "Pending", syncState = SyncState.SYNCED.name, sheetRowIndex = 4)
            .copy(owedToMe = 500.0, companions = "Bob")
        tDao.insert(txn)

        // 2. Queue pending OP_LOG_PAYABLE for Bob (Bob paid 200)
        val payableReq = com.financeautomation.domain.model.LogPayableRequest(
            requestId = "pay_bob_1",
            creditor = "Bob",
            amount = 200.0,
            whatFor = "Dinner",
            timestamp = nov2026
        )
        val queueItem = SyncQueueEntity(
            id = 200L,
            transactionId = "pay_bob_1",
            operationType = SyncQueueEntity.OP_LOG_PAYABLE,
            payloadJson = com.financeautomation.data.remote.AccountingRequestJsonAdapter.payableRequestToJson(payableReq),
            status = SyncQueueEntity.STATUS_PENDING,
            createdAt = 5_000_000L
        )
        qDao.insert(queueItem)

        val server = ScriptedSheetsServer(listOf("Nov26"))
        // Script batchGet to return Bob's restored debit row in Nov26 Table 1
        server.customRead = { call ->
            if (call.url.contains("values:batchGet")) {
                """{"valueRanges":[
                    {"range":"'Nov26'!A4:L","majorDimension":"ROWS","values":[
                        [46337, 0.5, "Cash", "Dinner", "", "Food", 1000.0, "Bob", 500.0, 500.0, "Pending", ""]
                    ]},
                    {"range":"'Nov26'!AD4:AD","majorDimension":"ROWS","values":[["tx_earlier"]]},
                    {"range":"'Nov26'!N4:U","majorDimension":"ROWS","values":[]},
                    {"range":"'Nov26'!AE4:AE","majorDimension":"ROWS","values":[]},
                    {"range":"'Nov26'!W4:AC","majorDimension":"ROWS","values":[]},
                    {"range":"'Nov26'!AF4:AF","majorDimension":"ROWS","values":[]}
                ]}"""
            } else null
        }

        val executor = createTestAccountingSyncExecutor(
            sheetsClient = server.client(),
            syncQueueDao = qDao,
            transactionDao = tDao,
            payableDao = pDao
        )

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            googleSheetsClient = server.client(),
            preferencesRepository = prefs,
            accountingSyncExecutor = executor,
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.requeueYearForNewWorkbook(2026)

        // Assert (1): restore items ran first in FIFO queue order
        val queueFifo = qDao.getPendingQueueFifo()
        assertEquals(2, queueFifo.size)
        assertEquals("tx_earlier", queueFifo[0].transactionId)
        assertEquals(SyncQueueEntity.OP_INSERT, queueFifo[0].operationType)
        assertEquals("pay_bob_1", queueFifo[1].transactionId)
        assertEquals(SyncQueueEntity.OP_LOG_PAYABLE, queueFifo[1].operationType)

        // Execute the pending OP_LOG_PAYABLE against the restored sheet
        val syncResult = executor.executeLogPayable(queueFifo[1], "test_sheet_id", "Nov26")
        assertTrue("executeLogPayable should succeed", syncResult is Result.Success)

        // Assert (2): netting reduced friend's receivable from 500 to 300
        val nettingCall = server.calls.find { it.method == "POST" && it.url.contains("values:batchUpdate") && it.body.contains("'Nov26'!J4") }
        assertNotNull("Netting edit must be posted to sheet", nettingCall)

        val parsedJson = Json.parseToJsonElement(nettingCall!!.body).jsonObject
        val dataArray = parsedJson["data"]?.jsonArray
        assertNotNull("Batch update must contain data array", dataArray)

        val j4Entry = dataArray!!.firstOrNull {
            it.jsonObject["range"]?.jsonPrimitive?.content == "'Nov26'!J4"
        }?.jsonObject
        assertNotNull("Netting edit must target cell 'Nov26'!J4", j4Entry)

        val j4Values = j4Entry!!["values"]?.jsonArray?.get(0)?.jsonArray
        assertNotNull("Cell J4 must have values", j4Values)
        assertEquals(1, j4Values!!.size)
        assertEquals("Bob's remaining receivable in cell J4 must be exactly 300.0", 300.0, j4Values[0].jsonPrimitive.double, 0.001)

        val k4Entry = dataArray.firstOrNull {
            it.jsonObject["range"]?.jsonPrimitive?.content == "'Nov26'!K4"
        }?.jsonObject
        assertNotNull("Status edit must target cell 'Nov26'!K4", k4Entry)
        val k4Values = k4Entry!!["values"]?.jsonArray?.get(0)?.jsonArray
        assertNotNull("Cell K4 must have values", k4Values)
        assertEquals("Pending", k4Values!![0].jsonPrimitive.content)

        // Assert (3): payable appears exactly once in the queue
        val payablesInQueue = qDao.getAllPending().filter { it.transactionId == "pay_bob_1" }
        assertEquals("Payable must appear exactly once in queue", 1, payablesInQueue.size)
    }

    @Test
    fun freshWorkbookStartEmptyDoesNotRequeue() = runBlocking {
        yearIds[year] = "old_wb_id"
        var requeueCalls = 0

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_1","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"fresh_sheet_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, """{"replies":[]}""")
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            onWorkbookReplaced = { _, _, _ -> requeueCalls++ }
        )

        val res = resolver.createFreshWorkbook(year, copyHistory = false)
        assertTrue(res is Result.Success)
        assertEquals("fresh_sheet_id", (res as Result.Success).data)
        assertEquals("Start empty must not call onWorkbookReplaced", 0, requeueCalls)
        assertNull(
            "Notice pending flag must not be set for fresh workbook",
            rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING]
        )
    }

    @Test
    fun freshWorkbookCopyHistoryRequeuesEverything() = runBlocking {
        yearIds[year] = "old_wb_id"
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val inDao = InMemoryInflowDao()
        val pDao = InMemoryPayableDao()
        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        tDao.insert(sampleTransaction("tx_fresh_copy", nov2026, status = "Pending", syncState = SyncState.SYNCED.name, sheetRowIndex = 4))
        inDao.insert(
            InflowEntity(
                id = "in_fresh_copy",
                timestamp = nov2026,
                amount = 500.0,
                account = "HDFC",
                inflowType = "Income / Salary",
                source = "Company",
                note = "Salary",
                status = "CONFIRMED"
            )
        )
        pDao.upsert(
            PayableEntity(
                uuid = "pay_fresh_copy",
                dateIncurredSerial = 46338,
                timeSerial = 0.5,
                paidBy = "Bob",
                whatFor = "Dinner",
                amountIOwe = 300.0,
                dateRepaidSerial = null,
                repaymentStatus = "Pending",
                tabName = "Nov26",
                sheetRowIndex = null
            )
        )

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_1","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"fresh_sheet_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, """{"replies":[]}""")
            }).build(), tokens
        )

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

        var requeueCalled = false
        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            onWorkbookReplaced = { y, _, _ ->
                requeueCalled = true
                repo.requeueYearForNewWorkbook(y)
            }
        )

        val res = resolver.createFreshWorkbook(year, copyHistory = true)
        assertTrue(res is Result.Success)
        assertTrue("Copy history must trigger onWorkbookReplaced", requeueCalled)

        val pending = qDao.getAllPending()
        assertTrue("Must requeue transaction", pending.any { it.transactionId == "tx_fresh_copy" && it.operationType == SyncQueueEntity.OP_INSERT })
        assertTrue("Must requeue inflow", pending.any { it.transactionId == "in_fresh_copy" && it.operationType == SyncQueueEntity.OP_RESTORE_INFLOW })
        assertTrue("Must requeue payable", pending.any { it.transactionId == "pay_fresh_copy" && it.operationType == SyncQueueEntity.OP_RESTORE_PAYABLE })

        assertNull(
            "Notice pending flag must not be set for fresh workbook even when copying history",
            rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING]
        )
    }

    @Test
    fun freshWorkbookNeverShowsTheMissingNotice() = runBlocking {
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_1","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"fresh_sheet_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, """{"replies":[]}""")
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs
        )

        // 1. copyHistory = false
        rawPrefs.remove(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)
        resolver.createFreshWorkbook(year, copyHistory = false)
        assertNull("Missing notice must not be set for copyHistory = false",
            rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])

        // 2. copyHistory = true
        rawPrefs.remove(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)
        resolver.createFreshWorkbook(year, copyHistory = true)
        assertNull("Missing notice must not be set for copyHistory = true",
            rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])
    }

    @Test
    fun startEmptyWithPendingItemsPreservesThemAndSyncsToNewWorkbook() = runBlocking {
        yearIds[year] = "old_wb_id"
        rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(year)] = "old_wb_id"

        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val inDao = InMemoryInflowDao()
        val pDao = InMemoryPayableDao()

        val nov2026 = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

        // 2 pending transactions
        val txn1 = sampleTransaction("tx_p1", nov2026, syncState = SyncState.PENDING.name, sheetRowIndex = null)
        val txn2 = sampleTransaction("tx_p2", nov2026 + 1000, syncState = SyncState.PENDING.name, sheetRowIndex = null)
        tDao.insert(txn1)
        tDao.insert(txn2)

        qDao.insert(
            SyncQueueEntity(
                id = 1L,
                transactionId = "tx_p1",
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "tx_p1",
                status = SyncQueueEntity.STATUS_PENDING,
                createdAt = 1000L
            )
        )
        qDao.insert(
            SyncQueueEntity(
                id = 2L,
                transactionId = "tx_p2",
                operationType = SyncQueueEntity.OP_INSERT,
                payloadJson = "tx_p2",
                status = SyncQueueEntity.STATUS_PENDING,
                createdAt = 2000L
            )
        )

        // An older already-synced transaction that should NOT be re-synced to fresh workbook
        val oldSynced = sampleTransaction("tx_old_synced", nov2026 - 100000, syncState = SyncState.SYNCED.name, sheetRowIndex = 4)
        tDao.insert(oldSynced)

        val server = ScriptedSheetsServer(listOf("Nov26"))
        val sheets = server.client()

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_1","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"fresh_sheet_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

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

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            onWorkbookReplaced = { y, _, _ ->
                repo.requeueYearForNewWorkbook(y)
            }
        )

        // Create fresh workbook with Start empty (copyHistory = false)
        val res = resolver.createFreshWorkbook(year, copyHistory = false)
        assertTrue(res is Result.Success)
        assertEquals("fresh_sheet_id", (res as Result.Success).data)

        // 1. Pending items are preserved (not cleared)
        val pendingBeforeSync = qDao.getAllPending()
        assertEquals("2 pending items must be preserved in queue", 2, pendingBeforeSync.size)

        // 2. Run sync with production repository
        val syncRes = repo.syncPendingTransactions()
        assertTrue(syncRes is Result.Success)
        assertEquals("Both pending items must be synced", 2, (syncRes as Result.Success).data)

        // 3. Assert what the new workbook receives: appends and writes targeted fresh_sheet_id
        val appendsAndWrites = server.calls.filter {
            (it.method == "POST" && it.url.contains(":append")) ||
                (it.method == "PUT" && it.url.contains("/values/")) ||
                (it.method == "POST" && it.url.contains("values:batchUpdate"))
        }
        assertTrue("Requests must be recorded", appendsAndWrites.isNotEmpty())
        assertTrue(
            "All sync writes must target the new fresh workbook",
            appendsAndWrites.all { it.url.contains("fresh_sheet_id") }
        )

        val recordedBodies = appendsAndWrites.map { it.body }
        assertTrue("New workbook must receive tx_p1", recordedBodies.any { it.contains("tx_p1") })
        assertTrue("New workbook must receive tx_p2", recordedBodies.any { it.contains("tx_p2") })
        assertFalse("New workbook must NOT receive old synced transaction", recordedBodies.any { it.contains("tx_old_synced") })

        // 4. Queue is now empty and transactions marked SYNCED
        assertEquals(0, qDao.getAllPending().size)
        assertEquals(SyncState.SYNCED.name, tDao.getById("tx_p1")?.syncState)
        assertEquals(SyncState.SYNCED.name, tDao.getById("tx_p2")?.syncState)
    }
}
