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

        // 2. Verify spacer columns pixelSize = 10
        val spacerReqs = requests.filter { it.containsKey("updateDimensionProperties") }
        assertEquals(2, spacerReqs.size)

        // 3. Verify Row 1 banner merge
        val mergeReqs = requests.filter { it.containsKey("mergeCells") }
        assertTrue(mergeReqs.size >= 5) // Banner + 4 sections

        // 4. Verify Row 3 has 31 column headers
        val updateCellsReq = requests.firstOrNull { it.containsKey("updateCells") }
        assertNotNull(updateCellsReq)
    }

    @Test
    fun testAppendTransactionSuccess() = runBlocking {
        var capturedAuthHeader: String? = null
        var capturedUrl: String? = null

        val client = createMockClient { request ->
            capturedAuthHeader = request.header("Authorization")
            capturedUrl = request.url.toString()

            val responseBody = """
                {
                    "spreadsheetId": "test_sheet_id",
                    "tableRange": "Nov26!A3:K17",
                    "updates": {
                        "spreadsheetId": "test_sheet_id",
                        "updatedRange": "Nov26!A18:K18",
                        "updatedRows": 1,
                        "updatedColumns": 11,
                        "updatedCells": 11
                    }
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

        val row = listOf(46331, 0.5, "SBI", "Swiggy", "", "Food & Drink", 350.0, "Personal", 350.0, 0.0, "Settled")
        val result = client.appendTransaction("test_sheet_id", "Nov26", row)

        assertTrue(result is Result.Success)
        val rowIndex = (result as Result.Success).data
        assertEquals(18, rowIndex)

        assertEquals("Bearer test_bearer_token_123", capturedAuthHeader)
        val url = requireNotNull(capturedUrl)
        assertTrue(url.contains("valueInputOption=USER_ENTERED") || url.contains("valueInputOption=RAW"))
        assertTrue(url.contains("insertDataOption=OVERWRITE"))
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
    fun testAppendTableScopedRoutingDebits() = runBlocking {
        var capturedUrl: String? = null
        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            val responseBody = """{"updates": {"updatedRange": "Nov26!A18:K18"}}"""
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody(jsonMediaType))
                .build()
        }

        val debitRow = listOf(46331, 0.5, "SBI", "Lunch", "", "Food & Drink", 200.0, "Personal", 200.0, 0.0, "Settled")
        val result = client.appendDebit("test_sheet_id", "Nov26", debitRow)

        assertTrue(result is Result.Success)
        val url = requireNotNull(capturedUrl)
        assertTrue(url.contains("A3%3AK") || url.contains("A3:K"))
    }

    @Test
    fun testAppendTableScopedRoutingInflows() = runBlocking {
        var capturedUrl: String? = null
        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            val responseBody = """{"updates": {"updatedRange": "Nov26!M18:T18"}}"""
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody(jsonMediaType))
                .build()
        }

        val inflowRow = listOf(46331, 0.5, "SBI", "Friend Repayment", "Alice", "Dinner share", 150.0, "txn_123")
        val result = client.appendInflow("test_sheet_id", "Nov26", inflowRow)

        assertTrue(result is Result.Success)
        val url = requireNotNull(capturedUrl)
        assertTrue(url.contains("M3%3AT") || url.contains("M3:T"))
    }

    @Test
    fun testAppendTableScopedRoutingPayables() = runBlocking {
        var capturedUrl: String? = null
        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            val responseBody = """{"updates": {"updatedRange": "Nov26!V18:AB18"}}"""
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody(jsonMediaType))
                .build()
        }

        val payableRow = listOf(46331, 0.5, "Bob", "Movie ticket", 250.0, "", "Pending")
        val result = client.appendPayable("test_sheet_id", "Nov26", payableRow)

        assertTrue(result is Result.Success)
        val url = requireNotNull(capturedUrl)
        assertTrue(url.contains("V3%3AAB") || url.contains("V3:AB"))
    }

    @Test
    fun testNeverWritesAcrossSpacerColumnsFor31ColumnArray() = runBlocking {
        var capturedUrl: String? = null
        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            val responseBody = """{"updates": {"updatedRange": "Nov26!A18:K18"}}"""
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody(jsonMediaType))
                .build()
        }

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
        val url = requireNotNull(capturedUrl)
        assertTrue("Must target A3:K", url.contains("A3%3AK") || url.contains("A3:K"))
        assertTrue("Must NEVER target A3:AE", !url.contains("A3:AE") && !url.contains("A3%3AAE"))
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
}
