package com.financeautomation.data

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.LineageEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.LogRepaymentRequest
import com.financeautomation.domain.usecase.CarryForwardUseCase
import com.financeautomation.domain.usecase.NettingUseCase
import com.financeautomation.domain.usecase.RepaymentWaterfallUseCase
import com.financeautomation.domain.usecase.SettlementWaterfallUseCase
import com.financeautomation.domain.usecase.YearCloseUseCase
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

class RepaymentQueueSafetyTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = queue.values.firstOrNull()
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = queue.values.toList()
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = queue.values.sortedBy { it.createdAt }
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
        override suspend fun resetQueueItem(id: Long) {}
        override suspend fun recoverRetryableFailedItems(): Int = 0
        override suspend fun countPendingOperations(opType: String): Int = 0
        override suspend fun updatePlanJson(id: Long, planJson: String?) {
            queue[id]?.let { queue[id] = it.copy(planJson = planJson) }
        }
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
        override suspend fun getActiveTransactionIds(): List<String> = emptyList()
        override suspend fun deletePendingForTransactions(transactionIds: List<String>) {}
    }

    private class FakeLineageDao : LineageDao {
        val records = ConcurrentHashMap<String, LineageEntity>()
        override suspend fun insert(entity: LineageEntity) { records[entity.uuid] = entity }
        override suspend fun insertAll(entities: List<LineageEntity>) { entities.forEach { insert(it) } }
        override suspend fun getByUuid(uuid: String): LineageEntity? = records[uuid]
        override suspend fun getByCarriedFrom(carriedFromUuid: String): LineageEntity? =
            records.values.firstOrNull { it.carriedFromUuid == carriedFromUuid }
        override suspend fun getAll(): List<LineageEntity> = records.values.toList()
        override suspend fun updateUuid(oldUuid: String, newUuid: String) {}
        override suspend fun updateCarriedFromUuid(oldUuid: String, newUuid: String) {}
        override suspend fun updateRootUuid(oldUuid: String, newUuid: String) {}
    }

    private class RepaymentMockSheets {
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val writtenBodies = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }

        fun createClient(): GoogleSheetsClient {
            val interceptor = Interceptor { chain ->
                val request = chain.request()
                val rawUrl = request.url.toString()
                val url = java.net.URLDecoder.decode(rawUrl, "UTF-8")
                val method = request.method

                val bodyText = request.body?.let { b ->
                    val buf = okio.Buffer()
                    b.writeTo(buf)
                    buf.readUtf8()
                }.orEmpty()
                if (bodyText.isNotEmpty()) writtenBodies.add(url to bodyText)

                val responseBody = when {
                    // Metadata for 2026 workbook
                    method == "GET" && url.contains("sheet_2026") && !url.contains("/values") ->
                        """{"spreadsheetId":"sheet_2026","sheets":[{"properties":{"sheetId":1,"title":"Dec26","gridProperties":{"rowCount":100,"columnCount":35}}}]}"""
                    // Metadata for 2027 workbook
                    method == "GET" && url.contains("sheet_2027") && !url.contains("/values") ->
                        """{"spreadsheetId":"sheet_2027","sheets":[{"properties":{"sheetId":2,"title":"Jan27","gridProperties":{"rowCount":100,"columnCount":35}}}]}"""
                    // BatchGet for 2026: empty payables (debt was already carried forward or not present)
                    method == "GET" && url.contains("sheet_2026") && url.contains("values:batchGet") -> {
                        """{
                            "valueRanges": [
                                {"range": "'Dec26'!L3:L3", "majorDimension": "ROWS", "values": [["Note"]]},
                                {"range": "'Dec26'!A4:L", "majorDimension": "ROWS", "values": []},
                                {"range": "'Dec26'!AD4:AD", "majorDimension": "ROWS", "values": []},
                                {"range": "'Dec26'!N4:U", "majorDimension": "ROWS", "values": []},
                                {"range": "'Dec26'!AE4:AE", "majorDimension": "ROWS", "values": []},
                                {"range": "'Dec26'!W4:AC", "majorDimension": "ROWS", "values": []},
                                {"range": "'Dec26'!AF4:AF", "majorDimension": "ROWS", "values": []}
                            ]
                        }"""
                    }
                    // BatchGet for 2027: Bob's debt carried forward into Jan27
                    method == "GET" && url.contains("sheet_2027") && url.contains("values:batchGet") -> {
                        """{
                            "valueRanges": [
                                {"range": "'Jan27'!L3:L3", "majorDimension": "ROWS", "values": [["Note"]]},
                                {"range": "'Jan27'!A4:L", "majorDimension": "ROWS", "values": []},
                                {"range": "'Jan27'!AD4:AD", "majorDimension": "ROWS", "values": []},
                                {"range": "'Jan27'!N4:U", "majorDimension": "ROWS", "values": []},
                                {"range": "'Jan27'!AE4:AE", "majorDimension": "ROWS", "values": []},
                                {"range": "'Jan27'!W4:AC", "majorDimension": "ROWS", "values": [
                                    [46419, 0.5, "Bob", "Dinner share", 500.0, "", "Pending"]
                                ]},
                                {"range": "'Jan27'!AF4:AF", "majorDimension": "ROWS", "values": [["pay_bob_jan27"]]}
                            ]
                        }"""
                    }
                    // UUID lookups in Jan27
                    method == "GET" && url.contains("sheet_2027") && (url.contains("AF4:AF") || url.contains("AF")) ->
                        """{"range":"'Jan27'!AF4:AF","majorDimension":"ROWS","values":[["pay_bob_jan27"]]}"""
                    // BatchUpdate or PUT values
                    method == "POST" && url.contains("batchUpdate") ->
                        """{"responses":[]}"""
                    method == "PUT" && url.contains("/values/") ->
                        """{"updatedCells":1}"""
                    else ->
                        """{"range":"'Sheet'!A1:L1","majorDimension":"ROWS","values":[]}"""
                }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody(jsonMediaType))
                    .build()
            }
            val okHttp = OkHttpClient.Builder().addInterceptor(interceptor).build()
            return GoogleSheetsClient(okHttp, tokenProvider)
        }
    }

    @Test
    fun testDecemberRepaymentLocatesDebtInNextYearWorkbookAndApplies() = runBlocking {
        val qDao = FakeSyncQueueDao()
        val lDao = FakeLineageDao()
        val mockSheets = RepaymentMockSheets()
        val sheetsClient = mockSheets.createClient()
        val driveClient = GoogleDriveClient(OkHttpClient(), mockSheets.tokenProvider)

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheetsClient,
            googleDriveClient = driveClient,
            syncQueueDao = qDao,
            lineageDao = lDao,
            nextWorkbookIdProvider = { year -> if (year == 2027) "sheet_2027" else null }
        )

        val req = LogRepaymentRequest(
            requestId = "req_repay_bob",
            creditor = "Bob",
            amount = 500.0,
            account = "SBI",
            timestamp = 1798761600000L, // Dec 31, 2026
            note = "Repaying dinner debt"
        )
        val payloadJson = AccountingRequestJsonAdapter.repaymentRequestToJson(req)
        val queueItem = SyncQueueEntity(
            id = 1L,
            transactionId = "repay_test_1",
            operationType = SyncQueueEntity.OP_LOG_REPAYMENT,
            payloadJson = payloadJson
        )
        qDao.insert(queueItem)

        val result = executor.executeLogRepayment(
            item = queueItem,
            spreadsheetId = "sheet_2026",
            tabName = "Dec26"
        )

        assertTrue("Execution should succeed by writing to 2027 workbook, got $result", result is Result.Success)

        // Verify written to sheet_2027
        val batchUpdateCall = mockSheets.writtenBodies.find { it.first.contains("sheet_2027") && it.first.contains("batchUpdate") }
        assertNotNull("Must write repayment update to 2027 workbook", batchUpdateCall)
        assertTrue(batchUpdateCall!!.second.contains("Repaid"))

        // Verify planJson was stored on queue item
        val updatedItem = qDao.getById(1L)
        assertNotNull(updatedItem)
        assertNotNull(updatedItem!!.planJson)
        assertTrue(updatedItem.planJson!!.contains("Jan27"))
    }

    @Test
    fun testUnprovableDebtReturnsErrorAndNeverSilentlySucceeds() = runBlocking {
        val qDao = FakeSyncQueueDao()
        val lDao = FakeLineageDao()
        val mockSheets = RepaymentMockSheets()
        val sheetsClient = mockSheets.createClient()
        val driveClient = GoogleDriveClient(OkHttpClient(), mockSheets.tokenProvider)

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheetsClient,
            googleDriveClient = driveClient,
            syncQueueDao = qDao,
            lineageDao = lDao,
            nextWorkbookIdProvider = { year -> if (year == 2027) "sheet_2027" else null }
        )

        // Request for a creditor ("Charlie") who has NO debt in 2026 or 2027
        val req = LogRepaymentRequest(
            requestId = "req_repay_charlie",
            creditor = "Charlie",
            amount = 300.0,
            account = "SBI",
            timestamp = 1798761600000L,
            note = "No open debt"
        )
        val payloadJson = AccountingRequestJsonAdapter.repaymentRequestToJson(req)
        val queueItem = SyncQueueEntity(
            id = 2L,
            transactionId = "repay_test_2",
            operationType = SyncQueueEntity.OP_LOG_REPAYMENT,
            payloadJson = payloadJson
        )
        qDao.insert(queueItem)

        val result = executor.executeLogRepayment(
            item = queueItem,
            spreadsheetId = "sheet_2026",
            tabName = "Dec26"
        )

        assertTrue("Unprovable debt must return Result.Error", result is Result.Error)
        val err = (result as Result.Error).exception
        assertTrue("Error message must explain no open debt found, got: ${err.message}", err.message?.contains("No open debt found for Charlie") == true)

        // Verify nothing was written to any sheet
        val writeCall = mockSheets.writtenBodies.find { it.first.contains("batchUpdate") || it.first.contains("/values/") }
        assertEquals(null, writeCall)
    }

    @Test
    fun testEmptySavedPlanReturnsErrorAndNeverSilentlySucceeds() = runBlocking {
        val qDao = FakeSyncQueueDao()
        val lDao = FakeLineageDao()
        val mockSheets = RepaymentMockSheets()
        val sheetsClient = mockSheets.createClient()
        val driveClient = GoogleDriveClient(OkHttpClient(), mockSheets.tokenProvider)

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheetsClient,
            googleDriveClient = driveClient,
            syncQueueDao = qDao,
            lineageDao = lDao
        )

        val queueItem = SyncQueueEntity(
            id = 3L,
            transactionId = "repay_test_3",
            operationType = SyncQueueEntity.OP_LOG_REPAYMENT,
            payloadJson = "{}",
            planJson = """{"edits":[],"appends":[],"settled":[]}"""
        )
        qDao.insert(queueItem)

        val result = executor.executeLogRepayment(
            item = queueItem,
            spreadsheetId = "sheet_2026",
            tabName = "Dec26"
        )

        assertTrue("Empty saved plan must return Result.Error", result is Result.Error)
        val err = (result as Result.Error).exception
        assertTrue(err.message?.contains("has no edits or appends") == true)
    }
}
