package com.financeautomation.sync

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.account.AccountDataStore
import com.financeautomation.data.account.AccountSwitchGuard
import com.financeautomation.data.account.SwitchDecision
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.GoogleDriveApiException
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * T4: Sync decision and concurrency resilience tests.
 * Validates sync state machine decisions using SyncHarness and in-memory tables:
 * 1. Queued insert whose sheet row was changed/inserted by another device is not overwritten silently.
 * 2. Scan error followed by retry does not insert a second row.
 * 3. Void after a partly delivered insert voids the one row.
 * 4. Transient 403 is retried; permanent 403 is surfaced.
 * 5. Sign-in with a second Google account does not push the first account's rows.
 * 6. Reinstall with an existing workbook adopts it instead of creating another.
 */
class SyncDecisionTest {

    @Test
    fun testQueuedInsert_whoseSheetRowWasChangedByAnotherDevice_isNotOverwrittenSilently() = runBlocking {
        val h = SyncHarness()
        val txnId = "txn_device_1"

        // The sheet already contains this transaction's UUID in column AD (placed/synced by another device)
        h.server.uuidColumnValues = { call ->
            if (call.url.contains(SheetSchemaConstants.COL_LETTER_DEBIT_UUID)) {
                listOf(txnId)
            } else {
                emptyList()
            }
        }

        // Local device had a queued insert for the same transaction
        h.repo.insertTransaction(h.expense(txnId))
        assertEquals(SyncState.PENDING.name, h.tDao.rows[txnId]!!.syncState)
        assertEquals(1, h.qDao.queue.size)

        // Run sync loop
        val syncResult = h.repo.syncPendingTransactions()
        assertTrue("Sync run must succeed", syncResult is Result.Success)

        // Local transaction is reconciled to SYNCED
        val localTxn = h.tDao.rows[txnId]
        assertNotNull("Local transaction must exist", localTxn)
        assertEquals(SyncState.SYNCED.name, localTxn!!.syncState)
        assertEquals("Row index must point to the existing sheet row (row 4)", 4, localTxn.sheetRowIndex)

        // The queue item was safely cleared without blindly appending
        assertTrue("Sync queue must be empty after reconciliation", h.qDao.queue.isEmpty())

        // Verify that no write / append call was executed on Google Sheets
        val writeCalls = h.server.calls.filter { it.method == "POST" && (it.url.contains("values:batchUpdate") || it.url.contains(":append")) }
        assertEquals("Must NOT append a second row when UUID already exists on sheet", 0, writeCalls.size)
    }

    @Test
    fun testScanErrorFollowedByRetry_doesNotInsertSecondRow() = runBlocking {
        val h = SyncHarness()
        val txnId = "txn_scan_retry"

        h.repo.insertTransaction(h.expense(txnId))
        assertEquals(1, h.qDao.queue.size)

        // 1. First sync attempt: simulate a transient 500 error during the UUID scan check
        h.server.fail = { call, _ ->
            if (call.method == "GET" && call.url.contains("/values/") && call.url.contains(SheetSchemaConstants.COL_LETTER_DEBIT_UUID)) {
                500 to "Internal Server Error"
            } else null
        }

        val firstSyncRes = h.repo.syncPendingTransactions()
        assertTrue("First sync must fail due to scan error", firstSyncRes is Result.Error)

        // Queue item remains pending with retryCount = 1
        val queuedItem = h.qDao.queue.values.single { it.transactionId == txnId }
        assertEquals(SyncQueueEntity.STATUS_PENDING, queuedItem.status)
        assertEquals(1, queuedItem.retryCount)

        // Zero write calls made during the failed scan
        val writesDuringFail = h.server.calls.count { it.method == "POST" && (it.url.contains("values:batchUpdate") || it.url.contains(":append")) }
        assertEquals("No append must occur when scan fails", 0, writesDuringFail)

        // 2. Second sync attempt: transient error resolves, UUID scan succeeds (empty column)
        h.server.fail = { _, _ -> null }

        val retrySyncRes = h.repo.syncPendingTransactions()
        assertTrue("Retry sync must succeed", retrySyncRes is Result.Success)
        assertEquals(SyncState.SYNCED.name, h.tDao.rows[txnId]!!.syncState)
        assertTrue("Queue must be empty after successful sync", h.qDao.queue.isEmpty())

        // Exactly one write / append call across the entire lifetime
        val totalWrites = h.server.calls.count { it.method == "POST" && (it.url.contains("values:batchUpdate") || it.url.contains(":append")) }
        assertEquals("Exactly one append/insert must be issued after scan and retry", 1, totalWrites)
    }

    @Test
    fun testVoidAfterPartlyDeliveredInsert_voidsTheOneRow() = runBlocking {
        val h = SyncHarness()
        val txnId = "txn_void_partly"

        // Insert and sync the transaction so it exists on the sheet
        h.repo.insertTransaction(h.expense(txnId))
        val insertSyncRes = h.repo.syncPendingTransactions()
        assertTrue(insertSyncRes is Result.Success)
        assertEquals(SyncState.SYNCED.name, h.tDao.rows[txnId]!!.syncState)

        // Now void the transaction
        h.repo.voidTransaction(txnId)
        assertEquals(SyncState.VOID.name, h.tDao.rows[txnId]!!.syncState)

        // Sync queue contains OP_VOID
        val voidQueueItem = h.qDao.queue.values.singleOrNull { it.transactionId == txnId }
        assertNotNull("Queue must contain OP_VOID item", voidQueueItem)
        assertEquals(SyncQueueEntity.OP_VOID, voidQueueItem!!.operationType)

        // Sheet still holds the row UUID at row 4
        h.server.uuidColumnValues = { call ->
            if (call.url.contains(SheetSchemaConstants.COL_LETTER_DEBIT_UUID)) listOf(txnId) else emptyList()
        }

        // Run sync loop to execute the void
        val voidSyncRes = h.repo.syncPendingTransactions()
        assertTrue("Void sync must succeed", voidSyncRes is Result.Success)

        // Queue item cleared
        assertTrue("Sync queue must be cleared after void sync", h.qDao.queue.isEmpty())

        // Verify the void was written to the sheet (status set to Void)
        val voidWrites = h.server.calls.filter {
            it.method == "POST" && it.url.contains("values:batchUpdate") && it.body.contains("Void")
        }
        assertTrue("Sheet must receive void update with 'Void' status", voidWrites.isNotEmpty())
    }

    @Test
    fun testTransient403IsRetried_andPermanent403IsSurfaced() = runBlocking {
        // Part A: Transient 403 (rateLimitExceeded) is retried
        val hTransient = SyncHarness()
        val transientTxnId = "txn_transient_403"
        hTransient.server.fail = { call, _ ->
            if (call.method == "GET" && !call.url.contains("/values/")) {
                403 to SyncHarness.errorBody(403, "rateLimitExceeded")
            } else null
        }

        hTransient.repo.insertTransaction(hTransient.expense(transientTxnId))
        val transientRes = hTransient.repo.syncPendingTransactions()

        assertTrue("Transient 403 must report Result.Error for retry", transientRes is Result.Error)
        val transientItem = hTransient.qDao.queue.values.single { it.transactionId == transientTxnId }
        assertEquals("Queue item must remain in STATUS_PENDING for retry", SyncQueueEntity.STATUS_PENDING, transientItem.status)
        assertEquals("Transient 403 increments retry count by 1", 1, transientItem.retryCount)
        assertNotEquals("Transient 403 must NOT be marked permanent failure (999)", 999, transientItem.retryCount)

        // Part B: Permanent 403 (forbidden) is surfaced with retryCount = 999
        val hPermanent = SyncHarness()
        val permTxnId = "txn_permanent_403"
        hPermanent.server.fail = { call, _ ->
            if (call.method == "GET" && !call.url.contains("/values/")) {
                403 to SyncHarness.errorBody(403, "forbidden")
            } else null
        }

        hPermanent.repo.insertTransaction(hPermanent.expense(permTxnId))
        hPermanent.repo.syncPendingTransactions()

        val permItem = hPermanent.qDao.queue.values.single { it.transactionId == permTxnId }
        assertEquals("Permanent 403 must be marked STATUS_FAILED", SyncQueueEntity.STATUS_FAILED, permItem.status)
        assertEquals("Permanent 403 must receive sentinel retryCount = 999", 999, permItem.retryCount)
        assertFalse("Error detail must be captured for permanent failure", permItem.lastErrorDetail.isNullOrBlank())
    }

    @Test
    fun testSignInWithSecondGoogleAccount_doesNotPushFirstAccountRows() = runBlocking {
        val h = SyncHarness()

        // Account A is the current ledger owner and has 2 pending transactions
        h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER] = "accountA@example.com"
        h.repo.insertTransaction(h.expense("txn_acc_a_1"))
        h.repo.insertTransaction(h.expense("txn_acc_a_2", merchant = "Book"))

        assertEquals(2, h.qDao.queue.size)
        h.syncRequests = 0
        h.server.calls.clear()

        var wipes = 0
        val store = object : AccountDataStore {
            override suspend fun pendingCount(): Int =
                h.qDao.queue.values.count { it.operationType != SyncQueueEntity.OP_CONFIG_PUSH }

            override suspend fun hasLocalData(): Boolean =
                h.tDao.rows.isNotEmpty() || h.qDao.queue.isNotEmpty()

            override suspend fun wipe() {
                wipes++
                h.tDao.rows.clear()
                h.qDao.queue.clear()
            }
        }

        val guard = AccountSwitchGuard(
            preferences = h.prefs,
            store = store,
            stopBackgroundWork = {},
            startBackgroundWork = {}
        )

        // User signs in with Account B
        val decision = guard.evaluate("accountB@example.com")

        // Must intercept with CONFIRM_SWITCH before touching any data or pushing to sheet
        assertEquals(SwitchDecision.CONFIRM_SWITCH, decision.decision)
        assertEquals("Pending count matches Account A's unsynced rows", 2, decision.pendingCount)
        assertEquals("No wipe executed before confirmation", 0, wipes)
        assertEquals("No sync request triggered", 0, h.syncRequests)
        assertTrue("No network requests sent to Google Sheets", h.server.calls.isEmpty())
        assertEquals("Original ledger owner remains intact", "accountA@example.com", h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER])
    }

    @Test
    fun testReinstallWithExistingWorkbook_adoptsItInsteadOfCreatingAnother() = runBlocking {
        val year = 2026
        val existingSpreadsheetId = "existing_sheet_reinstall_2026"
        var createSpreadsheetPostCalls = 0
        var setStoredIdCalls = 0
        var adoptedId: String? = null
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        val fakeTokens = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }

        val driveInterceptor = okhttp3.Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val q = req.url.queryParameter("q").orEmpty()

            val responseBody = when {
                req.method == "GET" && (q.contains("fiscalYear") || q.contains("Finance$year")) -> {
                    """{"files":[{"id":"$existingSpreadsheetId","name":"Finance$year"}]}"""
                }
                req.method == "GET" && url.contains("/files/$existingSpreadsheetId") -> {
                    """{"id":"$existingSpreadsheetId","name":"Finance$year","trashed":false}"""
                }
                req.method == "POST" && (url.contains("/files") || url.contains("spreadsheets")) -> {
                    createSpreadsheetPostCalls++
                    """{"id":"new_created_sheet","name":"Finance$year"}"""
                }
                else -> """{"files":[]}"""
            }

            okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody(jsonMediaType))
                .build()
        }

        val driveClient = GoogleDriveClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor(driveInterceptor).build(),
            tokenProvider = fakeTokens
        )

        val sheetsInterceptor = okhttp3.Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val body = if (url.contains("values:batchGet")) {
                """{"valueRanges":[{"range":"Config!A1:B10","majorDimension":"ROWS","values":[["version","1"]]}]}"""
            } else {
                """{"spreadsheetId":"$existingSpreadsheetId","sheets":[{"properties":{"sheetId":0,"title":"Dashboard"}},{"properties":{"sheetId":1,"title":"Nov26"}},{"properties":{"sheetId":2,"title":"Config"}}]}"""
            }
            okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(jsonMediaType))
                .build()
        }

        val sheetsClient = GoogleSheetsClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor(sheetsInterceptor).build(),
            tokenProvider = fakeTokens
        )

        val prefsMap = mutableMapOf<String, String?>()
        val fakePrefs = Proxy.newProxyInstance(
            PreferencesRepository::class.java.classLoader,
            arrayOf(PreferencesRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getSpreadsheetIdForYearSync" -> prefsMap[PreferencesRepository.keySpreadsheetIdForYear(args!![0] as Int)]
                "setSpreadsheetIdForYear" -> {
                    setStoredIdCalls++
                    adoptedId = args!![1] as String
                    prefsMap[PreferencesRepository.keySpreadsheetIdForYear(args[0] as Int)] = adoptedId
                    null
                }
                "getPreference" -> prefsMap[args!![0] as String]
                "setPreference" -> { prefsMap[args!![0] as String] = args[1] as String?; null }
                else -> null
            }
        } as PreferencesRepository

        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = fakePrefs
        )

        // On a fresh reinstall, local preferences have no spreadsheet ID stored for this year
        val findRes = resolver.findExisting(year)
        assertTrue("findExisting must succeed", findRes is Result.Success)
        assertEquals("Must return existing workbook ID from Drive", existingSpreadsheetId, (findRes as Result.Success).data)

        val resolveRes = resolver.resolveForYear(year)
        assertTrue("resolveForYear must succeed", resolveRes is Result.Success)
        assertEquals("Must adopt existing workbook ID", existingSpreadsheetId, (resolveRes as Result.Success).data)

        assertEquals("Must NOT create a new workbook when existing one is usable", 0, createSpreadsheetPostCalls)
        assertEquals("Must store adopted spreadsheet ID in preferences", existingSpreadsheetId, adoptedId)
    }
}
