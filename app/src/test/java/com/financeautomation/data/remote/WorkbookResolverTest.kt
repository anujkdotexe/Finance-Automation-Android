package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbookResolverTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val fixedClock: java.time.Clock = java.time.Clock.fixed(
        java.time.Instant.parse("2026-11-15T12:00:00Z"),
        java.time.ZoneId.of("Asia/Kolkata")
    )

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
        val rawPrefs = mutableMapOf<String, String?>()
        override suspend fun getPreference(key: String): String? = rawPrefs[key]
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {
            if (value != null) rawPrefs[key] = value else rawPrefs.remove(key)
        }
        override suspend fun deletePreference(key: String) { rawPrefs.remove(key) }
        override suspend fun clearKeysWithPrefix(prefix: String) {
            rawPrefs.keys.filter { it.startsWith(prefix) }.toList().forEach { rawPrefs.remove(it) }
        }
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
                        // First call: returns only Sheet1 (missing Dashboard)
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
                            .body("""{"replies": [{"addSheet": {"properties": {"sheetId": 102, "title": "Nov26"}}}]}""".toResponseBody(jsonMediaType))
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
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock
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
                        // Tabs exist: Dashboard and Nov26 already exist
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
                                    "sheetId": 102,
                                    "title": "Nov26"
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
                    url.contains("/values/Nov") && method == "GET" -> {
                        // Layout read: returns empty values (no headers populated)
                        val body = """
                            {
                              "range": "Nov26!A3:AE3",
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
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock
        )

        val result = resolver.resolveForYear(2026)

        assertTrue("Expected resolveForYear to succeed", result is Result.Success)
        assertEquals(foundWorkbookId, (result as Result.Success).data)
        assertTrue("Expected provisionMasterWorkbook batchUpdate to run when tabs exist but layout is empty", provisionBatchCalled)
    }

    @Test
    fun testResolverRepairsOnlyMonthTabWhenMonthTabLayoutIsMissing() = runBlocking<Unit> {
        var batchUpdateCalled = false
        var batchBody = ""
        val foundWorkbookId = "existing_partial_month_wb_789"
        val currentMonthTab = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("MMMyy", java.util.Locale.US))

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
                        // Tabs exist: Dashboard and Nov26
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
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock
        )

        val result = resolver.resolveForYear(2026)

        assertTrue("Expected resolveForYear to succeed", result is Result.Success)
        assertEquals(foundWorkbookId, (result as Result.Success).data)
        assertTrue("Expected batchUpdate to run to repair month tab", batchUpdateCalled)
        // Verify that batchUpdate targets the month tab, not re-provisioning the full master workbook
        assertTrue("Repair batch should target the month tab", batchBody.contains(currentMonthTab) || batchBody.contains("102"))
        assertFalse("Should not re-create the Dashboard sheet when repairing only the month tab", batchBody.contains("\"title\":\"Dashboard\""))
    }

    @Test
    fun testResolverCreatesOnlyMonthTabWhenMonthTabIsMissing() = runBlocking<Unit> {
        var batchUpdateCalled = false
        var batchBody = ""
        val foundWorkbookId = "existing_no_month_wb_790"
        val currentMonthTab = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("MMMyy", java.util.Locale.US))

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
                        // Tabs exist: Dashboard only; the month tab is missing
                        val body = """
                            {
                              "sheets": [
                                {
                                  "properties": {
                                    "sheetId": 0,
                                    "title": "Dashboard"
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
                        batchBody += buffer.readUtf8()
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"replies": [{"addSheet": {"properties": {"sheetId": 102, "title": "$currentMonthTab"}}}]}""".toResponseBody(jsonMediaType))
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
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock
        )

        val result = resolver.resolveForYear(2026)

        assertTrue("Expected resolveForYear to succeed", result is Result.Success)
        assertEquals(foundWorkbookId, (result as Result.Success).data)
        assertTrue("Expected batchUpdate to create the missing month tab", batchUpdateCalled)
        // Verify that batchUpdate targets the month tab, not re-provisioning the full master workbook
        assertTrue("Batch should create the month tab", batchBody.contains(currentMonthTab))
        assertFalse("Should not re-create the Dashboard sheet when only the month tab is missing", batchBody.contains("\"title\":\"Dashboard\""))
    }

    private class FreshDriveClient(tokenProvider: TokenProvider) : GoogleDriveClient(OkHttpClient.Builder().build(), tokenProvider) {
        val createdNames = mutableListOf<String>()
        val createdFolderIds = mutableListOf<String>()
        var createResult: Result<String> = Result.Success("fresh_sheet_id")

        override suspend fun findOrCreateFolder(folderName: String): Result<String> {
            assertEquals("Personal Finance", folderName)
            return Result.Success("folder_1")
        }

        override suspend fun createSpreadsheet(folderId: String, spreadsheetName: String, year: Int?): Result<String> {
            createdFolderIds.add(folderId)
            createdNames.add(spreadsheetName)
            return createResult
        }
    }

    private class FreshSheetsClient(tokenProvider: TokenProvider) : GoogleSheetsClient(OkHttpClient.Builder().build(), tokenProvider) {
        val provisioned = mutableListOf<String>()
        var provisionResult: Result<Unit> = Result.Success(Unit)

        override suspend fun provisionMasterWorkbook(spreadsheetId: String, accounts: List<String>, categories: List<String>): Result<Unit> {
            provisioned.add(spreadsheetId)
            return provisionResult
        }

        override suspend fun provisionMasterWorkbook(spreadsheetId: String, accounts: List<String>, categories: List<String>, year: Int): Result<Unit> {
            provisioned.add(spreadsheetId)
            return provisionResult
        }
    }

    private fun prefsWithOldId() = FakePreferencesRepository().apply {
        yearMap[2026] = "old_sheet_id"
        activeSpreadsheetId = "old_sheet_id"
    }

    @Test
    fun testCreateFreshWorkbook_createsNewWorkbookAndSavesBothKeys() = runBlocking<Unit> {
        val drive = FreshDriveClient(fakeTokenProvider)
        val sheets = FreshSheetsClient(fakeTokenProvider)
        val prefs = prefsWithOldId()
        val resolver = WorkbookResolver(drive, sheets, prefs, ioDispatcher = Dispatchers.Unconfined, clock = fixedClock)

        val result = resolver.createFreshWorkbook(2026)

        assertTrue(result is Result.Success)
        assertEquals("fresh_sheet_id", (result as Result.Success).data)
        assertEquals("fresh_sheet_id", prefs.yearMap[2026])
        assertEquals("fresh_sheet_id", prefs.activeSpreadsheetId)
        // One new spreadsheet, named with a time stamp so the name is unique, and it was provisioned
        assertEquals(listOf("folder_1"), drive.createdFolderIds)
        assertEquals(1, drive.createdNames.size)
        val namePattern = Regex("""Finance2026 \(new \d{4}-\d{2}-\d{2} \d{2}:\d{2}\)""")
        assertTrue(drive.createdNames.first(), namePattern.matches(drive.createdNames.first()))
        assertEquals(listOf("fresh_sheet_id"), sheets.provisioned)
    }

    @Test
    fun testCreateFreshWorkbook_createFailureKeepsStoredIds() = runBlocking<Unit> {
        val drive = FreshDriveClient(fakeTokenProvider).apply {
            createResult = Result.Error(java.io.IOException("Drive quota"))
        }
        val sheets = FreshSheetsClient(fakeTokenProvider)
        val prefs = prefsWithOldId()
        val resolver = WorkbookResolver(drive, sheets, prefs, ioDispatcher = Dispatchers.Unconfined, clock = fixedClock)

        val result = resolver.createFreshWorkbook(2026)

        assertTrue(result is Result.Error)
        assertEquals("old_sheet_id", prefs.yearMap[2026])
        assertEquals("old_sheet_id", prefs.activeSpreadsheetId)
        assertTrue(sheets.provisioned.isEmpty())
    }

    @Test
    fun testCreateFreshWorkbook_provisionFailureKeepsStoredIds() = runBlocking<Unit> {
        val drive = FreshDriveClient(fakeTokenProvider)
        val sheets = FreshSheetsClient(fakeTokenProvider).apply {
            provisionResult = Result.Error(GoogleSheetsApiException(500, "boom"))
        }
        val prefs = prefsWithOldId()
        val resolver = WorkbookResolver(drive, sheets, prefs, ioDispatcher = Dispatchers.Unconfined, clock = fixedClock)

        val result = resolver.createFreshWorkbook(2026)

        assertTrue(result is Result.Error)
        assertEquals("old_sheet_id", prefs.yearMap[2026])
        assertEquals("old_sheet_id", prefs.activeSpreadsheetId)
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
                                { "properties": { "sheetId": 102, "title": "Nov26" } }
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
                    url.contains("/values/Nov") && method == "GET" -> {
                        // Headers with wrong first and last column names
                        val headers = (0 until com.financeautomation.core.constants.SheetSchemaConstants.TOTAL_COLUMN_COUNT).joinToString(", ") { idx ->
                            "\"LegacyHeader$idx\""
                        }
                        val body = """
                            {
                              "range": "Nov26!A3:AE3",
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
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock
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
                              "range": "Nov26!A3:AE3",
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
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock
        )

        val res = resolver.resolveForYear(2026, forceValidateTabs = true, createBackup = true)

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
                              "range": "Nov26!A3:AE3",
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
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock
        )

        val res = resolver.resolveForYear(2026, forceValidateTabs = true, createBackup = true)

        assertTrue(res is Result.Success)
        assertFalse("Drive copyFile must NOT be called when workbook has no data rows", copyFileCalled)
    }

    @Test
    fun requeueFailureDoesNotBreakResolutionAndSchedulesARetry() = runBlocking {
        Diagnostics.clear()
        val storedId = "stored_wb_trashed"
        val foundId = "found_replacement_wb"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when {
                    url.contains("/files/$storedId") -> {
                        val body = """{"id": "$storedId", "name": "Finance2026", "trashed": true}"""
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody(jsonMediaType))
                            .build()
                    }
                    url.contains("/files?") && request.method == "GET" -> {
                        val body = """{"files": [{"id": "$foundId", "name": "Finance2026"}]}"""
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
            }).build(),
            fakeTokenProvider
        )

        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when {
                    url.contains("fields=sheets") && request.method == "GET" -> {
                        val body = """{"sheets": [{"properties": {"sheetId": 0, "title": "Dashboard"}}, {"properties": {"sheetId": 1, "title": "Nov26"}}]}"""
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
                        val body = """{"range": "Nov26!A3:AE3", "values": [ [ $validHeaders ] ]}"""
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
            }).build(),
            fakeTokenProvider
        )

        val prefs = FakePreferencesRepository().apply {
            setSpreadsheetIdForYear(2026, storedId)
        }

        var requeueAttempted = false
        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock,
            onWorkbookReplaced = { _, _, _ ->
                requeueAttempted = true
                throw IllegalStateException("Requeue failed in test")
            }
        )

        val res = resolver.resolveForYear(2026, forceValidateTabs = true)

        assertTrue("Resolution must succeed even if requeue throws", res is Result.Success)
        assertEquals(foundId, (res as Result.Success).data)
        assertTrue(requeueAttempted)
        assertEquals("2026", prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR))
        assertNull(
            "Notice flag must NOT be set when requeue fails",
            prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)
        )
        val records = Diagnostics.getRecords()
        assertTrue(
            "Diagnostics must record REQUEUE_FAILED with exception class",
            records.any { it.tag == "WorkbookResolver" && it.reason.contains("REQUEUE_FAILED") && it.reason.contains("IllegalStateException") }
        )
    }

    @Test
    fun retryHappensOnTheNextResolve() = runBlocking {
        val storedId = "stored_healthy_wb"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when {
                    url.contains("/files/$storedId") -> {
                        val body = """{"id": "$storedId", "name": "Finance2026", "trashed": false}"""
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
            }).build(),
            fakeTokenProvider
        )

        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when {
                    url.contains("fields=sheets") && request.method == "GET" -> {
                        val body = """{"sheets": [{"properties": {"sheetId": 0, "title": "Dashboard"}}, {"properties": {"sheetId": 1, "title": "Nov26"}}]}"""
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
                        val body = """{"range": "Nov26!A3:AE3", "values": [ [ $validHeaders ] ]}"""
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
            }).build(),
            fakeTokenProvider
        )

        val prefs = FakePreferencesRepository().apply {
            setSpreadsheetIdForYear(2026, storedId)
            setPreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR, "2026", isDirty = false)
        }

        var retryCalledWithYear: Int? = null
        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock,
            onWorkbookReplaced = { year, _, _ ->
                retryCalledWithYear = year
            }
        )

        val res = resolver.resolveForYear(2026)

        assertTrue(res is Result.Success)
        assertEquals(2026, retryCalledWithYear)
        assertNull(
            "Retry marker must be cleared on successful requeue",
            prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR)
        )
        assertEquals(
            "true",
            prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)
        )
    }

    @Test
    fun noticeFlagIsNotSetWhenRequeueFails() = runBlocking {
        Diagnostics.clear()
        val storedId = "stored_wb_trashed"
        val foundId = "found_replacement_wb"

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when {
                    url.contains("/files/$storedId") -> {
                        val body = """{"id": "$storedId", "name": "Finance2026", "trashed": true}"""
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody(jsonMediaType))
                            .build()
                    }
                    url.contains("/files?") && request.method == "GET" -> {
                        val body = """{"files": [{"id": "$foundId", "name": "Finance2026"}]}"""
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
            }).build(),
            fakeTokenProvider
        )

        val sheetsClient = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when {
                    url.contains("fields=sheets") && request.method == "GET" -> {
                        val body = """{"sheets": [{"properties": {"sheetId": 0, "title": "Dashboard"}}, {"properties": {"sheetId": 1, "title": "Nov26"}}]}"""
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
                        val body = """{"range": "Nov26!A3:AE3", "values": [ [ $validHeaders ] ]}"""
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
            ioDispatcher = Dispatchers.IO,
            clock = fixedClock,
            onWorkbookReplaced = { _, _, _ ->
                throw RuntimeException("Requeue error")
            }
        )

        resolver.resolveForYear(2026, forceValidateTabs = true)

        assertNull(
            "Notice flag must be null when requeue fails",
            prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)
        )
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun cancellationExceptionIsRethrownAndNotSwallowed() {
        runBlocking {
            val storedId = "stored_wb_trashed"
            val foundId = "found_replacement_wb"

            val driveClient = GoogleDriveClient(
                OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    when {
                        url.contains("/files/$storedId") -> {
                            val body = """{"id": "$storedId", "name": "Finance2026", "trashed": true}"""
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(body.toResponseBody(jsonMediaType))
                                .build()
                        }
                        url.contains("/files?") && request.method == "GET" -> {
                            val body = """{"files": [{"id": "$foundId", "name": "Finance2026"}]}"""
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
                }).build(),
                fakeTokenProvider
            )

            val sheetsClient = GoogleSheetsClient(
                OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    when {
                        url.contains("fields=sheets") && request.method == "GET" -> {
                            val body = """{"sheets": [{"properties": {"sheetId": 0, "title": "Dashboard"}}, {"properties": {"sheetId": 1, "title": "Nov26"}}]}"""
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
                            val body = """{"range": "Nov26!A3:AE3", "values": [ [ $validHeaders ] ]}"""
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
                ioDispatcher = Dispatchers.IO,
                clock = fixedClock,
                onWorkbookReplaced = { _, _, _ ->
                    throw kotlinx.coroutines.CancellationException("Job cancelled")
                }
            )

            resolver.resolveForYear(2026, forceValidateTabs = true)
        }
    }
}
