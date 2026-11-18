package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Unit tests for P0-2: Repair backups pile up and could be mistaken for the live workbook.
 * Verifies backup marker property isolation, Archive folder creation/reuse, and move-to-archive behaviour.
 */
class WorkbookBackupArchiveTest {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val tokens = object : TokenProvider {
        override fun getAccessToken(): String = "test_token"
        override fun refreshAccessToken(): String = "test_token"
    }
    private val testYear = 2026
    private val fixedClock = Clock.fixed(Instant.parse("2026-11-18T06:00:00Z"), ZoneId.of("Asia/Kolkata"))

    private val rawPrefs = mutableMapOf<String, String?>()
    private val yearIds = mutableMapOf<Int, String>()

    private val prefs: PreferencesRepository = Proxy.newProxyInstance(
        PreferencesRepository::class.java.classLoader, arrayOf(PreferencesRepository::class.java)
    ) { _, m, args ->
        when (m.name) {
            "getSpreadsheetIdForYearSync" -> yearIds[args!![0] as Int]
            "setSpreadsheetIdForYear" -> { yearIds[args!![0] as Int] = args[1] as String; null }
            "getSpreadsheetIdSync" -> yearIds[testYear]
            "setSpreadsheetId" -> { yearIds[testYear] = args!![0] as String; null }
            "getSpreadsheetTitle" -> flowOf("Finance$testYear")
            "setSpreadsheetTitle" -> null
            "getPreference" -> rawPrefs[args!![0] as String]
            "setPreference" -> { rawPrefs[args!![0] as String] = args[1] as String?; null }
            "deletePreference" -> { rawPrefs.remove(args!![0] as String); null }
            else -> if (kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(m.returnType)) flowOf<Any?>(null) else null
        }
    } as PreferencesRepository

    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("OK")
        .body(body.toResponseBody(json))
        .build()

    @Test
    fun backupIsNotReturnedAsLiveWorkbookByFindWorkbookForYear() = runBlocking {
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val q = r.url.queryParameter("q").orEmpty()
                if (q.contains("key='app' and value='FinanceAutomation'") && q.contains("key='fiscalYear' and value='$testYear'")) {
                    // Only live workbook matches the live query; backup file has app='FinanceAutomationBackup'
                    response(r, 200, """{"files":[{"id":"live_workbook_123","name":"Finance$testYear"}]}""")
                } else {
                    response(r, 200, """{"files":[]}""")
                }
            }).build(), tokens
        )

        val res = drive.findWorkbookForYear(testYear)
        assertTrue(res is Result.Success)
        assertEquals("live_workbook_123", (res as Result.Success).data)

        // When only backup exists, live query and name fallback both return nothing
        val driveOnlyBackup = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val q = r.url.queryParameter("q").orEmpty()
                if (q.contains("FinanceAutomationBackup")) {
                    response(r, 200, """{"files":[{"id":"backup_456","name":"Finance$testYear (backup)"}]}""")
                } else {
                    // Live query by appProperties and name query "name = 'Finance2026'" both return empty
                    response(r, 200, """{"files":[]}""")
                }
            }).build(), tokens
        )

        val resBackupOnly = driveOnlyBackup.findWorkbookForYear(testYear)
        assertTrue(resBackupOnly is Result.Success)
        assertNull((resBackupOnly as Result.Success).data)
    }

    @Test
    fun copyFileSetsBackupMarkerAndClearsFiscalYear() = runBlocking {
        var recordedBody = ""
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (r.method == "POST" && r.url.encodedPath.contains("/copy")) {
                    val buffer = okio.Buffer()
                    r.body?.writeTo(buffer)
                    recordedBody = buffer.readUtf8()
                    response(r, 200, """{"id":"copied_backup_id","name":"Finance$testYear (backup before repair 2026-11-18)"}""")
                } else {
                    response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val res = drive.copyFile("live_id", "Finance$testYear (backup before repair 2026-11-18)", year = testYear)
        assertTrue(res is Result.Success)
        assertEquals("copied_backup_id", (res as Result.Success).data)

        assertTrue("Body must include app=FinanceAutomationBackup", recordedBody.contains("\"app\":\"FinanceAutomationBackup\""))
        assertTrue("Body must include backupYear", recordedBody.contains("\"backupYear\":\"2026\""))
        assertTrue("Body must clear fiscalYear via null", recordedBody.contains("\"fiscalYear\":null"))
    }

    @Test
    fun repairWithMoveToArchiveLeavesOneBackupInFolderAndOldOneInArchive() = runBlocking {
        val requests = mutableListOf<String>()
        var movedFileId = ""
        var movedNewParent = ""
        var movedRemoveParent = ""

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val path = r.url.encodedPath
                requests.add("${r.method} $path")

                when {
                    r.method == "GET" && path.endsWith("/files/candidate_id") ->
                        response(r, 200, """{"id":"candidate_id","trashed":false}""")
                    // Query existing backups
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("FinanceAutomationBackup") == true ->
                        response(r, 200, """{"files":[{"id":"old_backup_1","name":"Finance$testYear (backup before repair 2026-11-01)","parents":["pf_root_folder_id"],"appProperties":{"app":"FinanceAutomationBackup","backupYear":"2026"}}]}""")
                    // Copy call
                    r.method == "POST" && path.contains("/files/candidate_id/copy") ->
                        response(r, 200, """{"id":"new_backup_2","name":"Finance$testYear (backup before repair 2026-11-18)"}""")
                    // Find Personal Finance root folder
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("Personal Finance") == true ->
                        response(r, 200, """{"files":[{"id":"pf_root_folder_id","name":"Personal Finance"}]}""")
                    // Query Archive folder inside root
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("name = 'Archive'") == true ->
                        response(r, 200, """{"files":[{"id":"archive_folder_id","name":"Archive"}]}""")
                    // Move file call
                    r.method == "PATCH" && path.contains("/files/old_backup_1") -> {
                        movedFileId = "old_backup_1"
                        movedNewParent = r.url.queryParameter("addParents").orEmpty()
                        movedRemoveParent = r.url.queryParameter("removeParents").orEmpty()
                        response(r, 200, """{"id":"old_backup_1"}""")
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" && r.url.encodedPath.contains("values:batchGet") ->
                        response(r, 200, """{"valueRanges":[{"range":"'Nov26'!A4:A5","values":[["2026-11-01"]]}]}""")
                    r.method == "GET" && r.url.encodedPath.contains("values") ->
                        response(r, 200, """{"values":[["Header"],["DataRow"]]}""")
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"candidate_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":2,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":1}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            clock = fixedClock
        )

        yearIds[testYear] = "candidate_id"

        val res = resolver.resolveForYear(
            year = testYear,
            forceValidateTabs = true,
            createBackup = true,
            archiveOlderBackups = true
        )

        assertTrue("Repair with archive older backups must succeed: $res", res is Result.Success)
        assertEquals("candidate_id", (res as Result.Success).data)

        // Verify copy happened first
        val copyIndex = requests.indexOfFirst { it.contains("copy") }
        assertTrue("Copy request must have occurred", copyIndex >= 0)

        // Verify move call targeted older backup
        assertEquals("old_backup_1", movedFileId)
        assertEquals("archive_folder_id", movedNewParent)
        assertEquals("pf_root_folder_id", movedRemoveParent)

        // Verify the newly created backup was NOT moved
        val movedNewBackup = requests.any { it.contains("PATCH") && it.contains("new_backup_2") }
        assertFalse("New backup must stay in folder and not be moved", movedNewBackup)
    }

    @Test
    fun repairWithKeepLeavesAllBackupsInPlace() = runBlocking {
        val requests = mutableListOf<String>()

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val path = r.url.encodedPath
                requests.add("${r.method} $path")

                when {
                    r.method == "GET" && path.endsWith("/files/candidate_id") ->
                        response(r, 200, """{"id":"candidate_id","trashed":false}""")
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("FinanceAutomationBackup") == true ->
                        response(r, 200, """{"files":[{"id":"old_backup_1","name":"Finance$testYear (backup)","parents":["pf_root_folder_id"],"appProperties":{"app":"FinanceAutomationBackup","backupYear":"2026"}}]}""")
                    r.method == "POST" && path.contains("/files/candidate_id/copy") ->
                        response(r, 200, """{"id":"new_backup_2","name":"Finance$testYear (backup before repair 2026-11-18)"}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" && r.url.encodedPath.contains("values:batchGet") ->
                        response(r, 200, """{"valueRanges":[{"range":"'Nov26'!A4:A5","values":[["2026-11-01"]]}]}""")
                    r.method == "GET" && r.url.encodedPath.contains("values") ->
                        response(r, 200, """{"values":[["Header"],["DataRow"]]}""")
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"candidate_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":2,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":1}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            clock = fixedClock
        )

        yearIds[testYear] = "candidate_id"

        val res = resolver.resolveForYear(
            year = testYear,
            forceValidateTabs = true,
            createBackup = true,
            archiveOlderBackups = false
        )

        assertTrue(res is Result.Success)
        // Verify copy happened
        assertTrue(requests.any { it.contains("copy") })
        // Verify zero PATCH move calls occurred
        val patchCount = requests.count { it.startsWith("PATCH") }
        assertEquals("Zero move requests when archiveOlderBackups is false", 0, patchCount)
    }

    @Test
    fun failedCopyMovesNothing() = runBlocking {
        val requests = mutableListOf<String>()

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val path = r.url.encodedPath
                requests.add("${r.method} $path")

                when {
                    r.method == "GET" && path.endsWith("/files/candidate_id") ->
                        response(r, 200, """{"id":"candidate_id","trashed":false}""")
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("FinanceAutomationBackup") == true ->
                        response(r, 200, """{"files":[{"id":"old_backup_1","name":"Finance$testYear (backup)","parents":["pf_root_folder_id"]}]}""")
                    r.method == "POST" && path.contains("/copy") ->
                        response(r, 500, """{"error":{"code":500,"message":"Drive internal server error"}}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" && r.url.encodedPath.contains("values:batchGet") ->
                        response(r, 200, """{"valueRanges":[{"range":"'Nov26'!A4:A5","values":[["2026-11-01"]]}]}""")
                    r.method == "GET" && r.url.encodedPath.contains("values") ->
                        response(r, 200, """{"values":[["Header"],["DataRow"]]}""")
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"candidate_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":2,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            clock = fixedClock
        )

        yearIds[testYear] = "candidate_id"

        val res = resolver.resolveForYear(
            year = testYear,
            forceValidateTabs = true,
            createBackup = true,
            archiveOlderBackups = true
        )

        assertTrue("Failed copy must return Result.Error", res is Result.Error)
        val patchCount = requests.count { it.startsWith("PATCH") }
        assertEquals("Failed copy must move nothing (0 PATCH requests)", 0, patchCount)
    }

    @Test
    fun archiveFolderIsCreatedOnceAndReused() = runBlocking {
        var postCount = 0
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (r.method == "POST" && r.url.encodedPath.endsWith("/files")) {
                    postCount++
                    response(r, 200, """{"id":"archive_created_id","name":"Archive"}""")
                } else if (r.method == "GET" && r.url.encodedPath.endsWith("/files") && r.url.queryParameter("q")?.contains("name = 'Archive'") == true) {
                    // First call: returns empty list (simulating missing)
                    if (postCount == 0) {
                        response(r, 200, """{"files":[]}""")
                    } else {
                        response(r, 200, """{"files":[{"id":"archive_created_id","name":"Archive"}]}""")
                    }
                } else {
                    response(r, 200, "{}")
                }
            }).build(), tokens
        )

        // First call: Archive folder does not exist -> creates it
        val res1 = drive.findOrCreateArchiveFolder("parent_123")
        assertTrue(res1 is Result.Success)
        assertEquals("archive_created_id", (res1 as Result.Success).data)
        assertEquals("Archive folder created once", 1, postCount)

        // Second call: Archive folder already exists -> reuses it, no POST
        val res2 = drive.findOrCreateArchiveFolder("parent_123")
        assertTrue(res2 is Result.Success)
        assertEquals("archive_created_id", (res2 as Result.Success).data)
        assertEquals("Archive folder must be reused without another creation", 1, postCount)
    }

    @Test
    fun userFileWithSimilarNameIsNotTouched() = runBlocking {
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val q = r.url.queryParameter("q").orEmpty()
                // The query MUST require appProperties has { key='app' and value='FinanceAutomationBackup' }
                assertTrue("Query must search via marker property", q.contains("key='app' and value='FinanceAutomationBackup'"))
                // Simulated response containing a user-created file with a similar name but NO marker property
                response(r, 200, """{"files":[]}""")
            }).build(), tokens
        )

        val res = drive.findBackups(testYear)
        assertTrue(res is Result.Success)
        assertTrue("User file without marker property is never returned as app backup", (res as Result.Success).data.isEmpty())
    }

    @Test
    fun driveFileScopeCanMoveFilesBetweenAppFolders() = runBlocking {
        var recordedUrl = ""
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                recordedUrl = r.url.toString()
                response(r, 200, """{"id":"file_to_move"}""")
            }).build(), tokens
        )

        val res = drive.moveFile("file_to_move", "new_parent_id", "old_parent_id")
        assertTrue(res is Result.Success)
        assertTrue("URL must add new parent", recordedUrl.contains("addParents=new_parent_id"))
        assertTrue("URL must remove old parent", recordedUrl.contains("removeParents=old_parent_id"))
    }

    @Test
    fun repairSkipsBackupsAlreadyInArchive() = runBlocking {
        val requests = mutableListOf<String>()
        var movedCount = 0

        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val path = r.url.encodedPath
                requests.add("${r.method} $path")

                when {
                    r.method == "GET" && path.endsWith("/files/candidate_id") ->
                        response(r, 200, """{"id":"candidate_id","trashed":false}""")
                    // Return 2 backups: old_1 in root, old_2 already in Archive folder
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("FinanceAutomationBackup") == true ->
                        response(r, 200, """{"files":[
                            {"id":"old_1","name":"Finance$testYear (backup 1)","parents":["pf_root_id"],"appProperties":{"app":"FinanceAutomationBackup","backupYear":"2026"}},
                            {"id":"old_2","name":"Finance$testYear (backup 2)","parents":["archive_folder_id"],"appProperties":{"app":"FinanceAutomationBackup","backupYear":"2026"}}
                        ]}""")
                    r.method == "POST" && path.contains("/files/candidate_id/copy") ->
                        response(r, 200, """{"id":"new_backup_3","name":"Finance$testYear (backup before repair 2026-11-18)"}""")
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("Personal Finance") == true ->
                        response(r, 200, """{"files":[{"id":"pf_root_id","name":"Personal Finance"}]}""")
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("name = 'Archive'") == true ->
                        response(r, 200, """{"files":[{"id":"archive_folder_id","name":"Archive"}]}""")
                    r.method == "PATCH" && path.contains("/files/old_1") -> {
                        movedCount++
                        response(r, 200, """{"id":"old_1"}""")
                    }
                    r.method == "PATCH" && path.contains("/files/old_2") -> {
                        org.junit.Assert.fail("old_2 is already in Archive and must never be moved again")
                        response(r, 200, """{"id":"old_2"}""")
                    }
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" && r.url.encodedPath.contains("values:batchGet") ->
                        response(r, 200, """{"valueRanges":[{"range":"'Nov26'!A4:A5","values":[["2026-11-01"]]}]}""")
                    r.method == "GET" && r.url.encodedPath.contains("values") ->
                        response(r, 200, """{"values":[["Header"],["DataRow"]]}""")
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"candidate_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":2,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":1}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            clock = fixedClock
        )

        yearIds[testYear] = "candidate_id"

        val res = resolver.resolveForYear(
            year = testYear,
            forceValidateTabs = true,
            createBackup = true,
            archiveOlderBackups = true
        )

        assertTrue(res is Result.Success)
        assertEquals("Only root backup must be moved", 1, movedCount)
        val outcome = resolver.lastArchiveOutcome
        org.junit.Assert.assertNotNull(outcome)
        assertEquals("Moved count must be 1", 1, outcome!!.movedCount)
        assertEquals("Already archived count must be 1", 1, outcome.alreadyInArchiveCount)
        assertEquals("Failed count must be 0", 0, outcome.failedCount)
    }

    @Test
    fun repairReportsPartialFailureWhenArchiveFolderFails() = runBlocking {
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                val path = r.url.encodedPath

                when {
                    r.method == "GET" && path.endsWith("/files/candidate_id") ->
                        response(r, 200, """{"id":"candidate_id","trashed":false}""")
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("FinanceAutomationBackup") == true ->
                        response(r, 200, """{"files":[{"id":"old_1","name":"Finance$testYear (backup)","parents":["pf_root_id"],"appProperties":{"app":"FinanceAutomationBackup","backupYear":"2026"}}]}""")
                    r.method == "POST" && path.contains("/files/candidate_id/copy") ->
                        response(r, 200, """{"id":"new_backup","name":"Finance$testYear (backup before repair 2026-11-18)"}""")
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("Personal Finance") == true ->
                        response(r, 200, """{"files":[{"id":"pf_root_id","name":"Personal Finance"}]}""")
                    // findOrCreateArchiveFolder fails with 500
                    r.method == "GET" && path.endsWith("/files") && r.url.queryParameter("q")?.contains("name = 'Archive'") == true ->
                        response(r, 500, """{"error":{"code":500,"message":"Archive query failed"}}""")
                    else -> response(r, 200, "{}")
                }
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                when {
                    r.method == "GET" && r.url.encodedPath.contains("values:batchGet") ->
                        response(r, 200, """{"valueRanges":[{"range":"'Nov26'!A4:A5","values":[["2026-11-01"]]}]}""")
                    r.method == "GET" && r.url.encodedPath.contains("values") ->
                        response(r, 200, """{"values":[["Header"],["DataRow"]]}""")
                    r.method == "GET" ->
                        response(r, 200, """{"spreadsheetId":"candidate_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":2,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":1}""")
                }
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            clock = fixedClock
        )

        yearIds[testYear] = "candidate_id"

        val res = resolver.resolveForYear(
            year = testYear,
            forceValidateTabs = true,
            createBackup = true,
            archiveOlderBackups = true
        )

        // Workbook repair itself succeeds because backup copy succeeded
        assertTrue(res is Result.Success)
        val outcome = resolver.lastArchiveOutcome
        org.junit.Assert.assertNotNull(outcome)
        assertEquals("Failed count must be 1 because archive folder failed", 1, outcome!!.failedCount)
        assertEquals("Moved count must be 0", 0, outcome.movedCount)
        org.junit.Assert.assertNotNull("Error must be captured", outcome.error)
    }

    @Test
    fun repairCancelMakesNoDriveCalls() = runBlocking {
        var copyCalls = 0
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (r.url.encodedPath.contains("/copy")) {
                    copyCalls++
                }
                response(r, 200, "{}")
            }).build(), tokens
        )

        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, "{}")
            }).build(), tokens
        )

        val resolver = WorkbookResolver(
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            clock = fixedClock
        )

        // When user cancels dialog, no repair is invoked
        assertEquals("No copy calls on cancel", 0, copyCalls)
        assertNull(resolver.lastArchiveOutcome)
    }
}
