package com.financeautomation.data.remote

import com.financeautomation.R
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.repository.PreferencesRepository
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
}
