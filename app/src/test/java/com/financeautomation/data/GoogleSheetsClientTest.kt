package com.financeautomation.data

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.DataResult
import com.financeautomation.core.model.Result
import com.financeautomation.data.remote.AuthExpiredException
import com.financeautomation.data.remote.GoogleSheetsApiException
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.MalformedResponseException
import com.financeautomation.data.remote.SheetNotFoundException
import com.financeautomation.data.remote.SheetProvisioner
import com.financeautomation.data.remote.TokenProvider
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class GoogleSheetsClientTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val fakeTokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "test_bearer_token_123"
        override fun refreshAccessToken(): String = "test_refreshed_token_456"
    }

    private fun createMockClient(handler: (request: okhttp3.Request) -> Response): GoogleSheetsClient {
        val interceptor = Interceptor { chain ->
            handler(chain.request())
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        return GoogleSheetsClient(okHttpClient, fakeTokenProvider)
    }

    @Test
    fun testHexToColorMap() {
        val navy = SheetProvisioner.hexToColorMap("#1A237E")
        assertEquals(26 / 255f, navy["red"] ?: 0f, 0.01f)
        assertEquals(35 / 255f, navy["green"] ?: 0f, 0.01f)
        assertEquals(126 / 255f, navy["blue"] ?: 0f, 0.01f)
        assertEquals(1.0f, navy["alpha"] ?: 0f, 0.01f)
    }

    @Test
    fun testBuildProvisionRequestsStructure() {
        val requests = SheetProvisioner.buildProvisionRequests(0, "Nov26")
        assertTrue(requests.isNotEmpty())

        // 1. Verify frozen rows request exists
        val frozenReq = requests.firstOrNull { it.containsKey("updateSheetProperties") }
        assertNotNull(frozenReq)

        // 2. Verify dimension properties (35 total: 2 initial spacer config + 1 UUID hide + 29 column widths + 3 row heights)
        val dimPropsReqs = requests.filter { it.containsKey("updateDimensionProperties") }
        assertEquals(35, dimPropsReqs.size)

        // 3. Verify exactly 4 merges (Row 1 Title Banner + Table 1 Debits + Table 2 Inflows + Table 3 Payables)
        val mergeReqs = requests.filter { it.containsKey("mergeCells") }
        assertEquals(4, mergeReqs.size)

        // 4. Verify Row 3 has 31 column headers
        val updateCellsReq = requests.firstOrNull { it.containsKey("updateCells") }
        assertNotNull(updateCellsReq)
    }

    private fun createFakeSheetServer(
        tabName: String = "Nov26",
        existingDebits: List<List<Any?>> = emptyList(),
        existingInflows: List<List<Any?>> = emptyList(),
        existingPayables: List<List<Any?>> = emptyList(),
        onPut: ((url: String, body: String) -> Unit)? = null
    ): GoogleSheetsClient {
        return createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            if (method == "GET" && url.contains("fields=sheets")) {
                val body = """
                    {
                        "sheets": [
                            {
                                "properties": {
                                    "sheetId": 0,
                                    "title": "$tabName",
                                    "gridProperties": {
                                        "rowCount": 1000
                                    }
                                }
                            }
                        ]
                    }
                """.trimIndent()
                return@createMockClient Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(jsonMediaType))
                    .build()
            }

            if (method == "GET" && url.contains("/values/")) {
                val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
                val values = when {
                    decodedUrl.contains("A4:A") -> existingDebits.map { listOf(it.getOrNull(0) ?: "") }
                    decodedUrl.contains("L3:L3") || decodedUrl.contains("L3%3AL3") -> listOf(listOf("Note"))
                    decodedUrl.contains("AD4:AD") || decodedUrl.contains("AC4:AC") -> existingDebits.map { listOf(it.getOrNull(11) ?: it.getOrNull(10) ?: "") }
                    decodedUrl.contains("N4:N") || decodedUrl.contains("M4:M") -> existingInflows.map { listOf(it.getOrNull(0) ?: "") }
                    decodedUrl.contains("AE4:AE") || decodedUrl.contains("AD4:AD") -> existingInflows.map { listOf(it.getOrNull(7) ?: "") }
                    decodedUrl.contains("W4:W") || decodedUrl.contains("V4:V") -> existingPayables.map { listOf(it.getOrNull(0) ?: "") }
                    decodedUrl.contains("AF4:AF") || decodedUrl.contains("AE4:AE") -> existingPayables.map { listOf(it.getOrNull(6) ?: "") }
                    else -> emptyList<List<Any?>>()
                }

                val jsonValues = values.joinToString(",") { row ->
                    "[" + row.joinToString(",") { "\"$it\"" } + "]"
                }
                val body = """{"range": "$tabName", "majorDimension": "ROWS", "values": [$jsonValues]}"""
                return@createMockClient Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(jsonMediaType))
                    .build()
            }

            if (method == "PUT" && url.contains("/values/")) {
                val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
                val requestBodyStr = request.body?.let { rb ->
                    val buffer = okio.Buffer()
                    rb.writeTo(buffer)
                    buffer.readUtf8()
                }.orEmpty()
                onPut?.invoke(decodedUrl, requestBodyStr)

                val rangeMatch = Regex("values/'?$tabName'?!([^\\?]+)").find(decodedUrl)?.groupValues?.get(1) ?: "A4:L4"
                val body = """
                    {
                        "spreadsheetId": "test_sheet_id",
                        "updatedRange": "$tabName!$rangeMatch",
                        "updatedRows": 1,
                        "updatedColumns": 12,
                        "updatedCells": 12
                    }
                """.trimIndent()
                return@createMockClient Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(jsonMediaType))
                    .build()
            }

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{}""".toResponseBody(jsonMediaType))
                .build()
        }
    }

    @Test
    fun testAppendTransactionSuccess() = runBlocking {
        var capturedPutUrl: String? = null
        val client = createFakeSheetServer(
            tabName = "Nov26",
            existingDebits = emptyList(),
            onPut = { url, _ -> capturedPutUrl = url }
        )

        val row = listOf(46331, 0.5, "SBI", "Swiggy", "", "Food & Drink", 350.0, "Personal", 350.0, 0.0, "Settled", "Dinner note")
        val result = client.appendTransaction("test_sheet_id", "Nov26", row)

        assertTrue(result is Result.Success)
        val rowIndex = (result as Result.Success).data
        assertEquals(4, rowIndex)

        val putUrl = requireNotNull(capturedPutUrl)
        assertTrue("Must use values.update to A4:L4", putUrl.contains("A4:L4"))
        assertTrue("Must use RAW valueInputOption", putUrl.contains("valueInputOption=RAW"))
    }

    @Test
    fun testUpdateRowSuccess() = runBlocking {
        var capturedUrl: String? = null
        var capturedMethod: String? = null

        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            capturedMethod = request.method

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"updatedCells": 11}""".toResponseBody(jsonMediaType))
                .build()
        }

        val row = listOf(46331, 0.5, "SBI", "[Void] Swiggy", "", "Food & Drink", 0.0, "Personal", 0.0, 0.0, "Void")
        val result = client.updateRow("test_sheet_id", "Nov26", 18, row)

        assertTrue(result is Result.Success)
        assertEquals("PUT", capturedMethod)
        val url = requireNotNull(capturedUrl)
        assertTrue(url.contains("valueInputOption=RAW"))
        assertTrue(url.contains("A18%3AK18") || url.contains("A18:K18"))
    }

    @Test
    fun testRenamedCategoryAndAccountRowUpdatePayload() = runBlocking {
        var capturedUrl: String? = null
        var capturedBody: String? = null

        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            capturedBody = buffer.readUtf8()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"updatedCells": 11}""".toResponseBody(jsonMediaType))
                .build()
        }

        // Row 15: Synced transaction with renamed account "HDFC Salary" and renamed category "Groceries & Supermarket"
        val row = listOf(
            46331, 0.5, "HDFC Salary", "Supermarket", "", "Groceries & Supermarket",
            1250.0, "Personal", 1250.0, 0.0, "Settled"
        )
        val result = client.updateRow("test_sheet_id", "Nov26", 15, row)

        assertTrue(result is Result.Success<*>)
        val url = requireNotNull(capturedUrl)
        assertTrue("URL must target range A15:K15", url.contains("A15%3AK15") || url.contains("A15:K15"))
        val body = requireNotNull(capturedBody)
        assertTrue("Body must include renamed account", body.contains("HDFC Salary"))
        assertTrue("Body must include renamed category", body.contains("Groceries & Supermarket"))
    }

    @Test
    fun testAppendTableScopedRoutingDebits() = runBlocking {
        var capturedPutUrl: String? = null
        // 4 existing debits: rows 4, 5, 6, 7 occupied
        val existing = listOf(
            listOf(46330, "d1"),
            listOf(46331, "d2"),
            listOf(46332, "d3"),
            listOf(46333, "d4")
        )
        val client = createFakeSheetServer(
            tabName = "Nov26",
            existingDebits = existing,
            onPut = { url, _ -> capturedPutUrl = url }
        )

        val debitRow = listOf(46334, 0.5, "SBI", "Lunch", "", "Food & Drink", 200.0, "Personal", 200.0, 0.0, "Settled")
        val result = client.appendDebit("test_sheet_id", "Nov26", debitRow)

        assertTrue(result is Result.Success)
        assertEquals(8, (result as Result.Success).data)
        val putUrl = requireNotNull(capturedPutUrl)
        assertTrue("Next debit must target A8:L8", putUrl.contains("A8:L8"))
    }

    @Test
    fun testAppendTableScopedRoutingInflows() = runBlocking {
        var capturedPutUrl: String? = null
        val client = createFakeSheetServer(
            tabName = "Nov26",
            existingInflows = emptyList(),
            onPut = { url, _ -> capturedPutUrl = url }
        )

        val inflowRow = listOf(46331, 0.5, "SBI", "Friend Repayment", "Alice", "Dinner share", 150.0, "txn_123")
        val result = client.appendInflow("test_sheet_id", "Nov26", inflowRow)

        assertTrue(result is Result.Success)
        assertEquals(4, (result as Result.Success).data)
        val putUrl = requireNotNull(capturedPutUrl)
        assertTrue("Inflow must target N4:U4", putUrl.contains("N4:U4"))
    }

    @Test
    fun testAppendTableScopedRoutingPayables() = runBlocking {
        var capturedPutUrl: String? = null
        val client = createFakeSheetServer(
            tabName = "Nov26",
            existingPayables = emptyList(),
            onPut = { url, _ -> capturedPutUrl = url }
        )

        val payableRow = listOf(46331, 0.5, "Bob", "Movie ticket", 250.0, "", "Pending")
        val result = client.appendPayable("test_sheet_id", "Nov26", payableRow)

        assertTrue(result is Result.Success)
        assertEquals(4, (result as Result.Success).data)
        val putUrl = requireNotNull(capturedPutUrl)
        assertTrue("Payable must target W4:AC4", putUrl.contains("W4:AC4"))
    }

    @Test
    fun testBug003EmptyInflowsAndPayablesDoNotFallBackToColumnA() = runBlocking {
        var capturedPutUrl: String? = null
        // Debits table has 4 rows (rows 4..7). Inflows (N:U) and Payables (W:AC) are EMPTY.
        val existingDebits = listOf(
            listOf(46330, "Breakfast"),
            listOf(46331, "Dinner"),
            listOf(46332, "Groceries"),
            listOf(46333, "Lunch")
        )
        val client = createFakeSheetServer(
            tabName = "Nov26",
            existingDebits = existingDebits,
            existingInflows = emptyList(),
            existingPayables = emptyList(),
            onPut = { url, _ -> capturedPutUrl = url }
        )

        // 1. Inflow append on empty Inflow table must write strictly to N4:U4, NEVER A8:H8
        val inflowRow = listOf(46334, 0.5, "SBI", "Friend Repayment", "Charlie", "Settlement", 75.0, "txn_dinner")
        val inflowRes = client.appendInflow("test_sheet_id", "Nov26", inflowRow)
        assertTrue(inflowRes is Result.Success)
        assertEquals(4, (inflowRes as Result.Success).data)
        val inflowUrl = requireNotNull(capturedPutUrl)
        assertTrue("Inflow must write to N4:U4", inflowUrl.contains("N4:U4"))
        assertTrue("Inflow must NEVER write to Column A", !inflowUrl.contains("A4:L4") && !inflowUrl.contains("A8:L8") && !inflowUrl.contains("A8:H8"))

        // 2. Payable append on empty Payable table must write strictly to W4:AC4, NEVER A8:G8
        capturedPutUrl = null
        val payableRow = listOf(46334, 0.5, "Charlie", "Cab fare", 100.0, "", "Pending")
        val payableRes = client.appendPayable("test_sheet_id", "Nov26", payableRow)
        assertTrue(payableRes is Result.Success)
        assertEquals(4, (payableRes as Result.Success).data)
        val payableUrl = requireNotNull(capturedPutUrl)
        assertTrue("Payable must write to W4:AC4", payableUrl.contains("W4:AC4"))
        assertTrue("Payable must NEVER write to Column A", !payableUrl.contains("A4:L4") && !payableUrl.contains("A8:L8") && !payableUrl.contains("A8:G8"))

        // 3. Debit append must write to A8:L8 (next free debit row after 4 existing rows)
        capturedPutUrl = null
        val debitRow = listOf(46334, 0.5, "SBI", "Coffee", "", "Food & Drink", 50.0, "Personal", 50.0, 0.0, "Settled", "Coffee note")
        val debitRes = client.appendDebit("test_sheet_id", "Nov26", debitRow)
        assertTrue(debitRes is Result.Success)
        assertEquals(8, (debitRes as Result.Success).data)
        val debitUrl = requireNotNull(capturedPutUrl)
        assertTrue("Debit must write to A8:L8", debitUrl.contains("A8:L8"))
    }

    @Test
    fun testNeverWritesAcrossSpacerColumnsFor32ColumnArray() = runBlocking {
        var capturedPutUrl: String? = null
        val client = createFakeSheetServer(
            tabName = "Nov26",
            existingDebits = emptyList(),
            onPut = { url, _ -> capturedPutUrl = url }
        )

        // 32-column row with Table 1 debit values and spacers
        val row32 = MutableList<Any?>(32) { "" }
        row32[0] = 46331
        row32[1] = 0.5
        row32[2] = "SBI"
        row32[3] = "Groceries"
        row32[6] = 500.0
        row32[11] = "Groceries note"
        row32[29] = "txn_debit_uuid_123"

        val result = client.appendTransaction("test_sheet_id", "Nov26", row32)

        assertTrue(result is Result.Success)
        val putUrl = requireNotNull(capturedPutUrl)
        assertTrue("Must target A4:L4", putUrl.contains("A4:L4"))
        assertTrue("Must NEVER target A3:AF", !putUrl.contains("A3:AF") && !putUrl.contains("A4:AF4"))
    }

    @Test
    fun testUpdateLinkedDebitSettlementDecrementAndStatus() = runBlocking {
        var capturedUrl: String? = null
        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"updatedCells": 2}""".toResponseBody(jsonMediaType))
                .build()
        }

        // Full settlement: 200 owed - 200 paid = 0.0 remaining -> "Settled"
        val fullResult = client.updateLinkedDebitSettlement(
            spreadsheetId = "test_sheet_id",
            tabName = "Nov26",
            rowIndex = 15,
            paidAmount = 200.0,
            currentOwed = 200.0
        )
        assertTrue(fullResult is Result.Success)
        assertEquals(0.0, (fullResult as Result.Success).data, 0.001)

        val url = requireNotNull(capturedUrl)
        assertTrue(url.contains("J15%3AK15") || url.contains("J15:K15"))

        // Partial settlement: 300 owed - 100 paid = 200.0 remaining -> "Pending"
        val partialResult = client.updateLinkedDebitSettlement(
            spreadsheetId = "test_sheet_id",
            tabName = "Nov26",
            rowIndex = 16,
            paidAmount = 100.0,
            currentOwed = 300.0
        )
        assertTrue(partialResult is Result.Success)
        assertEquals(200.0, (partialResult as Result.Success).data, 0.001)
    }

    @Test
    fun testNegativeHttp401AuthExpiredReturnsError() = runBlocking {
        val client = createMockClient { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"error": {"code": 401, "message": "Invalid Credentials"}}""".toResponseBody(jsonMediaType))
                .build()
        }

        val row = listOf(46331, 0.5, "SBI", "Tea", "", "Food & Drink", 20.0, "Personal", 20.0, 0.0, "Settled")
        val result = client.appendTransaction("test_sheet_id", "Nov26", row)

        assertTrue(result is Result.Error)
        val error = result as Result.Error
        assertTrue(error.exception is AuthExpiredException)

        val dataResult = client.appendTransactionDataResult("test_sheet_id", "Nov26", row)
        assertTrue(dataResult is DataResult.AuthExpired)
    }

    @Test
    fun testNegativeHttp404SheetNotFoundReturnsError() = runBlocking {
        val client = createMockClient { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("""{"error": {"code": 404, "message": "Spreadsheet not found"}}""".toResponseBody(jsonMediaType))
                .build()
        }

        val result = client.getSheetMetadata("invalid_sheet_id")

        assertTrue(result is Result.Error)
        val error = result as Result.Error
        assertTrue(error.exception is SheetNotFoundException)

        val dataResult = client.getSheetMetadataDataResult("invalid_sheet_id")
        assertTrue(dataResult is DataResult.SheetNotFound)
        assertEquals("invalid_sheet_id", (dataResult as DataResult.SheetNotFound).spreadsheetId)
    }

    @Test
    fun testNegativeHttp500ServerErrorReturnsError() = runBlocking {
        val client = createMockClient { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("""{"error": {"code": 500, "message": "Backend failure"}}""".toResponseBody(jsonMediaType))
                .build()
        }

        val row = listOf(46331, 0.5, "SBI", "Fuel", "", "Transport", 500.0, "Personal", 500.0, 0.0, "Settled")
        val result = client.appendTransaction("test_sheet_id", "Nov26", row)

        assertTrue(result is Result.Error)
        val error = result as Result.Error
        assertTrue(error.exception is GoogleSheetsApiException)
        assertEquals(500, (error.exception as GoogleSheetsApiException).statusCode)

        val dataResult = client.appendTransactionDataResult("test_sheet_id", "Nov26", row)
        assertTrue(dataResult is DataResult.General)
    }

    @Test
    fun testNegativeSocketTimeoutReturnsNetworkError() = runBlocking {
        val client = createMockClient {
            throw SocketTimeoutException("Connection timed out after 10000ms")
        }

        val result = client.getSheetMetadata("test_sheet_id")

        assertTrue(result is Result.Error)
        val error = result as Result.Error
        assertTrue(error.exception is SocketTimeoutException)

        val dataResult = client.getSheetMetadataDataResult("test_sheet_id")
        assertTrue(dataResult is DataResult.Network)
    }

    @Test
    fun testNegativeMalformedJsonResponseReturnsError() = runBlocking {
        val client = createMockClient { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{ malformed json without closing brace""".toResponseBody(jsonMediaType))
                .build()
        }

        val result = client.getSheetMetadata("test_sheet_id")

        assertTrue(result is Result.Error)
        val error = result as Result.Error
        assertTrue(error.exception is MalformedResponseException)

        val dataResult = client.getSheetMetadataDataResult("test_sheet_id")
        assertTrue(dataResult is DataResult.Malformed)
    }

    @Test
    fun testZeroFakeSuccessOnUnparseableRange() = runBlocking {
        val client = createMockClient { request ->
            val responseBody = """{"updates": {"updatedRange": ""}}"""
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody(jsonMediaType))
                .build()
        }

        val row = listOf(46331, 0.5, "SBI", "Coffee", "", "Food & Drink", 100.0, "Personal", 100.0, 0.0, "Settled")
        val result = client.appendTransaction("test_sheet_id", "Nov26", row)

        // Strict invariant: Zero Fake Successes. Must return explicit Result.Error.
        assertTrue("Must not return fake success on unparseable range", result is Result.Error)
    }

    @Test
    fun testGetSheetMetadataSuccess() = runBlocking {
        val client = createMockClient { request ->
            val responseBody = """
                {
                    "spreadsheetId": "test_sheet_id",
                    "sheets": [
                        { "properties": { "sheetId": 0, "title": "Nov26" } },
                        { "properties": { "sheetId": 1, "title": "Dec26" } }
                    ]
                }
            """.trimIndent()

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody(jsonMediaType))
                .build()
        }

        val result = client.getSheetMetadata("test_sheet_id")
        assertTrue(result is Result.Success)
        val titles = (result as Result.Success).data
        assertEquals(listOf("Nov26", "Dec26"), titles)
    }

    @Test
    fun testBatchUpdateSuccess() = runBlocking {
        var capturedMethod: String? = null

        val client = createMockClient { request ->
            capturedMethod = request.method

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"replies": []}""".toResponseBody(jsonMediaType))
                .build()
        }

        val requests = SheetProvisioner.buildProvisionRequests(0, "Nov26")
        val result = client.batchUpdate("test_sheet_id", requests)

        assertTrue(result is Result.Success)
        assertEquals("POST", capturedMethod)
    }

    @Test
    fun testProvisionMasterWorkbookLocaleBatchFailureResilience() = runBlocking {
        var localeBatchCalled = false
        var tabSetupBatchCalled = false

        val client = createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            when {
                url.contains("fields=sheets.properties") && method == "GET" -> {
                    // Initial getSheetTabsWithIds call
                    val body = """
                        {
                          "sheets": [
                            {
                              "properties": {
                                "sheetId": 0,
                                "title": "Sheet1"
                              }
                            }
                          ]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }
                url.contains(":batchUpdate") && method == "POST" -> {
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    val bodyString = buffer.readUtf8()

                    if (bodyString.contains("updateSpreadsheetProperties")) {
                        // Cosmetic locale batch: simulate failure
                        localeBatchCalled = true
                        val errorJson = """
                            {
                              "error": {
                                "code": 400,
                                "message": "Invalid locale",
                                "status": "INVALID_ARGUMENT"
                              }
                            }
                        """.trimIndent()
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(400)
                            .message("Bad Request")
                            .body(errorJson.toResponseBody(jsonMediaType))
                            .build()
                    } else {
                        // Structural tab batch or formatting batch
                        if (bodyString.contains("addSheet")) {
                            tabSetupBatchCalled = true
                        }
                        val successJson = """
                            {
                              "replies": [
                                {
                                  "addSheet": {
                                    "properties": {
                                      "sheetId": 102,
                                      "title": "Nov26"
                                    }
                                  }
                                }
                              ]
                            }
                        """.trimIndent()
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(successJson.toResponseBody(jsonMediaType))
                            .build()
                    }
                }
                else -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"replies": []}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }
        }

        val result = client.provisionMasterWorkbook("test_sheet_id")
        assertTrue("Expected provisioning to succeed even when locale batch fails", result is Result.Success)
        assertTrue("Locale batch should have been attempted", localeBatchCalled)
        assertTrue("Tab setup batch should have run", tabSetupBatchCalled)
    }

    @Test
    fun testMonthTabSchemaRepairAndFullDataFlow() = runBlocking {
        var isRepaired = false
        val executedBatchBodies = mutableListOf<String>()
        val putCalls = mutableListOf<Pair<String, String>>()

        val client = createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            when {
                // Get spreadsheet metadata
                method == "GET" && url.contains("/test_sheet_id?") -> {
                    val body = """{
                        "spreadsheetId": "test_sheet_id",
                        "sheets": [
                            {"properties": {"sheetId": 0, "title": "Dashboard", "gridProperties": {"rowCount": 1000, "columnCount": 32}}},
                            {"properties": {"sheetId": 1234, "title": "Nov26", "gridProperties": {"rowCount": 1000, "columnCount": 32}}}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                // Check L3
                method == "GET" && !url.contains("batchGet") && (url.contains("L3%3AL3") || url.contains("L3:L3")) -> {
                    val body = if (isRepaired) {
                        """{"range": "'Nov26'!L3:L3", "majorDimension": "ROWS", "values": [["Note"]]}"""
                    } else {
                        """{"range": "'Nov26'!L3:L3", "majorDimension": "ROWS", "values": [["Spacer 1"]]}"""
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                // Post repair verification read back: L3:AF3
                method == "GET" && !url.contains("batchGet") && (url.contains("L3%3AAF3") || url.contains("L3:AF3")) -> {
                    val body = """{"range": "'Nov26'!L3:AF3", "majorDimension": "ROWS", "values": [["Note", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "Debit Txn UUID", "Inflow Txn UUID", "Payable Txn UUID"]]}"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                // batchUpdate
                method == "POST" && url.contains(":batchUpdate") -> {
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    val bodyStr = buffer.readUtf8()
                    executedBatchBodies.add(bodyStr)
                    isRepaired = true
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"replies": []}""".toResponseBody(jsonMediaType))
                        .build()
                }

                // values:batchGet for readLedgerSnapshot
                method == "GET" && url.contains("values:batchGet") -> {
                    val body = """{
                        "valueRanges": [
                            {"range": "'Nov26'!L3:L3", "majorDimension": "ROWS", "values": [["Note"]]},
                            {"range": "'Nov26'!A4:L", "majorDimension": "ROWS", "values": [[46331, 0.5, "SBI", "Dinner", "", "Food & Drink", 500.0, "Personal", 500.0, 0.0, "Settled", "Dinner with team"]]},
                            {"range": "'Nov26'!AD4:AD", "majorDimension": "ROWS", "values": [["txn_debit_123"]]},
                            {"range": "'Nov26'!N4:U", "majorDimension": "ROWS", "values": [[46331, 0.5, "SBI", "Salary", "Employer", "Monthly Salary", 100000.0, ""]]},
                            {"range": "'Nov26'!AE4:AE", "majorDimension": "ROWS", "values": [["inf_salary_456"]]},
                            {"range": "'Nov26'!W4:AC", "majorDimension": "ROWS", "values": [[46331, 0.6, "Alice", "Lunch share", 250.0, "", "Pending"]]},
                            {"range": "'Nov26'!AF4:AF", "majorDimension": "ROWS", "values": [["pay_lunch_789"]]}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                // Single value get for free row scan
                method == "GET" && url.contains("/values/") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"range": "'Nov26'!A4:A", "majorDimension": "ROWS", "values": []}""".toResponseBody(jsonMediaType))
                        .build()
                }

                // PUT to write rows
                method == "PUT" && url.contains("/values/") -> {
                    val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    val bodyStr = buffer.readUtf8()
                    putCalls.add(decodedUrl to bodyStr)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"updatedCells": 1}""".toResponseBody(jsonMediaType))
                        .build()
                }

                else -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }
        }

        // 1. First repair: Tab needs repair -> runs batchUpdate with insertDimension and succeeds
        val repairRes = client.repairMonthTabSchema("test_sheet_id", "Nov26")
        assertTrue(repairRes is Result.Success)
        assertEquals(true, (repairRes as Result.Success).data)
        assertEquals(1, executedBatchBodies.size)
        assertTrue("Must include insertDimension at 11", executedBatchBodies.first().contains("\"startIndex\":11"))
        assertTrue("Must include Note header", executedBatchBodies.first().contains("\"stringValue\":\"Note\""))

        // 2. Second repair: Tab already repaired -> skips batchUpdate
        val secondRepair = client.repairMonthTabSchema("test_sheet_id", "Nov26")
        assertTrue(secondRepair is Result.Success)
        assertEquals(false, (secondRepair as Result.Success).data)
        assertEquals("Should not run batchUpdate again", 1, executedBatchBodies.size)

        // 3. Write debit with note, inflow, and payable
        val debitRow = listOf(46331, 0.5, "SBI", "Dinner", "", "Food & Drink", 500.0, "Personal", 500.0, 0.0, "Settled", "Dinner with team")
        client.appendDebit("test_sheet_id", "Nov26", debitRow)
        val inflowRow = listOf(46331, 0.5, "SBI", "Salary", "Employer", "Monthly Salary", 100000.0, "")
        client.appendInflow("test_sheet_id", "Nov26", inflowRow)
        val payableRow = listOf(46331, 0.6, "Alice", "Lunch share", 250.0, "", "Pending")
        client.appendPayable("test_sheet_id", "Nov26", payableRow)

        // Check target ranges
        assertTrue("Debit must write to A4:L4", putCalls.any { it.first.contains("A4:L4") && it.second.contains("Dinner with team") })
        assertTrue("Inflow must write to N4:U4", putCalls.any { it.first.contains("N4:U4") && it.second.contains("Monthly Salary") })
        assertTrue("Payable must write to W4:AC4", putCalls.any { it.first.contains("W4:AC4") && it.second.contains("Lunch share") })

        // 4. readLedgerSnapshot restores rows with full fidelity including note
        val snapshotRes = client.readLedgerSnapshot("test_sheet_id")
        assertTrue(snapshotRes is Result.Success)
        val snapshot = (snapshotRes as Result.Success).data
        val novTab = snapshot.monthTabs.first { it.tabName == "Nov26" }
        assertEquals(1, novTab.debits.size)
        assertEquals("Dinner with team", novTab.debits.first().note)
        assertEquals("txn_debit_123", novTab.debits.first().uuid)
        assertEquals(1, novTab.inflows.size)
        assertEquals("inf_salary_456", novTab.inflows.first().uuid)
        assertEquals(1, novTab.payables.size)
        assertEquals("pay_lunch_789", novTab.payables.first().uuid)
    }

    @Test
    fun testNotesStartingWithFormulaCharactersWrittenAsPlainRawWithoutApostrophe() = runBlocking {
        val testNotes = listOf(
            """=HYPERLINK("x","y")""",
            "+1",
            "-1",
            "@a"
        )

        for ((idx, note) in testNotes.withIndex()) {
            var capturedPutUrl: String? = null
            var capturedBody: String? = null

            val client = createFakeSheetServer(
                tabName = "Nov26",
                existingDebits = emptyList(),
                onPut = { url, body ->
                    capturedPutUrl = url
                    capturedBody = body
                }
            )

            // Table 1 Debit row with 12 items: Note at index 11 (Column L)
            val debitRow = listOf(
                46331, 0.5, "SBI", "Item $idx", "", "Food & Drink", 100.0, "Personal", 100.0, 0.0, "Settled", note
            )
            val res = client.appendDebit("test_sheet_id", "Nov26", debitRow)

            assertTrue("appendDebit should succeed for note: $note", res is Result.Success)
            val putUrl = requireNotNull(capturedPutUrl)
            assertTrue("URL must enforce valueInputOption=RAW", putUrl.contains("valueInputOption=RAW"))
            assertTrue("Target must be Table 1 A4:L4", putUrl.contains("A4:L4"))

            val body = requireNotNull(capturedBody)
            val expectedInJson = note.replace("\"", "\\\"")
            // Assert that the note is written verbatim as plain text in JSON
            assertTrue("Body must contain exact note in JSON", body.contains(expectedInJson))
            // Assert that no leading apostrophe was prepended to the note
            assertFalse("Body must NOT contain apostrophe prefixed note", body.contains("'$note") || body.contains("'="))
        }
    }

    @Test
    fun testNotesStartingWithFormulaCharactersRestoredUnchangedFromSheet() = runBlocking {
        val notes = listOf(
            """=HYPERLINK("x","y")""",
            "+1",
            "-1",
            "@a"
        )

        val client = createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            when {
                // Get spreadsheet metadata
                method == "GET" && url.contains("/test_sheet_id?") -> {
                    val body = """{
                        "spreadsheetId": "test_sheet_id",
                        "sheets": [
                            {"properties": {"sheetId": 1234, "title": "Nov26", "gridProperties": {"rowCount": 1000, "columnCount": 32}}}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                // values:batchGet for readLedgerSnapshot
                method == "GET" && url.contains("values:batchGet") -> {
                    val debitsRows = notes.mapIndexed { i, n ->
                        val escaped = n.replace("\"", "\\\"")
                        """[46331, 0.5, "SBI", "Item $i", "", "Food & Drink", 100.0, "Personal", 100.0, 0.0, "Settled", "$escaped"]"""
                    }.joinToString(",")

                    val uuidRows = notes.mapIndexed { i, _ ->
                        """["txn_$i"]"""
                    }.joinToString(",")

                    val body = """{
                        "valueRanges": [
                            {"range": "'Nov26'!A4:L", "majorDimension": "ROWS", "values": [$debitsRows]},
                            {"range": "'Nov26'!AD4:AD", "majorDimension": "ROWS", "values": [$uuidRows]},
                            {"range": "'Nov26'!N4:U", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!AE4:AE", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!W4:AC", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!AF4:AF", "majorDimension": "ROWS", "values": []}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                else -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }
        }

        val snapshotRes = client.readLedgerSnapshot("test_sheet_id")
        assertTrue(snapshotRes is Result.Success)
        val snapshot = (snapshotRes as Result.Success).data
        val novTab = snapshot.monthTabs.first { it.tabName == "Nov26" }
        assertEquals(4, novTab.debits.size)

        for ((idx, expectedNote) in notes.withIndex()) {
            assertEquals("Note at index $idx must match exact plain text", expectedNote, novTab.debits[idx].note)
        }
    }

    @Test
    fun testReadLedgerSnapshot_31WideOldRow_parsesWithNoteEmpty() = runBlocking {
        val client = createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            when {
                method == "GET" && url.contains("/test_sheet_id?") -> {
                    val body = """{
                        "spreadsheetId": "test_sheet_id",
                        "sheets": [
                            {"properties": {"sheetId": 1234, "title": "Nov26", "gridProperties": {"rowCount": 1000, "columnCount": 31}}}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }
                method == "GET" && url.contains("values:batchGet") -> {
                    // Legacy 31-wide: L3 is "Spacer 1", A4:K has 11 columns, AC4:AC has uuid
                    val body = """{
                        "valueRanges": [
                            {"range": "'Nov26'!L3:L3", "majorDimension": "ROWS", "values": [["Spacer 1"]]},
                            {"range": "'Nov26'!A4:L", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!A4:K", "majorDimension": "ROWS", "values": [[46331, 0.5, "SBI", "Old Coffee", "", "Food & Drink", 150.0, "Personal", 150.0, 0.0, "Settled"]]},
                            {"range": "'Nov26'!AD4:AD", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!AC4:AC", "majorDimension": "ROWS", "values": [["txn_legacy_31"]]},
                            {"range": "'Nov26'!N4:U", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!AE4:AE", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!W4:AC", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!AF4:AF", "majorDimension": "ROWS", "values": []}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }
                else -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }
        }

        val snapshotRes = client.readLedgerSnapshot("test_sheet_id")
        assertTrue(snapshotRes is Result.Success)
        val snapshot = (snapshotRes as Result.Success).data
        val novTab = snapshot.monthTabs.first { it.tabName == "Nov26" }
        assertEquals(1, novTab.debits.size)
        assertEquals("Old Coffee", novTab.debits.first().description)
        assertEquals("", novTab.debits.first().note) // Note is empty string for 31-wide row
        assertEquals("txn_legacy_31", novTab.debits.first().uuid)
    }

    @Test
    fun testReadLedgerSnapshot_32WideRow_parsesWithNote() = runBlocking {
        val client = createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            when {
                method == "GET" && url.contains("/test_sheet_id?") -> {
                    val body = """{
                        "spreadsheetId": "test_sheet_id",
                        "sheets": [
                            {"properties": {"sheetId": 1234, "title": "Nov26", "gridProperties": {"rowCount": 1000, "columnCount": 32}}}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }
                method == "GET" && url.contains("values:batchGet") -> {
                    // Modern 32-wide: L3 is "Note", A4:L has 12 columns with Note, AD4:AD has uuid
                    val body = """{
                        "valueRanges": [
                            {"range": "'Nov26'!L3:L3", "majorDimension": "ROWS", "values": [["Note"]]},
                            {"range": "'Nov26'!A4:L", "majorDimension": "ROWS", "values": [[46331, 0.5, "SBI", "New Coffee", "", "Food & Drink", 180.0, "Personal", 180.0, 0.0, "Settled", "Cappuccino with oat milk"]]},
                            {"range": "'Nov26'!AD4:AD", "majorDimension": "ROWS", "values": [["txn_modern_32"]]},
                            {"range": "'Nov26'!N4:U", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!AE4:AE", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!W4:AC", "majorDimension": "ROWS", "values": []},
                            {"range": "'Nov26'!AF4:AF", "majorDimension": "ROWS", "values": []}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }
                else -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }
        }

        val snapshotRes = client.readLedgerSnapshot("test_sheet_id")
        assertTrue(snapshotRes is Result.Success)
        val snapshot = (snapshotRes as Result.Success).data
        val novTab = snapshot.monthTabs.first { it.tabName == "Nov26" }
        assertEquals(1, novTab.debits.size)
        assertEquals("New Coffee", novTab.debits.first().description)
        assertEquals("Cappuccino with oat milk", novTab.debits.first().note)
        assertEquals("txn_modern_32", novTab.debits.first().uuid)
    }

    @Test
    fun testProductionChain_old31ColumnWorkbook_syncWritesExpenseAndRepairsTabExactlyOnce() = runBlocking {
        var isRepaired = false
        val executedBatchBodies = mutableListOf<String>()
        val putCalls = mutableListOf<Pair<String, String>>()

        val client = createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            when {
                // Spreadsheet metadata
                method == "GET" && url.contains("/test_sheet_id?") -> {
                    val body = """{
                        "spreadsheetId": "test_sheet_id",
                        "sheets": [
                            {"properties": {"sheetId": 1234, "title": "Nov26", "gridProperties": {"rowCount": 1000, "columnCount": 31}}}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                // Check L3
                method == "GET" && (url.contains("L3%3AL3") || url.contains("L3:L3")) -> {
                    val headerVal = if (isRepaired) "Note" else "Spacer 1"
                    val body = """{"range": "'Nov26'!L3:L3", "majorDimension": "ROWS", "values": [["$headerVal"]]}"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                // Verification read back L3:AF3
                method == "GET" && (url.contains("L3%3AAF3") || url.contains("L3:AF3")) -> {
                    val body = """{"range": "'Nov26'!L3:AF3", "majorDimension": "ROWS", "values": [["Note", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "Debit Txn UUID", "Inflow Txn UUID", "Payable Txn UUID"]]}"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }

                // batchUpdate (repair)
                method == "POST" && url.contains(":batchUpdate") -> {
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    val bodyStr = buffer.readUtf8()
                    executedBatchBodies.add(bodyStr)
                    isRepaired = true
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"replies": []}""".toResponseBody(jsonMediaType))
                        .build()
                }

                // Append value puts
                method == "PUT" && url.contains("/values/") -> {
                    val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    putCalls.add(decodedUrl to buffer.readUtf8())
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"updatedCells": 1}""".toResponseBody(jsonMediaType))
                        .build()
                }

                // Single value get for free row scan
                method == "GET" && url.contains("/values/") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"range": "'Nov26'!A4:A", "majorDimension": "ROWS", "values": []}""".toResponseBody(jsonMediaType))
                        .build()
                }

                else -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }
        }

        // 1. Production call chain: appendDebit is called on un-repaired 31-column month tab
        val debit1 = listOf(46331, 0.5, "SBI", "Dinner", "", "Food & Drink", 500.0, "Personal", 500.0, 0.0, "Settled", "Dinner note")
        val res1 = client.appendDebit("test_sheet_id", "Nov26", debit1)
        assertTrue("First append must succeed", res1 is Result.Success)
        assertEquals("Must execute repair batchUpdate exactly once", 1, executedBatchBodies.size)
        assertTrue(executedBatchBodies.first().contains("\"startIndex\":11"))
        assertTrue("Row must land in 32-column range A4:L4", putCalls.any { it.first.contains("A4:L4") && it.second.contains("Dinner note") })

        // 2. Second append on the same tab in the session: should not execute repair batchUpdate again!
        val debit2 = listOf(46332, 0.6, "SBI", "Coffee", "", "Food & Drink", 150.0, "Personal", 150.0, 0.0, "Settled", "Second note")
        val res2 = client.appendDebit("test_sheet_id", "Nov26", debit2)
        assertTrue("Second append must succeed", res2 is Result.Success)
        assertEquals("Must NOT execute repair batchUpdate a second time", 1, executedBatchBodies.size)
    }

    @Test
    fun testRepairMonthTabSchema_onFailedOrEmptyRead_stopsAndReportsErrorWithoutInsertingDimension() = runBlocking {
        var is500Error = true
        var batchUpdateCalled = false

        val client = createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            when {
                method == "GET" && (url.contains("L3%3AL3") || url.contains("L3:L3")) -> {
                    if (is500Error) {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Internal Server Error")
                            .body("""{"error": "transient"}""".toResponseBody(jsonMediaType))
                            .build()
                    } else {
                        // Empty read
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"values": []}""".toResponseBody(jsonMediaType))
                            .build()
                    }
                }
                method == "POST" && url.contains(":batchUpdate") -> {
                    batchUpdateCalled = true
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"replies": []}""".toResponseBody(jsonMediaType))
                        .build()
                }
                else -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }
        }

        // Test 1: HTTP 500 error on L3 read
        val resError = client.repairMonthTabSchema("test_sheet_id", "Nov26")
        assertTrue("Must report error on failed L3 read", resError is Result.Error)
        assertTrue("Must NOT call batchUpdate or insertDimension on read error", !batchUpdateCalled)

        // Test 2: Empty read on L3
        is500Error = false
        val resEmpty = client.repairMonthTabSchema("test_sheet_id", "Nov26")
        assertTrue("Must report error on empty L3 read", resEmpty is Result.Error)
        assertTrue("Must NOT call batchUpdate or insertDimension on empty read", !batchUpdateCalled)
    }

    @Test
    fun testReadLedgerSnapshot_reads31ColumnTabNextTo32ColumnTab() = runBlocking {
        val client = createMockClient { request ->
            val url = request.url.toString()
            val method = request.method

            when {
                method == "GET" && url.contains("/test_sheet_id?") -> {
                    val body = """{
                        "spreadsheetId": "test_sheet_id",
                        "sheets": [
                            {"properties": {"sheetId": 101, "title": "Oct26", "gridProperties": {"rowCount": 1000, "columnCount": 31}}},
                            {"properties": {"sheetId": 102, "title": "Nov26", "gridProperties": {"rowCount": 1000, "columnCount": 32}}}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }
                method == "GET" && url.contains("values:batchGet") -> {
                    val body = """{
                        "valueRanges": [
                            {"range": "'Oct26'!L3:L3", "majorDimension": "ROWS", "values": [["Spacer 1"]]},
                            {"range": "'Oct26'!A4:K", "majorDimension": "ROWS", "values": [[46300, 0.5, "HDFC", "Oct Fuel", "", "Fuel", 2000.0, "Personal", 2000.0, 0.0, "Settled"]]},
                            {"range": "'Oct26'!AC4:AC", "majorDimension": "ROWS", "values": [["txn_oct_legacy_31"]]},
                            {"range": "'Oct26'!M4:T", "majorDimension": "ROWS", "values": [[46300, 0.5, "HDFC", "Salary", "Company", "Oct Salary", 80000.0, ""]]},
                            {"range": "'Oct26'!AD4:AD", "majorDimension": "ROWS", "values": [["inf_oct_legacy_31"]]},
                            {"range": "'Oct26'!V4:AB", "majorDimension": "ROWS", "values": [[46300, 0.5, "Alice", "Oct Stay", 500.0, "", "Pending"]]},
                            {"range": "'Oct26'!AE4:AE", "majorDimension": "ROWS", "values": [["pay_oct_legacy_31"]]},
                            {"range": "'Nov26'!L3:L3", "majorDimension": "ROWS", "values": [["Note"]]},
                            {"range": "'Nov26'!A4:L", "majorDimension": "ROWS", "values": [[46331, 0.5, "HDFC", "Nov Dinner", "", "Food", 1000.0, "Personal", 1000.0, 0.0, "Settled", "Team Dinner"]]},
                            {"range": "'Nov26'!AD4:AD", "majorDimension": "ROWS", "values": [["txn_nov_modern_32"]]},
                            {"range": "'Nov26'!N4:U", "majorDimension": "ROWS", "values": [[46331, 0.5, "HDFC", "Salary", "Company", "Nov Salary", 85000.0, ""]]},
                            {"range": "'Nov26'!AE4:AE", "majorDimension": "ROWS", "values": [["inf_nov_modern_32"]]},
                            {"range": "'Nov26'!W4:AC", "majorDimension": "ROWS", "values": [[46331, 0.5, "Bob", "Nov Grocery", 300.0, "", "Pending"]]},
                            {"range": "'Nov26'!AF4:AF", "majorDimension": "ROWS", "values": [["pay_nov_modern_32"]]}
                        ]
                    }"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(jsonMediaType))
                        .build()
                }
                else -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }
        }

        val snapshotRes = client.readLedgerSnapshot("test_sheet_id")
        assertTrue(snapshotRes is Result.Success)
        val snapshot = (snapshotRes as Result.Success).data
        assertEquals(2, snapshot.monthTabs.size)

        // 31-column legacy tab Oct26
        val octTab = snapshot.monthTabs.first { it.tabName == "Oct26" }
        assertEquals(1, octTab.debits.size)
        assertEquals("Oct Fuel", octTab.debits.first().description)
        assertEquals("", octTab.debits.first().note)
        assertEquals("txn_oct_legacy_31", octTab.debits.first().uuid)
        assertEquals(1, octTab.inflows.size)
        assertEquals("inf_oct_legacy_31", octTab.inflows.first().uuid)
        assertEquals(1, octTab.payables.size)
        assertEquals("pay_oct_legacy_31", octTab.payables.first().uuid)

        // 32-column modern tab Nov26
        val novTab = snapshot.monthTabs.first { it.tabName == "Nov26" }
        assertEquals(1, novTab.debits.size)
        assertEquals("Nov Dinner", novTab.debits.first().description)
        assertEquals("Team Dinner", novTab.debits.first().note)
        assertEquals("txn_nov_modern_32", novTab.debits.first().uuid)
        assertEquals(1, novTab.inflows.size)
        assertEquals("inf_nov_modern_32", novTab.inflows.first().uuid)
        assertEquals(1, novTab.payables.size)
        assertEquals("pay_nov_modern_32", novTab.payables.first().uuid)
    }
}
