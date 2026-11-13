package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.model.VoidInflowRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import okio.Buffer

class VoidInflowExecutorTest {

    private val tokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "token"
        override fun refreshAccessToken(): String = "token2"
    }

    private class Recorder {
        val writes = mutableListOf<String>()
    }

    /** Serves AD (uuid) and R (reason) reads and records value writes. */
    private fun executor(recorder: Recorder, uuidColumn: List<String>, reason: String): AccountingSyncExecutor {
        val interceptor = Interceptor { chain ->
            val request: Request = chain.request()
            val url = URLDecoder.decode(request.url.toString(), "UTF-8")
            val body = when {
                request.method == "POST" && url.contains("values:batchUpdate") -> {
                    val buffer = Buffer()
                    request.body?.writeTo(buffer)
                    recorder.writes.add(buffer.readUtf8())
                    "{}"
                }
                url.contains("!AD4:AD") ->
                    """{"values":[${uuidColumn.joinToString(",") { """["$it"]""" }}]}"""
                url.contains("!R") -> """{"values":[["$reason"]]}"""
                else -> "{}"
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val http = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return AccountingSyncExecutor(
            googleSheetsClient = GoogleSheetsClient(http, tokenProvider),
            googleDriveClient = GoogleDriveClient(okHttpClient = http, tokenProvider = tokenProvider)
        )
    }

    private fun voidItem(id: String) = SyncQueueEntity(
        transactionId = id,
        operationType = SyncQueueEntity.OP_VOID_INFLOW,
        payloadJson = AccountingRequestJsonAdapter.voidInflowRequestToJson(VoidInflowRequest(id, 1794387699000L))
    )

    @Test
    fun voidZeroesTheAmountAndPrefixesTheReasonOnTheRowFoundByUuid() = runBlocking {
        val recorder = Recorder()
        val exec = executor(recorder, uuidColumn = listOf("cred_other", "cred_abc123"), reason = "Salary")

        val result = exec.executeVoidInflow(voidItem("cred_abc123"), "sheet1", "Nov26")

        assertTrue(result is Result.Success)
        assertEquals(1, recorder.writes.size)
        val payload = recorder.writes[0]
        // The second entry of the uuid column is row 5
        assertTrue(payload, payload.contains("'Nov26'!R5"))
        assertTrue(payload, payload.contains("[Void] Salary"))
        assertTrue(payload, payload.contains("'Nov26'!S5"))
        assertTrue(payload, payload.contains("[[0.0]]"))
        assertTrue(payload, !payload.contains("'Nov26'!K5"))
    }

    @Test
    fun alreadyPrefixedReasonIsNotPrefixedAgain() = runBlocking {
        val recorder = Recorder()
        val exec = executor(recorder, uuidColumn = listOf("cred_abc123"), reason = "[Void] Salary")

        exec.executeVoidInflow(voidItem("cred_abc123"), "sheet1", "Nov26")

        assertTrue(recorder.writes[0], !recorder.writes[0].contains("[Void] [Void]"))
    }

    @Test
    fun rowNotFoundIsAnErrorAndWritesNothing() = runBlocking {
        val recorder = Recorder()
        val exec = executor(recorder, uuidColumn = listOf("inf_randomold"), reason = "Salary")

        val result = exec.executeVoidInflow(voidItem("cred_abc123"), "sheet1", "Nov26")

        assertTrue(result is Result.Error)
        assertTrue(recorder.writes.isEmpty())
    }

    @Test
    fun creditPlanStoresTheCreditRequestIdAsTheRowUuid() {
        val plan = AccountingPlan(
            appends = listOf(
                NewRow(TableType.PAYABLE, "Nov26", "pay_1", listOf("x")),
                NewRow(TableType.INFLOW, "Nov26", "inf_random", listOf("y"))
            )
        )

        val fixed = plan.withInflowRowUuid("cred_abc123")

        assertEquals("pay_1", fixed.appends[0].uuid)
        assertEquals("cred_abc123", fixed.appends[1].uuid)
        assertEquals(plan, plan.withInflowRowUuid(""))
    }
}
