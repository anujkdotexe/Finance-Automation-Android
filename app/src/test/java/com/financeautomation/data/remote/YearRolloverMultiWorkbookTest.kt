package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.CarryForwardRequest
import com.financeautomation.testutil.InMemoryLineageDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class YearRolloverMultiWorkbookTest {

    private class TestPreferenceDao : PreferenceDao {
        val table = ConcurrentHashMap<String, PreferenceEntity>()
        override fun getPreferenceFlow(key: String): Flow<String?> = flowOf(table[key]?.value)
        override suspend fun getPreference(key: String): String? = table[key]?.value
        override suspend fun getAllPreferences(): List<PreferenceEntity> = table.values.toList()
        override suspend fun setPreference(entity: PreferenceEntity) { table[entity.key] = entity }
        override suspend fun deletePreference(key: String) { table.remove(key) }
        override suspend fun deletePreferencesWithPrefix(prefix: String) {
            table.keys.filter { it.startsWith(prefix) }.forEach { table.remove(it) }
        }
        override suspend fun clearAll() { table.clear() }
        override suspend fun getDirtyPreferences(): List<PreferenceEntity> = table.values.filter { it.dirty == 1 }
        override suspend fun markClean(keys: List<String>) {
            keys.forEach { key -> table[key]?.let { table[key] = it.copy(dirty = 0) } }
        }
    }

    private class MutableClock(
        private var currentInstant: Instant,
        private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    ) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)
        override fun instant(): Instant = currentInstant
        fun setInstant(newInstant: Instant) { currentInstant = newInstant }
    }

    @Test
    fun yearRolloverCreatesSeparateWorkbookFor2027WithoutOverwriting2026() = runBlocking {
        val kolkata = ZoneId.of("Asia/Kolkata")
        // Start on 31 Dec 2026, 23:59:00 IST
        val instant2026 = Instant.parse("2026-12-31T18:29:00Z") // 23:59 IST
        val clock = MutableClock(instant2026, kolkata)

        val prefDao = TestPreferenceDao()
        val prefsRepo = PreferencesRepositoryImpl(
            preferenceDao = prefDao,
            clock = clock
        )

        // Store 2026 workbook ID
        prefsRepo.setSpreadsheetIdForYear(2026, "sheet_finance_2026")
        assertEquals("sheet_finance_2026", prefsRepo.getSpreadsheetIdSync())
        assertEquals("sheet_finance_2026", prefsRepo.getSpreadsheetIdForYearSync(2026))

        // Tick clock to 1 Jan 2027, 00:01:00 IST
        val instant2027 = Instant.parse("2027-01-01T18:31:00Z") // 00:01 IST on 1 Jan 2027
        clock.setInstant(instant2027)

        // On 1 Jan 2027, year 2027 MUST NOT fall back to 2026's spreadsheet ID!
        val stored2027Id = prefsRepo.getSpreadsheetIdForYearSync(2027)
        assertNull("2027 spreadsheet ID must be null before creation, never falling back to 2026 ID", stored2027Id)

        // Setting a past year's ID (e.g. 2025) must NOT overwrite global/active spreadsheet ID
        prefsRepo.setSpreadsheetIdForYear(2025, "sheet_finance_2025")
        assertEquals("sheet_finance_2025", prefsRepo.getSpreadsheetIdForYearSync(2025))
        assertNotEquals("Setting 2025 ID must not overwrite active 2026/2027 ID", "sheet_finance_2025", prefsRepo.getSpreadsheetIdSync())

        // Setting current year's ID (2027) updates active spreadsheet ID
        prefsRepo.setSpreadsheetIdForYear(2027, "sheet_finance_2027")
        assertEquals("sheet_finance_2027", prefsRepo.getSpreadsheetIdForYearSync(2027))
        assertEquals("sheet_finance_2027", prefsRepo.getSpreadsheetIdSync())
        // And 2026 ID remains completely intact
        assertEquals("sheet_finance_2026", prefsRepo.getSpreadsheetIdForYearSync(2026))
    }

    @Test
    fun crossYearCarryForwardReadsDec26FromPreviousWorkbookAndAppendsToJan27() = runBlocking {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val instant2027 = Instant.parse("2027-01-01T18:31:00Z") // 1 Jan 2027 IST
        val clock = MutableClock(instant2027, kolkata)

        val prefDao = TestPreferenceDao()
        val prefsRepo = PreferencesRepositoryImpl(
            preferenceDao = prefDao,
            clock = clock
        )
        prefsRepo.setSpreadsheetIdForYear(2026, "sheet_finance_2026")
        prefsRepo.setSpreadsheetIdForYear(2027, "sheet_finance_2027")

        val recordedRequests = mutableListOf<String>()

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            recordedRequests.add("${req.method} $url")

            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val body = when {
                // Metadata for 2026 workbook (contains Dec26)
                req.method == "GET" && url.contains("sheet_finance_2026") && !url.contains("/values") -> {
                    """{"spreadsheetId":"sheet_finance_2026","sheets":[{"properties":{"sheetId":0,"title":"Dec26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}"""
                }
                // Metadata for 2027 workbook (contains Jan27)
                req.method == "GET" && url.contains("sheet_finance_2027") && !url.contains("/values") -> {
                    """{"spreadsheetId":"sheet_finance_2027","sheets":[{"properties":{"sheetId":0,"title":"Jan27","gridProperties":{"rowCount":1000,"columnCount":35}}}]}"""
                }
                // Batch get for Dec26 in 2026 workbook with an unsettled pending debit
                req.method == "GET" && url.contains("sheet_finance_2026") && url.contains("values:batchGet") -> {
                    """{"valueRanges":[
                        {"range":"'Dec26'!A4:K","majorDimension":"ROWS","values":[["31/12/26","23:59:00","HDFC","Dinner","","Food & Drink",1000,"Alice",500,500,"Pending"]]},
                        {"range":"'Dec26'!AC4:AC","majorDimension":"ROWS","values":[["debit_dec26_uuid"]]},
                        {"range":"'Dec26'!M4:T","majorDimension":"ROWS","values":[]},
                        {"range":"'Dec26'!AD4:AD","majorDimension":"ROWS","values":[]},
                        {"range":"'Dec26'!V4:AB","majorDimension":"ROWS","values":[]},
                        {"range":"'Dec26'!AE4:AE","majorDimension":"ROWS","values":[]}
                    ]}"""
                }
                // Batch get for Jan27 in 2027 workbook (empty)
                req.method == "GET" && url.contains("sheet_finance_2027") && url.contains("values:batchGet") -> {
                    """{"valueRanges":[
                        {"range":"'Jan27'!A4:K","majorDimension":"ROWS","values":[]},
                        {"range":"'Jan27'!AC4:AC","majorDimension":"ROWS","values":[]},
                        {"range":"'Jan27'!M4:T","majorDimension":"ROWS","values":[]},
                        {"range":"'Jan27'!AD4:AD","majorDimension":"ROWS","values":[]},
                        {"range":"'Jan27'!V4:AB","majorDimension":"ROWS","values":[]},
                        {"range":"'Jan27'!AE4:AE","majorDimension":"ROWS","values":[]}
                    ]}"""
                }
                // Check if uuid exists (findRowByUuid) -> returns empty
                req.method == "GET" && url.contains("/values/") && url.contains("AC") -> {
                    """{"range":"'Jan27'!AC3:AC","majorDimension":"ROWS","values":[]}"""
                }
                // Append debit to Jan27 in 2027 workbook
                req.method == "POST" && url.contains(":append") -> {
                    """{"spreadsheetId":"sheet_finance_2027","updates":{"updatedRange":"'Jan27'!A4:K4","updatedCells":11}}"""
                }
                // Batch update values (status edit in Dec26 of 2026 workbook)
                req.method == "POST" && url.contains("values:batchUpdate") -> {
                    """{"totalUpdatedCells":1,"responses":[]}"""
                }
                // General batchUpdate (e.g. create tab if needed)
                req.method == "POST" && url.contains("batchUpdate") -> {
                    """{"replies":[{"addSheet":{"properties":{"sheetId":1,"title":"Jan27"}}}]}"""
                }
                else -> "{}"
            }

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(jsonMediaType))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "tok"
            override fun refreshAccessToken(): String = "tok"
        }
        val sheetsClient = GoogleSheetsClient(okHttpClient, tokenProvider)
        val driveClient = GoogleDriveClient(okHttpClient, tokenProvider)

        val queueDao = InMemoryQueueDao()
        val lineageDao = InMemoryLineageDao()
        val txnDao = InMemoryTransactionDao()

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheetsClient,
            googleDriveClient = driveClient,
            syncQueueDao = queueDao,
            lineageDao = lineageDao,
            transactionDao = txnDao,
            previousWorkbookIdProvider = { year -> prefsRepo.getSpreadsheetIdForYearSync(year) }
        )

        val cfReq = CarryForwardRequest(
            targetTab = "Jan27",
            sourceTab = null,
            accounts = listOf("HDFC"),
            categories = listOf("Food & Drink")
        )
        val queueItem = SyncQueueEntity(
            id = 1L,
            transactionId = "cf_test_1",
            operationType = SyncQueueEntity.OP_CARRY_FORWARD,
            payloadJson = AccountingRequestJsonAdapter.carryForwardRequestToJson(cfReq)
        )

        val result = executor.executeCarryForward(queueItem, "sheet_finance_2027")
        println("executeCarryForward result: $result")
        println("recordedRequests: $recordedRequests")
        assertTrue("executeCarryForward must succeed across year boundary: $result", result is Result.Success)

        // Verifications:
        // 1. Appends/updates MUST target 2027 workbook for Jan27
        assertTrue(recordedRequests.any { it.contains("POST") && it.contains("sheet_finance_2027") && it.contains("values:batchUpdate") })

        // 2. Status edits (marking origin Dec26 row as Carried) MUST target 2026 workbook!
        assertTrue(recordedRequests.any { it.contains("POST") && it.contains("sheet_finance_2026") && it.contains("values:batchUpdate") })
    }

    @Test
    fun newWorkbookCreationCopiesConfigMirrorToNewWorkbook() = runBlocking {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val instant2027 = Instant.parse("2027-01-01T18:31:00Z")
        val clock = MutableClock(instant2027, kolkata)

        val prefDao = TestPreferenceDao()
        val prefsRepo = PreferencesRepositoryImpl(
            preferenceDao = prefDao,
            clock = clock
        )

        var mirrorTargetSpreadsheetId: String? = null
        var mirrorCalled = false

        // Mock dependencies for WorkbookResolver
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val body = when {
                url.contains("/files?q=") -> """{"files":[]}"""
                req.method == "POST" && url.contains("/files") -> """{"id":"sheet_finance_2027"}"""
                req.method == "POST" && url.contains(":batchUpdate") -> """{"replies":[{"addSheet":{"properties":{"sheetId":0,"title":"Dashboard"}}}]}"""
                req.method == "GET" && url.contains("/values/") -> """{"values":[["Header"]]}"""
                else -> "{}"
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(jsonMediaType))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "tok"
            override fun refreshAccessToken(): String = "tok"
        }
        val driveClient = GoogleDriveClient(okHttpClient, tokenProvider)
        val sheetsClient = GoogleSheetsClient(okHttpClient, tokenProvider)

        val fakeLocalGateway = object : com.financeautomation.domain.repository.ConfigSyncLocalGateway {
            override suspend fun markAllLocalItemsDirty() {}
            override suspend fun getDirtySnapshot(): com.financeautomation.domain.repository.DirtyConfigSnapshot =
                com.financeautomation.domain.repository.DirtyConfigSnapshot(
                    accounts = listOf(com.financeautomation.domain.model.Account("a1", "HDFC", isDefault = true, isArchived = false, displayOrder = 0)),
                    categories = listOf(com.financeautomation.domain.model.Category("c1", "Food & Drink", isExcludedFromLivingBurn = false, isDefault = true, isArchived = false, displayOrder = 0)),
                    companions = listOf(com.financeautomation.domain.model.Companion("p1", "Rahul", isArchived = false)),
                    tripTags = emptyList(),
                    preferences = emptyList()
                )
            override suspend fun markAccountsClean(ids: List<String>) {}
            override suspend fun markCategoriesClean(ids: List<String>) {}
            override suspend fun markCompanionsClean(ids: List<String>) {}
            override suspend fun markTripTagsClean(ids: List<String>) {}
            override suspend fun markPreferencesClean(keys: List<String>) {}
            override suspend fun markSubscriptionsClean(ids: List<String>) {}
            override suspend fun markBudgetsClean(categories: List<String>) {}
            override suspend fun applyRemoteAccount(name: String, lastFour: String?, isDefault: Boolean, isArchived: Boolean, displayOrder: Int, updatedAt: Long, deleted: Boolean) {}
            override suspend fun applyRemoteCategory(name: String, excludedFromBurn: Boolean, isDefault: Boolean, isArchived: Boolean, displayOrder: Int, updatedAt: Long, deleted: Boolean) {}
            override suspend fun applyRemoteCompanion(name: String, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
            override suspend fun applyRemoteTripTag(name: String, isActive: Boolean, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
            override suspend fun applyRemotePreference(key: String, value: String?, updatedAt: Long, deleted: Boolean) {}
            override suspend fun applyRemoteBudget(category: String, monthlyAmount: Double, updatedAt: Long, deleted: Boolean) {}
            override suspend fun applyRemoteSubscription(subscription: com.financeautomation.domain.model.Subscription, updatedAt: Long, deleted: Boolean) {}
        }

        val fakeConfigRepo = object : com.financeautomation.domain.repository.ConfigRepository {
            override suspend fun readAll(spreadsheetId: String): Result<List<com.financeautomation.domain.model.ConfigRow>> =
                Result.Success(emptyList())
            override suspend fun upsert(spreadsheetId: String, rows: List<com.financeautomation.domain.model.ConfigRow>): Result<Unit> {
                mirrorTargetSpreadsheetId = spreadsheetId
                mirrorCalled = true
                return Result.Success(Unit)
            }
            override suspend fun tombstone(spreadsheetId: String, type: String, key: String, deviceId: String): Result<Unit> = Result.Success(Unit)
        }

        val syncConfigUseCase = com.financeautomation.domain.usecase.SyncConfigUseCase(
            configRepository = fakeConfigRepo,
            localGateway = fakeLocalGateway,
            preferencesRepository = prefsRepo
        )

        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefsRepo,
            syncConfigUseCase = syncConfigUseCase,
            clock = clock
        )

        val result = resolver.resolveForYear(2027)
        assertTrue("Workbook resolution for 2027 must succeed: $result", result is Result.Success)
        assertEquals("sheet_finance_2027", (result as Result.Success).data)

        // Verify that Config mirror was populated into the newly created 2027 workbook
        assertTrue("mirrorToCloud must be called when creating new workbook", mirrorCalled)
        assertEquals("sheet_finance_2027", mirrorTargetSpreadsheetId)
        assertEquals("sheet_finance_2027", prefsRepo.getSpreadsheetIdForYearSync(2027))
    }
}
