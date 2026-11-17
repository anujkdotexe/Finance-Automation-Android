package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingPlanJsonAdapter
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the sync loop reacts to each class of Google error. Written against a server that can fail chosen requests. */
class SyncErrorHandlingTest {

    private fun metadataCall(call: ScriptedCall) = call.method == "GET" && !call.url.contains("/values/")

    private fun SyncHarness.queueOf(id: String) = qDao.queue.values.single { it.transactionId == id }

    private suspend fun SyncHarness.saveAndSync(id: String): Result<Int> {
        repo.insertTransaction(expense(id))
        return repo.syncPendingTransactions()
    }

    // S-2: rate limits and timeouts are retryable

    @Test
    fun rateLimitsAndTimeoutsAreRetriedNotFailedPermanently() = runBlocking {
        val cases = listOf(
            429 to SyncHarness.errorBody(429, "rateLimitExceeded"),
            408 to "Request Timeout",
            403 to SyncHarness.errorBody(403, "rateLimitExceeded"),
            403 to SyncHarness.errorBody(403, "userRateLimitExceeded"),
            503 to "unavailable"
        )
        for ((code, body) in cases) {
            val h = SyncHarness()
            h.server.fail = { call, _ -> if (metadataCall(call)) code to body else null }
            val res = h.saveAndSync("txn_a")
            val item = h.queueOf("txn_a")
            assertTrue("run reports a retryable error for $code", res is Result.Error)
            assertNotEquals("HTTP $code must not become retry 999", 999, item.retryCount)
            assertEquals("HTTP $code counts one retry", 1, item.retryCount)
            assertEquals(
                "HTTP $code must not send the row to review",
                ReviewState.CONFIRMED.name, h.tDao.rows["txn_a"]!!.reviewState
            )
        }
    }

    @Test
    fun aPermissionDeniedStaysPermanent() = runBlocking {
        val h = SyncHarness()
        h.server.fail = { call, _ -> if (metadataCall(call)) 403 to SyncHarness.errorBody(403, "forbidden") else null }
        h.saveAndSync("txn_a")
        val item = h.queueOf("txn_a")
        assertEquals(999, item.retryCount)
        assertTrue("the reason is kept for the failed item", !item.lastErrorDetail.isNullOrBlank())
        assertEquals("a failed item does not send the row back to review", ReviewState.CONFIRMED.name, h.tDao.rows["txn_a"]!!.reviewState)
    }

    // S-3: an expired token pauses the queue, it does not damage it

    @Test
    fun anExpiredTokenLeavesEveryItemPendingAndUntouched() = runBlocking {
        val h = SyncHarness()
        h.server.fail = { _, _ -> 401 to "unauthorized" }
        h.repo.insertTransaction(h.expense("txn_a"))
        h.repo.insertTransaction(h.expense("txn_b", merchant = "Bus"))
        val res = h.repo.syncPendingTransactions()
        assertTrue(res is Result.Error)
        for (id in listOf("txn_a", "txn_b")) {
            val item = h.queueOf(id)
            assertEquals(SyncQueueEntity.STATUS_PENDING, item.status)
            assertEquals(0, item.retryCount)
            assertEquals(ReviewState.CONFIRMED.name, h.tDao.rows[id]!!.reviewState)
        }
        assertEquals("AUTH_EXPIRED", h.queueOf("txn_a").lastErrorKind)
        assertEquals("the run stops at the first expired token", 1, h.server.calls.size)
    }

    @Test
    fun theQueueResumesOnceTheTokenWorksAgain() = runBlocking {
        val h = SyncHarness()
        var expired = true
        h.server.fail = { _, _ -> if (expired) 401 to "unauthorized" else null }
        h.repo.insertTransaction(h.expense("txn_a"))
        h.repo.syncPendingTransactions()
        expired = false
        val res = h.repo.syncPendingTransactions()
        assertTrue(res is Result.Success)
        assertEquals(SyncState.SYNCED.name, h.tDao.rows["txn_a"]!!.syncState)
        assertTrue(h.qDao.queue.isEmpty())
    }

    // S-7: one failing item does not hold back unrelated ones

    @Test
    fun aServerErrorOnOneItemStillSyncsTheOthers() = runBlocking {
        val h = SyncHarness()
        var uuidLookups = 0
        h.server.fail = { call, _ ->
            if (call.method == "GET" && call.url.contains("/values/") && call.url.contains("AC")) {
                uuidLookups++
                if (uuidLookups == 1) 500 to "boom" else null
            } else null
        }
        h.repo.insertTransaction(h.expense("txn_a"))
        h.repo.insertTransaction(h.expense("txn_b", merchant = "Bus"))
        val res = h.repo.syncPendingTransactions()
        assertTrue("run still reports the failure so it is retried", res is Result.Error)
        assertEquals(1, h.queueOf("txn_a").retryCount)
        assertEquals(SyncState.SYNCED.name, h.tDao.rows["txn_b"]!!.syncState)
        assertFalse(h.qDao.queue.values.any { it.transactionId == "txn_b" })
    }

    @Test
    fun noNetworkStopsTheRunInsteadOfBurningEveryRetry() = runBlocking {
        val h = SyncHarness()
        h.server.fail = { call, _ ->
            if (metadataCall(call)) throw java.net.UnknownHostException("offline") else null
        }
        h.repo.insertTransaction(h.expense("txn_a"))
        h.repo.insertTransaction(h.expense("txn_b", merchant = "Bus"))
        h.repo.syncPendingTransactions()
        // Transient network failures are excluded from the retry cap and keep retryCount at 0
        assertEquals(0, h.queueOf("txn_a").retryCount)
        assertEquals("the second item is not tried while offline", 0, h.queueOf("txn_b").retryCount)
    }

    // S-9: a failed existence check must not append

    @Test
    fun aFailedExistenceCheckReturnsTheErrorInsteadOfAppending() = runBlocking {
        val h = SyncHarness()
        h.repo.insertTransaction(h.expense("txn_a"))
        val tab = com.financeautomation.testutil.ScriptedSheetsServer.currentTab()
        val plan = AccountingPlan(
            appends = listOf(NewRow(TableType.DEBIT, tab, "txn_a", listOf(46000, 0.5, "SBI", "Tea", "", "Food & Drink", 40.0, "Personal", 40.0, 0.0, "Personal")))
        )
        val item = h.queueOf("txn_a")
        h.qDao.updatePlanJson(item.id, AccountingPlanJsonAdapter.toJson(plan))
        h.server.fail = { call, _ ->
            if (call.method == "GET" && call.url.contains("/values/") && call.url.contains("AC")) {
                403 to SyncHarness.errorBody(403, "forbidden")
            } else null
        }
        h.repo.syncPendingTransactions()
        val writes = h.server.calls.count { it.method != "GET" }
        assertEquals("nothing may be written after a failed existence check", 0, writes)
        assertNotEquals(SyncState.SYNCED.name, h.tDao.rows["txn_a"]!!.syncState)
    }

    @Test
    fun driveCheckErrorLeavesTheQueueItemPendingForRetry() = runBlocking {
        val currentYear = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).year
        val fakeTokens = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }
        val failingDriveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                Response.Builder()
                    .request(r)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("""{"error":{"code":500,"message":"Drive error"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }).build(),
            fakeTokens
        )

        val h = SyncHarness(resolverFactory = { prefs, sheets ->
            WorkbookResolver(
                googleDriveClient = failingDriveClient,
                googleSheetsClient = sheets,
                preferencesRepository = prefs
            )
        })
        // Ensure no last verified date is set today so the daily check is triggered
        h.preferences[PreferencesRepository.keyLastWorkbookVerifiedDate(currentYear)] = null
        h.preferences[PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR] = null

        val res = h.saveAndSync("txn_drive_err")
        val item = h.queueOf("txn_drive_err")

        assertTrue("Sync run must report error when Drive check fails", res is Result.Error)
        assertEquals("Queue item must remain in STATUS_PENDING for retry", SyncQueueEntity.STATUS_PENDING, item.status)
        assertNotEquals("Queue item must not be marked permanently failed (999)", 999, item.retryCount)
        assertEquals("Retry count must be incremented for retryable server error", 1, item.retryCount)
        assertEquals("Transaction review state must remain CONFIRMED", ReviewState.CONFIRMED.name, h.tDao.rows["txn_drive_err"]!!.reviewState)
        assertFalse("Last error detail must not be empty", item.lastErrorDetail.isNullOrBlank())
    }
}

private typealias ScriptedCall = com.financeautomation.testutil.ScriptedSheetsServer.Call
