package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbookResolverTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val fakeTokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "test_token"
        override fun refreshAccessToken(): String = "test_token"
    }

    private class FakePreferencesRepository : PreferencesRepository {
        val yearMap = mutableMapOf<Int, String>()
        var activeSpreadsheetId: String? = null

        override fun getSpreadsheetId(): Flow<String?> = flowOf(activeSpreadsheetId)
        override suspend fun getSpreadsheetIdSync(): String? = activeSpreadsheetId
        override suspend fun setSpreadsheetId(id: String) { activeSpreadsheetId = id }
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(yearMap[year])
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = yearMap[year]
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) { yearMap[year] = id }
        override fun getDefaultAccount(): Flow<String> = flowOf("Account")
        override suspend fun getDefaultAccountSync(): String = "Account"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(trip: String?) {}
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
        override suspend fun getGoogleAccountEmailSync(): String? = null
        override suspend fun setGoogleAccountEmail(email: String?) {}
        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(120)
        override suspend fun getSettleDelaySecondsSync(): Int = 120
        override suspend fun setSettleDelaySeconds(seconds: Int) {}
        override suspend fun getOrCreateDeviceId(): String = "test-device-uuid"
        override suspend fun getDeviceIdSync(): String? = "test-device-uuid"
        override fun getDeveloperMode(): Flow<Boolean> = flowOf(false)
        override suspend fun getDeveloperModeSync(): Boolean = false
        override suspend fun setDeveloperMode(enabled: Boolean) {}
        override fun getThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun getThemeModeSync(): ThemeMode = ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun getPreference(key: String): String? = null
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {}
        override suspend fun deletePreference(key: String) {}
    }

    @Test
    fun testResolverRepairsExistingWorkbookWithOnlySheet1() = runBlocking {
        var provisionBatchCalled = false
        val foundWorkbookId = "existing_single_tab_wb_123"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("/files") && request.method == "GET") {
                    val body = """
                        {
                          "files": [
                            {
                              "id": "$foundWorkbookId",
                              "name": "Finance2026"
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
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }).build(),
            fakeTokenProvider
        )

        var sheetTabsCallCount = 0
        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val method = request.method

                when {
                    url.contains("fields=sheets") && method == "GET" -> {
                        sheetTabsCallCount++
                        // First call: returns only Sheet1 (missing Dashboard & Template)
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
                        if (bodyString.contains("addSheet") || bodyString.contains("updateSheetProperties")) {
                            provisionBatchCalled = true
                        }
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"replies": [{"addSheet": {"properties": {"sheetId": 101, "title": "Template"}}}]}""".toResponseBody(jsonMediaType))
                            .build()
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
            }).build(),
            fakeTokenProvider
        )

        val prefs = FakePreferencesRepository()
        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val result = resolver.resolveForYear(2026)

        assertTrue("Expected resolveForYear to succeed", result is Result.Success)
        assertEquals(foundWorkbookId, (result as Result.Success).data)
        assertTrue("Expected provisionMasterWorkbook batchUpdate to run for workbook with only Sheet1", provisionBatchCalled)
        assertEquals("Spreadsheet ID should be saved in preferences", foundWorkbookId, prefs.getSpreadsheetIdForYearSync(2026))
    }

    @Test
    fun testResolverRepairsPartiallyProvisionedWorkbookWhereTabsExistButLayoutIsMissing() = runBlocking {
        var provisionBatchCalled = false
        val foundWorkbookId = "existing_partial_tabs_wb_456"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("/files") && request.method == "GET") {
                    val body = """
                        {
                          "files": [
                            {
                              "id": "$foundWorkbookId",
                              "name": "Finance2026"
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
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }).build(),
            fakeTokenProvider
        )

        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val method = request.method

                when {
                    url.contains("fields=sheets") && method == "GET" -> {
                        // Tabs exist: Dashboard and Template already exist
                        val body = """
                            {
                              "sheets": [
                                {
                                  "properties": {
                                    "sheetId": 0,
                                    "title": "Dashboard"
                                  }
                                },
                                {
                                  "properties": {
                                    "sheetId": 101,
                                    "title": "Template"
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
                    url.contains("/values/Template") && method == "GET" -> {
                        // Layout read: returns empty values (no headers populated)
                        val body = """
                            {
                              "range": "Template!A3:AE3",
                              "majorDimension": "ROWS",
                              "values": []
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
                        provisionBatchCalled = true
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
                            .body("""{"replies": []}""".toResponseBody(jsonMediaType))
                            .build()
                    }
                }
            }).build(),
            fakeTokenProvider
        )

        val prefs = FakePreferencesRepository()
        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val result = resolver.resolveForYear(2026)

        assertTrue("Expected resolveForYear to succeed", result is Result.Success)
        assertEquals(foundWorkbookId, (result as Result.Success).data)
        assertTrue("Expected provisionMasterWorkbook batchUpdate to run when tabs exist but layout is empty", provisionBatchCalled)
    }

    @Test
    fun testResolverRepairsOnlyMonthTabWhenTemplateIsValidButMonthTabLayoutIsMissing() = runBlocking<Unit> {
        var batchUpdateCalled = false
        var batchBody = ""
        val foundWorkbookId = "existing_partial_month_wb_789"
        val currentMonthTab = "Nov26"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("/files") && request.method == "GET") {
                    val body = """
                        {
                          "files": [
                            {
                              "id": "$foundWorkbookId",
                              "name": "Finance2026"
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
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }).build(),
            fakeTokenProvider
        )

        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val method = request.method

                when {
                    url.contains("fields=sheets") && method == "GET" -> {
                        // Tabs exist: Dashboard, Template, and Nov26
                        val body = """
                            {
                              "sheets": [
                                {
                                  "properties": {
                                    "sheetId": 0,
                                    "title": "Dashboard"
                                  }
                                },
                                {
                                  "properties": {
                                    "sheetId": 101,
                                    "title": "Template"
                                  }
                                },
                                {
                                  "properties": {
                                    "sheetId": 102,
                                    "title": "$currentMonthTab"
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
                    url.contains("/values/Template") && method == "GET" -> {
                        // Template tab has valid 31 headers
                        val headers = (0 until com.financeautomation.core.constants.SheetSchemaConstants.TOTAL_COLUMN_COUNT).joinToString(", ") { idx ->
                            val colLetter = com.financeautomation.core.constants.SheetSchemaConstants.SHEET_COLUMNS[idx]
                            "\"" + com.financeautomation.core.constants.SheetSchemaConstants.SHEET_COLUMN_HEADERS[colLetter] + "\""
                        }
                        val body = """
                            {
                              "range": "Template!A3:AE3",
                              "majorDimension": "ROWS",
                              "values": [
                                [ $headers ]
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
                    url.contains("/values/$currentMonthTab") && method == "GET" -> {
                        // Month tab is empty (no headers)
                        val body = """
                            {
                              "range": "$currentMonthTab!A3:AE3",
                              "majorDimension": "ROWS",
                              "values": []
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
                        batchUpdateCalled = true
                        val buffer = okio.Buffer()
                        request.body?.writeTo(buffer)
                        batchBody = buffer.readUtf8()
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
                            .body("""{"replies": []}""".toResponseBody(jsonMediaType))
                            .build()
                    }
                }
            }).build(),
            fakeTokenProvider
        )

        val prefs = FakePreferencesRepository()
        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val result = resolver.resolveForYear(2026)

        assertTrue("Expected resolveForYear to succeed", result is Result.Success)
        assertEquals(foundWorkbookId, (result as Result.Success).data)
        assertTrue("Expected batchUpdate to run to repair month tab", batchUpdateCalled)
        // Verify that batchUpdate targets the month tab, not re-provisioning the full master workbook
        assertFalse("Should not attempt to add Template sheet when repairing month tab", batchBody.contains("\"title\":\"Template\""))
    }

    @Test
    fun testResolverReProvisionsWhenHeaderNamesDoNotMatchSchemaConstants() = runBlocking<Unit> {
        var provisionBatchCalled = false
        val foundWorkbookId = "existing_mismatched_headers_wb_999"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("/files") && request.method == "GET") {
                    val body = """
                        {
                          "files": [
                            {
                              "id": "$foundWorkbookId",
                              "name": "Finance2026"
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
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }).build(),
            fakeTokenProvider
        )

        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val method = request.method

                when {
                    url.contains("fields=sheets") && method == "GET" -> {
                        val body = """
                            {
                              "sheets": [
                                { "properties": { "sheetId": 0, "title": "Dashboard" } },
                                { "properties": { "sheetId": 101, "title": "Template" } }
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
                    url.contains("/values/Template") && method == "GET" -> {
                        // Headers with wrong first and last column names
                        val headers = (0 until com.financeautomation.core.constants.SheetSchemaConstants.TOTAL_COLUMN_COUNT).joinToString(", ") { idx ->
                            "\"LegacyHeader$idx\""
                        }
                        val body = """
                            {
                              "range": "Template!A3:AE3",
                              "majorDimension": "ROWS",
                              "values": [ [ $headers ] ]
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
                        provisionBatchCalled = true
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
                            .body("""{"replies": []}""".toResponseBody(jsonMediaType))
                            .build()
                    }
                }
            }).build(),
            fakeTokenProvider
        )

        val prefs = FakePreferencesRepository()
        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val result = resolver.resolveForYear(2026)

        assertTrue("Expected resolveForYear to succeed", result is Result.Success)
        assertEquals(foundWorkbookId, (result as Result.Success).data)
        assertTrue("Expected provisionMasterWorkbook batchUpdate to run when headers do not match schema constants", provisionBatchCalled)
    }

    @Test
    fun testPreRepairCreatesBackupWhenWorkbookContainsDataRows() = runBlocking {
        var copyFileCalled = false
        var copiedBackupName: String? = null
        val storedId = "stored_wb_with_data_789"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("/copy") && request.method == "POST") {
                    copyFileCalled = true
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    copiedBackupName = buffer.readUtf8()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"id": "backup_file_id_999", "name": "backup"}""".toResponseBody(jsonMediaType))
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{}""".toResponseBody(jsonMediaType))
                        .build()
                }
            }).build(),
            fakeTokenProvider
        )

        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()

                when {
                    url.contains("fields=sheets") -> {
                        val body = """
                            {
                              "sheets": [
                                { "properties": { "sheetId": 0, "title": "Dashboard" } },
                                { "properties": { "sheetId": 1, "title": "Template" } },
                                { "properties": { "sheetId": 2, "title": "Nov26" } }
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
                    url.contains("/values:batchGet") -> {
                        // Month tab Nov26 contains a transaction data row at row 4
                        val body = """
                            {
                              "valueRanges": [
                                {
                                  "range": "'Nov26'!A4:A5",
                                  "values": [ ["11/11/2026"] ]
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
                    url.contains("/values/") -> {
                        val validHeaders = com.financeautomation.core.constants.SheetSchemaConstants.SHEET_COLUMN_HEADERS.values.joinToString(", ") { "\"$it\"" }
                        val body = """
                            {
                              "range": "Template!A3:AE3",
                              "values": [ [ $validHeaders ] ]
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
            }).build(),
            fakeTokenProvider
        )

        val prefs = FakePreferencesRepository().apply {
            setSpreadsheetIdForYear(2026, storedId)
        }
        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val res = resolver.resolveForYear(2026, forceValidateTabs = true)

        assertTrue(res is Result.Success)
        assertTrue("Drive copyFile must be called before repair when data rows exist", copyFileCalled)
        assertTrue("Backup file name must contain 'backup before repair'", copiedBackupName?.contains("backup before repair") == true)
    }

    @Test
    fun testPreRepairSkipsBackupWhenWorkbookHasNoDataRows() = runBlocking {
        var copyFileCalled = false
        val storedId = "stored_empty_wb_111"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("/copy")) {
                    copyFileCalled = true
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{}""".toResponseBody(jsonMediaType))
                    .build()
            }).build(),
            fakeTokenProvider
        )

        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()

                when {
                    url.contains("fields=sheets") -> {
                        val body = """
                            {
                              "sheets": [
                                { "properties": { "sheetId": 0, "title": "Dashboard" } },
                                { "properties": { "sheetId": 1, "title": "Template" } },
                                { "properties": { "sheetId": 2, "title": "Nov26" } }
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
                    url.contains("/values:batchGet") -> {
                        // Month tab Nov26 is completely empty in row 4+
                        val body = """
                            {
                              "valueRanges": [
                                {
                                  "range": "'Nov26'!A4:A5",
                                  "values": []
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
                    url.contains("/values/") -> {
                        val validHeaders = com.financeautomation.core.constants.SheetSchemaConstants.SHEET_COLUMN_HEADERS.values.joinToString(", ") { "\"$it\"" }
                        val body = """
                            {
                              "range": "Template!A3:AE3",
                              "values": [ [ $validHeaders ] ]
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
            }).build(),
            fakeTokenProvider
        )

        val prefs = FakePreferencesRepository().apply {
            setSpreadsheetIdForYear(2026, storedId)
        }
        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO
        )

        val res = resolver.resolveForYear(2026, forceValidateTabs = true)

        assertTrue(res is Result.Success)
        assertFalse("Drive copyFile must NOT be called when workbook has no data rows", copyFileCalled)
    }
}
