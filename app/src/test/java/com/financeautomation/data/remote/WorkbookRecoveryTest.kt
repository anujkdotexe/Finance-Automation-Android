package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime

/** A stored workbook the signed in account cannot open is replaced by a search, not reported as an error. */
class WorkbookRecoveryTest {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val tokens = object : TokenProvider {
        override fun getAccessToken(): String = "t"
        override fun refreshAccessToken(): String = "t"
    }
    private val year = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
    private val yearIds = mutableMapOf<Int, String>()
    private var legacySpreadsheetId: String? = null
    private val rawPrefs = mutableMapOf<String, String?>()

    private val prefs: PreferencesRepository = Proxy.newProxyInstance(
        PreferencesRepository::class.java.classLoader, arrayOf(PreferencesRepository::class.java)
    ) { _, m, args ->
        when (m.name) {
            "getSpreadsheetIdForYearSync" -> yearIds[args!![0] as Int]
            "setSpreadsheetIdForYear" -> { yearIds[args!![0] as Int] = args[1] as String; null }
            "getSpreadsheetIdSync" -> legacySpreadsheetId
            "setSpreadsheetId" -> { legacySpreadsheetId = args!![0] as String?; null }
            "getSpreadsheetTitle" -> flowOf("Finance$year")
            "setSpreadsheetTitle" -> null
            "getPreference" -> rawPrefs[args!![0] as String]
            "setPreference" -> { rawPrefs[args!![0] as String] = args[1] as String?; null }
            "deletePreference" -> { rawPrefs.remove(args!![0] as String); null }
            "clearKeysWithPrefix" -> {
                val prefix = args!![0] as String
                rawPrefs.keys.filter { it.startsWith(prefix) }.toList().forEach { rawPrefs.remove(it) }
                null
            }
            else -> if (kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(m.returnType)) flowOf<Any?>(null) else null
        }
    } as PreferencesRepository

    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody(json)).build()

    private fun resolver(blockedCode: Int, calls: MutableList<String>): WorkbookResolver {
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "drive ${r.method} ${r.url.encodedPath}"
                if (r.method == "GET" && r.url.encodedPath.endsWith("/files")) {
                    response(r, 200, """{"files":[{"id":"new_id","name":"Finance$year"}]}""")
                } else response(r, 200, "{}")
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "sheets ${r.method} ${r.url.encodedPath}"
                when {
                    r.url.encodedPath.contains("old_id") ->
                        response(r, blockedCode, """{"error":{"code":$blockedCode,"message":"The caller does not have permission","errors":[{"reason":"forbidden"}],"status":"X"}}""")
                    r.method == "GET" -> response(r, 200, """{"spreadsheetId":"new_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":0}""")
                }
            }).build(), tokens
        )
        return WorkbookResolver(drive, sheets, prefs)
    }

    @Test
    fun aForbiddenStoredWorkbookIsReplacedByTheOneDriveFinds() = runBlocking {
        yearIds[year] = "old_id"
        val calls = mutableListOf<String>()
        val res = resolver(403, calls).resolveForYear(year, forceValidateTabs = true)
        assertTrue("expected success but was $res", res is Result.Success)
        assertEquals("new_id", (res as Result.Success).data)
        assertEquals("new_id", yearIds[year])
        assertTrue("Drive was searched", calls.any { it.startsWith("drive GET") && it.endsWith("/files") })
    }

    @Test
    fun aMissingStoredWorkbookIsReplacedToo() = runBlocking {
        yearIds[year] = "old_id"
        val res = resolver(404, mutableListOf()).resolveForYear(year, forceValidateTabs = true)
        assertEquals(Result.Success("new_id"), res)
    }

    @Test
    fun aTrashedStoredWorkbookIsReplacedByTheOneDriveFinds() = runBlocking {
        yearIds[year] = "old_id"
        val calls = mutableListOf<String>()
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "drive ${r.method} ${r.url.encodedPath}"
                when {
                    r.url.encodedPath.endsWith("/files/old_id") ->
                        response(r, 200, """{"id":"old_id","trashed":true}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[{"id":"new_id","name":"Finance$year"}]}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )
        // Sheets API still returns 200 OK for trashed files
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "sheets ${r.method} ${r.url.encodedPath}"
                when {
                    r.method == "GET" -> response(r, 200, """{"spreadsheetId":"any","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":0}""")
                }
            }).build(), tokens
        )
        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue("expected success but was $res", res is Result.Success)
        assertEquals("new_id", (res as Result.Success).data)
        assertEquals("new_id", yearIds[year])
        assertTrue("Drive was searched", calls.any { it.startsWith("drive GET") && it.endsWith("/files") })
    }

    @Test
    fun aPermanentlyDeletedDriveWorkbookCausesNewWorkbookResolution() = runBlocking {
        yearIds[year] = "old_id"
        val calls = mutableListOf<String>()
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "drive ${r.method} ${r.url.encodedPath}"
                when {
                    r.url.encodedPath.endsWith("/files/old_id") ->
                        response(r, 404, """{"error":{"code":404,"message":"File not found","errors":[{"reason":"notFound"}]}}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[{"id":"new_id","name":"Finance$year"}]}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "sheets ${r.method} ${r.url.encodedPath}"
                when {
                    r.method == "GET" -> response(r, 200, """{"spreadsheetId":"any","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":0}""")
                }
            }).build(), tokens
        )
        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue("expected success but was $res", res is Result.Success)
        assertEquals("new_id", (res as Result.Success).data)
        assertEquals("new_id", yearIds[year])
        assertTrue("Drive was searched", calls.any { it.startsWith("drive GET") && it.endsWith("/files") })
    }

    @Test
    fun aHealthyWorkbookIsKept() = runBlocking {
        yearIds[year] = "healthy_id"
        val calls = mutableListOf<String>()
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "drive ${r.method} ${r.url.encodedPath}"
                when {
                    r.url.encodedPath.endsWith("/files/healthy_id") ->
                        response(r, 200, """{"id":"healthy_id","trashed":false}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[{"id":"should_not_use","name":"Finance$year"}]}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "sheets ${r.method} ${r.url.encodedPath}"
                when {
                    r.method == "GET" -> response(r, 200, """{"spreadsheetId":"healthy_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":0}""")
                }
            }).build(), tokens
        )
        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue("expected success but was $res", res is Result.Success)
        assertEquals("healthy_id", (res as Result.Success).data)
        assertEquals("healthy_id", yearIds[year])
        org.junit.Assert.assertFalse("Drive files list search should not be called for healthy id",
            calls.any { it.startsWith("drive GET") && it.endsWith("/files") })
    }

    @Test
    fun driveMetadataNetworkErrorGivesNoNewWorkbook() = runBlocking {
        yearIds[year] = "old_id"
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (r.url.encodedPath.endsWith("/files/old_id")) {
                    throw java.io.IOException("Network timeout")
                }
                response(r, 200, "{}")
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, "{}")
            }).build(), tokens
        )
        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue("expected Result.Error on network exception, was $res", res is Result.Error)
        assertEquals("old_id", yearIds[year])
    }

    @Test
    fun driveMetadata500ErrorGivesNoNewWorkbook() = runBlocking {
        yearIds[year] = "old_id"
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (r.url.encodedPath.endsWith("/files/old_id")) {
                    response(r, 500, """{"error":{"code":500,"message":"Internal Server Error"}}""")
                } else response(r, 200, "{}")
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, "{}")
            }).build(), tokens
        )
        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)
        assertTrue("expected Result.Error on 500 error, was $res", res is Result.Error)
        assertEquals("old_id", yearIds[year])
    }

    @Test
    fun trashedIdInFindExistingFallsThroughToDriveSearch() = runBlocking {
        yearIds[year] = "old_id"
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.url.encodedPath.endsWith("/files/old_id") ->
                        response(r, 200, """{"id":"old_id","trashed":true}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[{"id":"new_id","name":"Finance$year"}]}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, "{}")
            }).build(), tokens
        )
        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.findExisting(year)
        assertEquals(Result.Success("new_id"), res)
    }

    @Test
    fun networkErrorInFindExistingReturnsError() = runBlocking {
        yearIds[year] = "old_id"
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (r.url.encodedPath.endsWith("/files/old_id")) {
                    throw java.io.IOException("Network timeout")
                }
                response(r, 200, "{}")
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, "{}")
            }).build(), tokens
        )
        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.findExisting(year)
        assertTrue("expected Result.Error, was $res", res is Result.Error)
    }

    private fun backupTestResolver(
        storedId: String,
        hasRows: Boolean,
        copyRequests: MutableList<String>
    ): WorkbookResolver {
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (r.url.encodedPath.endsWith("/copy")) {
                    copyRequests += "drive ${r.method} ${r.url.encodedPath}"
                    response(r, 200, """{"id":"backup_copy_id"}""")
                } else if (r.url.encodedPath.endsWith("/files/$storedId")) {
                    response(r, 200, """{"id":"$storedId","trashed":false}""")
                } else {
                    response(r, 200, "{}")
                }
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.url.encodedPath.contains("values:batchGet") -> {
                        val rowData = if (hasRows) """[["expense"]]""" else """[]"""
                        response(r, 200, """{"valueRanges":[{"range":"'Nov26'!A4:A5","values":$rowData}]}""")
                    }
                    r.method == "GET" -> {
                        response(r, 200, """{"spreadsheetId":"$storedId","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":2,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    }
                    else -> response(r, 200, """{"replies":[]}""")
                }
            }).build(), tokens
        )
        return WorkbookResolver(drive, sheets, prefs)
    }

    @Test
    fun signInForcedValidationDoesNotCopyTheWorkbook() = runBlocking {
        yearIds[year] = "valid_id"
        val copyRequests = mutableListOf<String>()
        val resolver = backupTestResolver("valid_id", hasRows = true, copyRequests = copyRequests)
        val res = resolver.resolveForYear(year, forceValidateTabs = true, createBackup = false)
        assertTrue("expected success but was $res", res is Result.Success)
        assertEquals("valid_id", (res as Result.Success).data)
        assertEquals("Sign-in forced validation must not copy workbook", 0, copyRequests.filter { it.endsWith("/copy") }.size)
    }

    @Test
    fun explicitRepairCopiesExactlyOnce() = runBlocking {
        yearIds[year] = "valid_id"
        val copyRequests = mutableListOf<String>()
        val resolver = backupTestResolver("valid_id", hasRows = true, copyRequests = copyRequests)
        val res = resolver.resolveForYear(year, forceValidateTabs = true, createBackup = true)
        assertTrue("expected success but was $res", res is Result.Success)
        assertEquals("valid_id", (res as Result.Success).data)
        assertEquals("Explicit repair must copy exactly once when rows exist", 1, copyRequests.filter { it.endsWith("/copy") }.size)
    }

    @Test
    fun repairOfAnEmptyWorkbookDoesNotCopy() = runBlocking {
        yearIds[year] = "valid_id"
        val copyRequests = mutableListOf<String>()
        val resolver = backupTestResolver("valid_id", hasRows = false, copyRequests = copyRequests)
        val res = resolver.resolveForYear(year, forceValidateTabs = true, createBackup = true)
        assertTrue("expected success but was $res", res is Result.Success)
        assertEquals("valid_id", (res as Result.Success).data)
        assertEquals("Repair of an empty workbook must not copy", 0, copyRequests.filter { it.endsWith("/copy") }.size)
    }

    @Test
    fun aTrashedStoredWorkbookWithNothingInDriveCreatesAndProvisionsANewOne() = runBlocking {
        yearIds[year] = "old_trashed_id"
        legacySpreadsheetId = "old_trashed_id"
        val driveCalls = mutableListOf<String>()
        val sheetCalls = mutableListOf<String>()
        val copyCalls = mutableListOf<String>()

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                driveCalls += "${r.method} ${r.url.encodedPath}"
                if (r.url.encodedPath.endsWith("/copy")) copyCalls += r.url.encodedPath
                when {
                    r.url.encodedPath.endsWith("/files/old_trashed_id") ->
                        response(r, 200, """{"id":"old_trashed_id","trashed":true}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[]}""")
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_created_id","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"created_sheet_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                sheetCalls += "${r.method} ${r.url.encodedPath}"
                when {
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"created_sheet_id","sheets":[{"properties":{"sheetId":0,"title":"Sheet1"}}]}""")
                    else ->
                        response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":10}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)

        assertTrue("Expected Result.Success, was $res", res is Result.Success)
        assertEquals("created_sheet_id", (res as Result.Success).data)
        assertEquals("created_sheet_id", yearIds[year])
        assertEquals("created_sheet_id", legacySpreadsheetId)
        assertEquals("No copy call must happen", 0, copyCalls.size)
        assertTrue("Drive folder must be created", driveCalls.any { it.startsWith("POST") && it.endsWith("/files") })
        assertTrue("Drive spreadsheet must be created", driveCalls.count { it.startsWith("POST") && it.endsWith("/files") } >= 2)
        assertTrue("Sheets batchUpdate must be called for provisioning", sheetCalls.any { it.contains("batchUpdate") })
    }

    @Test
    fun a404StoredWorkbookWithNothingInDriveCreatesAndProvisionsANewOne() = runBlocking {
        yearIds[year] = "old_404_id"
        legacySpreadsheetId = "old_404_id"
        val driveCalls = mutableListOf<String>()
        val sheetCalls = mutableListOf<String>()
        val copyCalls = mutableListOf<String>()

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                driveCalls += "${r.method} ${r.url.encodedPath}"
                if (r.url.encodedPath.endsWith("/copy")) copyCalls += r.url.encodedPath
                when {
                    r.url.encodedPath.endsWith("/files/old_404_id") ->
                        response(r, 404, """{"error":{"code":404,"message":"File not found","errors":[{"reason":"notFound"}]}}""")
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[]}""")
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_created_id","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"created_sheet_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                sheetCalls += "${r.method} ${r.url.encodedPath}"
                when {
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"created_sheet_id","sheets":[{"properties":{"sheetId":0,"title":"Sheet1"}}]}""")
                    else ->
                        response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":10}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)

        assertTrue("Expected Result.Success, was $res", res is Result.Success)
        assertEquals("created_sheet_id", (res as Result.Success).data)
        assertEquals("created_sheet_id", yearIds[year])
        assertEquals("created_sheet_id", legacySpreadsheetId)
        assertEquals("No copy call must happen", 0, copyCalls.size)
        assertTrue("Drive spreadsheet must be created", driveCalls.count { it.startsWith("POST") && it.endsWith("/files") } >= 2)
        assertTrue("Sheets batchUpdate must be called for provisioning", sheetCalls.any { it.contains("batchUpdate") })
    }

    @Test
    fun aDeletedFolderAndWorkbookWithNothingInDriveCreatesAndProvisionsANewOne() = runBlocking {
        yearIds[year] = "old_deleted_id"
        legacySpreadsheetId = "old_deleted_id"
        val driveCalls = mutableListOf<String>()
        val sheetCalls = mutableListOf<String>()
        val copyCalls = mutableListOf<String>()

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                driveCalls += "${r.method} ${r.url.encodedPath}"
                if (r.url.encodedPath.endsWith("/copy")) copyCalls += r.url.encodedPath
                when {
                    r.url.encodedPath.endsWith("/files/old_deleted_id") ->
                        response(r, 404, """{"error":{"code":404,"message":"File not found","errors":[{"reason":"notFound"}]}}""")
                    // Folder search and workbook search both return empty: folder and workbook are gone from Drive
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[]}""")
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"recreated_folder_id","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"recreated_sheet_id","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                sheetCalls += "${r.method} ${r.url.encodedPath}"
                when {
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"recreated_sheet_id","sheets":[{"properties":{"sheetId":0,"title":"Sheet1"}}]}""")
                    else ->
                        response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":10}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)

        assertTrue("Expected Result.Success, was $res", res is Result.Success)
        assertEquals("recreated_sheet_id", (res as Result.Success).data)
        assertEquals("recreated_sheet_id", yearIds[year])
        assertEquals("recreated_sheet_id", legacySpreadsheetId)
        assertEquals("No copy call must happen", 0, copyCalls.size)
        assertTrue("Drive files search must be called", driveCalls.any { it.startsWith("GET") && it.endsWith("/files") })
        val createPosts = driveCalls.filter { it.startsWith("POST") && it.endsWith("/files") }
        assertTrue("Drive must create folder and spreadsheet", createPosts.size >= 2)
        assertTrue("Sheets batchUpdate must be called for provisioning", sheetCalls.any { it.contains("batchUpdate") })
    }

    @Test
    fun aFreshWorkbookReplacesTheOldAfterSignOutAndSignIn() = runBlocking {
        yearIds[year] = "pre_signout_old_id"
        legacySpreadsheetId = "pre_signout_old_id"
        rawPrefs[PreferencesRepository.keyLastWorkbookVerifiedDate(year)] = "2026-11-17"
        rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING] = "true"

        // 1. Sign out clears the per-account workbook state and markers
        yearIds.remove(year)
        legacySpreadsheetId = null
        rawPrefs.clear()

        val driveCalls = mutableListOf<String>()
        val copyCalls = mutableListOf<String>()

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                driveCalls += "${r.method} ${r.url.encodedPath}"
                if (r.url.encodedPath.endsWith("/copy")) copyCalls += r.url.encodedPath
                when {
                    r.method == "GET" && r.url.encodedPath.endsWith("/files") ->
                        response(r, 200, """{"files":[]}""")
                    r.method == "POST" && r.url.encodedPath.endsWith("/files") -> {
                        val buffer = okio.Buffer()
                        r.body?.writeTo(buffer)
                        val text = buffer.readUtf8()
                        if (text.contains("application/vnd.google-apps.folder")) {
                            response(r, 200, """{"id":"folder_post_signin","name":"Personal Finance"}""")
                        } else {
                            response(r, 200, """{"id":"sheet_post_signin","name":"Finance$year"}""")
                        }
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"sheet_post_signin","sheets":[{"properties":{"sheetId":0,"title":"Sheet1"}}]}""")
                    else ->
                        response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":10}""")
                }
            }).build(), tokens
        )

        // 2. Sign in again triggers resolution with forceValidateTabs = true
        val resolver = WorkbookResolver(drive, sheets, prefs)
        val res = resolver.resolveForYear(year, forceValidateTabs = true)

        assertTrue("Expected Result.Success, was $res", res is Result.Success)
        assertEquals("sheet_post_signin", (res as Result.Success).data)
        assertEquals("sheet_post_signin", yearIds[year])
        assertEquals("sheet_post_signin", legacySpreadsheetId)
        assertEquals("No copy call must happen", 0, copyCalls.size)
    }
}
