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

        // 2. Verify dimension properties (34 total: 2 initial spacer config + 1 UUID hide + 28 column widths + 3 row heights)
        val dimPropsReqs = requests.filter { it.containsKey("updateDimensionProperties") }
        assertEquals(34, dimPropsReqs.size)

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
                    decodedUrl.contains("AC4:AC") -> existingDebits.map { listOf(it.getOrNull(10) ?: "") }
                    decodedUrl.contains("M4:M") -> existingInflows.map { listOf(it.getOrNull(0) ?: "") }
                    decodedUrl.contains("AD4:AD") -> existingInflows.map { listOf(it.getOrNull(7) ?: "") }
                    decodedUrl.contains("V4:V") -> existingPayables.map { listOf(it.getOrNull(0) ?: "") }
                    decodedUrl.contains("AE4:AE") -> existingPayables.map { listOf(it.getOrNull(6) ?: "") }
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

                val rangeMatch = Regex("values/'?$tabName'?!([^\\?]+)").find(decodedUrl)?.groupValues?.get(1) ?: "A4:K4"
                val body = """
                    {
                        "spreadsheetId": "test_sheet_id",
                        "updatedRange": "$tabName!$rangeMatch",
                        "updatedRows": 1,
                        "updatedColumns": 11,
                        "updatedCells": 11
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

        val row = listOf(46331, 0.5, "SBI", "Swiggy", "", "Food & Drink", 350.0, "Personal", 350.0, 0.0, "Settled")
        val result = client.appendTransaction("test_sheet_id", "Nov26", row)

        assertTrue(result is Result.Success)
        val rowIndex = (result as Result.Success).data
        assertEquals(4, rowIndex)

        val putUrl = requireNotNull(capturedPutUrl)
        assertTrue("Must use values.update to A4:K4", putUrl.contains("A4:K4"))
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
        assertTrue("Next debit must target A8:K8", putUrl.contains("A8:K8"))
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
        assertTrue("Inflow must target M4:T4", putUrl.contains("M4:T4"))
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
        assertTrue("Payable must target V4:AB4", putUrl.contains("V4:AB4"))
    }

    @Test
    fun testBug003EmptyInflowsAndPayablesDoNotFallBackToColumnA() = runBlocking {
        var capturedPutUrl: String? = null
        // Debits table has 4 rows (rows 4..7). Inflows (M:T) and Payables (V:AB) are EMPTY.
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

        // 1. Inflow append on empty Inflow table must write strictly to M4:T4, NEVER A8:H8
        val inflowRow = listOf(46334, 0.5, "SBI", "Friend Repayment", "Charlie", "Settlement", 75.0, "txn_dinner")
        val inflowRes = client.appendInflow("test_sheet_id", "Nov26", inflowRow)
        assertTrue(inflowRes is Result.Success)
        assertEquals(4, (inflowRes as Result.Success).data)
        val inflowUrl = requireNotNull(capturedPutUrl)
        assertTrue("Inflow must write to M4:T4", inflowUrl.contains("M4:T4"))
        assertTrue("Inflow must NEVER write to Column A", !inflowUrl.contains("A4:K4") && !inflowUrl.contains("A8:K8") && !inflowUrl.contains("A8:H8"))

        // 2. Payable append on empty Payable table must write strictly to V4:AB4, NEVER A8:G8
        capturedPutUrl = null
        val payableRow = listOf(46334, 0.5, "Charlie", "Cab fare", 100.0, "", "Pending")
        val payableRes = client.appendPayable("test_sheet_id", "Nov26", payableRow)
        assertTrue(payableRes is Result.Success)
        assertEquals(4, (payableRes as Result.Success).data)
        val payableUrl = requireNotNull(capturedPutUrl)
        assertTrue("Payable must write to V4:AB4", payableUrl.contains("V4:AB4"))
        assertTrue("Payable must NEVER write to Column A", !payableUrl.contains("A4:K4") && !payableUrl.contains("A8:K8") && !payableUrl.contains("A8:G8"))

        // 3. Debit append must write to A8:K8 (next free debit row after 4 existing rows)
        capturedPutUrl = null
        val debitRow = listOf(46334, 0.5, "SBI", "Coffee", "", "Food & Drink", 50.0, "Personal", 50.0, 0.0, "Settled")
        val debitRes = client.appendDebit("test_sheet_id", "Nov26", debitRow)
        assertTrue(debitRes is Result.Success)
        assertEquals(8, (debitRes as Result.Success).data)
        val debitUrl = requireNotNull(capturedPutUrl)
        assertTrue("Debit must write to A8:K8", debitUrl.contains("A8:K8"))
    }

    @Test
    fun testNeverWritesAcrossSpacerColumnsFor31ColumnArray() = runBlocking {
        var capturedPutUrl: String? = null
        val client = createFakeSheetServer(
            tabName = "Nov26",
            existingDebits = emptyList(),
            onPut = { url, _ -> capturedPutUrl = url }
        )

        // 31-column row with Table 1 debit values and spacers
        val row31 = MutableList<Any?>(31) { "" }
        row31[0] = 46331
        row31[1] = 0.5
        row31[2] = "SBI"
        row31[3] = "Groceries"
        row31[6] = 500.0
        row31[28] = "txn_debit_uuid_123"

        val result = client.appendTransaction("test_sheet_id", "Nov26", row31)

        assertTrue(result is Result.Success)
        val putUrl = requireNotNull(capturedPutUrl)
        assertTrue("Must target A4:K4", putUrl.contains("A4:K4"))
        assertTrue("Must NEVER target A3:AE", !putUrl.contains("A3:AE") && !putUrl.contains("A4:AE4"))
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
}
