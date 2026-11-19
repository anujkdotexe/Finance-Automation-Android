package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
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
        val appends = mutableListOf<String>()
        val readRanges = mutableListOf<String>()
    }

    /** Serves AE (inflow uuid) and S (reason) reads and records value writes. */
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
                request.method == "POST" && url.contains("values/") && url.contains(":append") -> {
                    val buffer = Buffer()
                    request.body?.writeTo(buffer)
                    recorder.appends.add(buffer.readUtf8())
                    "{}"
                }
                url.contains("!${SheetSchemaConstants.COL_LETTER_INFLOW_UUID}4:${SheetSchemaConstants.COL_LETTER_INFLOW_UUID}") -> {
                    recorder.readRanges.add(SheetSchemaConstants.COL_LETTER_INFLOW_UUID)
                    """{"values":[${uuidColumn.joinToString(",") { """["$it"]""" }}]}"""
                }
                url.contains("!${SheetSchemaConstants.COL_LETTER_DEBIT_UUID}4:${SheetSchemaConstants.COL_LETTER_DEBIT_UUID}") -> {
                    recorder.readRanges.add(SheetSchemaConstants.COL_LETTER_DEBIT_UUID)
                    """{"values":[${uuidColumn.joinToString(",") { """["$it"]""" }}]}"""
                }
                url.contains("!${SheetSchemaConstants.COL_LETTER_PAYABLE_UUID}4:${SheetSchemaConstants.COL_LETTER_PAYABLE_UUID}") -> {
                    recorder.readRanges.add(SheetSchemaConstants.COL_LETTER_PAYABLE_UUID)
                    """{"values":[${uuidColumn.joinToString(",") { """["$it"]""" }}]}"""
                }
                url.contains("!${SheetSchemaConstants.COL_LETTER_INFLOW_REASON}") -> {
                    recorder.readRanges.add(SheetSchemaConstants.COL_LETTER_INFLOW_REASON)
                    """{"values":[["$reason"]]}"""
                }
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
        assertTrue(payload, payload.contains("'Nov26'!${SheetSchemaConstants.COL_LETTER_INFLOW_REASON}5"))
        assertTrue(payload, payload.contains("[Void] Salary"))
        assertTrue(payload, payload.contains("'Nov26'!${SheetSchemaConstants.COL_LETTER_INFLOW_AMOUNT}5"))
        assertTrue(payload, payload.contains("[[0.0]]"))
        assertTrue(payload, !payload.contains("'Nov26'!K5"))
        // Proves lookup queried column AE (Inflow UUID), not AD (Debit UUID)
        assertTrue(recorder.readRanges.contains(SheetSchemaConstants.COL_LETTER_INFLOW_UUID))
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

    @Test
    fun retriedPlanDoesNotAppendDuplicateRowsWhenUuidsExistInColumnsAD_AE_AF() = runBlocking {
        val recorder = Recorder()
        val exec = executor(
            recorder,
            uuidColumn = listOf("deb_123", "inf_456", "pay_789"),
            reason = "Test"
        )

        val plan = AccountingPlan(
            appends = listOf(
                NewRow(TableType.DEBIT, "Nov26", "deb_123", listOf("d1")),
                NewRow(TableType.INFLOW, "Nov26", "inf_456", listOf("i1")),
                NewRow(TableType.PAYABLE, "Nov26", "pay_789", listOf("p1"))
            )
        )
        val item = SyncQueueEntity(
            transactionId = "tx_retry",
            operationType = SyncQueueEntity.OP_LOG_CREDIT,
            payloadJson = "{}",
            planJson = AccountingPlanJsonAdapter.toJson(plan)
        )

        val result = exec.executeLogCredit(item, "sheet1", "Nov26")

        assertTrue(result is Result.Success)
        // Since all three UUIDs were found in columns AD, AE, and AF, no appends must be made!
        assertTrue("No appends should occur on retry when rows exist", recorder.appends.isEmpty())
        // Proves that all three 32-column UUID letters were checked
        assertTrue(recorder.readRanges.contains(SheetSchemaConstants.COL_LETTER_DEBIT_UUID))
        assertTrue(recorder.readRanges.contains(SheetSchemaConstants.COL_LETTER_INFLOW_UUID))
        assertTrue(recorder.readRanges.contains(SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))
    }
}
