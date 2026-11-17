package com.financeautomation.data.remote

import com.financeautomation.R
import com.financeautomation.core.model.Result
import com.financeautomation.data.account.AccountDataStore
import com.financeautomation.data.account.AccountSwitchGuard
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import com.financeautomation.presentation.viewmodel.SettingsSyncCoordinator
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import com.financeautomation.testutil.SyncHarness
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbookReplacedNoticeTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private class TestPreferenceDao(val map: ConcurrentHashMap<String, String>) : PreferenceDao {
        override fun getPreferenceFlow(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun getPreference(key: String): String? = map[key]
        override suspend fun getAllPreferences(): List<PreferenceEntity> = map.map { PreferenceEntity(it.key, it.value) }
        override suspend fun setPreference(entity: PreferenceEntity) {
            if (entity.value != null) map[entity.key] = entity.value!! else map.remove(entity.key)
        }
        override suspend fun deletePreference(key: String) { map.remove(key) }
        override suspend fun deletePreferencesWithPrefix(prefix: String) {
            map.keys.filter { it.startsWith(prefix) }.forEach { map.remove(it) }
        }
        override suspend fun clearAll() { map.clear() }
        override suspend fun getDirtyPreferences(): List<PreferenceEntity> = emptyList()
        override suspend fun markClean(keys: List<String>) {}
    }

    private class FakeCategoryRepo : CategoryRepository {
        override fun getCategories(includeArchived: Boolean): Flow<List<Category>> = flowOf(emptyList())
        override suspend fun getAllCategories(includeArchived: Boolean): List<Category> = emptyList()
        override suspend fun getActiveCategories(): List<Category> = emptyList()
        override suspend fun getCategoryById(id: String): Category? = null
        override suspend fun getCategoryByName(name: String): Category? = null
        override suspend fun insertCategory(category: Category) {}
        override suspend fun updateCategory(category: Category) {}
        override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean) {}
        override suspend fun archiveCategory(id: String) {}
        override suspend fun unarchiveCategory(id: String) {}
        override suspend fun deleteCategory(id: String) {}
        override suspend fun reorderCategories(categoryIds: List<String>) {}
    }

    @Test
    fun noticeSurvivesProcessDeath() = runBlocking {
        val backingStorage = ConcurrentHashMap<String, String>()
        val dao = TestPreferenceDao(backingStorage)

        // Process 1 writes the notice pending flag
        val process1Prefs = PreferencesRepositoryImpl(preferenceDao = dao, ioDispatcher = Dispatchers.Unconfined)
        process1Prefs.setPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING, "true", isDirty = false)
        assertEquals("true", process1Prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING))

        // Process 2 starts up fresh with new repository instance over same persistent DAO
        val process2Prefs = PreferencesRepositoryImpl(preferenceDao = dao, ioDispatcher = Dispatchers.Unconfined)
        assertEquals("true", process2Prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING))
    }

    @Test
    fun noticeIsShownOnlyOnce() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pRepo = PreferencesRepositoryImpl(isTestHeadless = true, ioDispatcher = testDispatcher)
        pRepo.setPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING, "true")

        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            preferencesRepository = pRepo,
            accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
            ioDispatcher = testDispatcher
        )

        // First ViewModel launch consumes notice and clears pending key
        val vm1 = DashboardViewModel(
            transactionRepository = repo,
            directSyncQueueDao = qDao,
            preferencesRepository = pRepo,
            categoryRepository = FakeCategoryRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val notice1 = vm1.uiState.value.syncMessage
        assertNotNull("First run must display replacement notice", notice1)
        assertEquals(R.string.workbook_replaced_notice_partial, notice1?.resId)
        assertNull("Pending preference must be cleared after display",
            pRepo.getPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING))

        // Second ViewModel launch observes no notice
        val vm2 = DashboardViewModel(
            transactionRepository = repo,
            directSyncQueueDao = qDao,
            preferencesRepository = pRepo,
            categoryRepository = FakeCategoryRepo(),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertNull("Second run must not show replacement notice again", vm2.uiState.value.syncMessage)
    }

    @Test
    fun noticeIsClearedOnAccountSwitch() = runBlocking {
        val harness = SyncHarness()
        harness.preferences[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING] = "true"
        harness.preferences[PreferencesRepository.keyLastWorkbookVerifiedDate(2026)] = "2026-11-17"
        harness.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER] = "old@example.com"

        val store = object : AccountDataStore {
            override suspend fun pendingCount() = 0
            override suspend fun hasLocalData() = false
            override suspend fun wipe() {}
        }
        val guard = AccountSwitchGuard(harness.prefs, store, {}, {})
        guard.switchTo("new@example.com")

        assertNull("Notice pending must be cleared on account switch",
            harness.preferences[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])
        assertNull("Verified date markers must be cleared on account switch",
            harness.preferences[PreferencesRepository.keyLastWorkbookVerifiedDate(2026)])
    }

    @Test
    fun noticeIsClearedOnSignOut() = runBlocking {
        val harness = SyncHarness()
        harness.preferences[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING] = "true"
        harness.preferences[PreferencesRepository.keyLastWorkbookVerifiedDate(2026)] = "2026-11-17"
        harness.googleEmail = "user@example.com"

        val fakeTokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }
        val http = OkHttpClient.Builder().build()
        val driveClient = GoogleDriveClient(http, fakeTokenProvider)
        val sheetsClient = GoogleSheetsClient(http, fakeTokenProvider)

        val uiState = MutableStateFlow(SettingsUiState())
        val coordinator = SettingsSyncCoordinator(
            preferencesRepository = harness.prefs,
            transactionRepository = harness.repo,
            syncConfigUseCase = null,
            regenerateDashboardUseCase = null,
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            googleAuthTokenProvider = null,
            workbookResolver = null,
            accountRepository = null,
            categoryRepository = null,
            scope = this,
            uiState = uiState,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.onGoogleSignOut()

        assertNull("Notice pending must be cleared on sign out",
            harness.preferences[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])
        assertNull("Verified date markers must be cleared on sign out",
            harness.preferences[PreferencesRepository.keyLastWorkbookVerifiedDate(2026)])
    }

    @Test
    fun workbookResolverSetsTheFlagOnlyForMissingOrTrashedReplacement() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneId.of("Asia/Kolkata"))
        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }

        val storedId = "stored_wb_id"
        val foundReplacementId = "found_in_drive_wb_id"

        var driveIsUsable = true
        var driveSearchFinds = false

        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when {
                    url.contains("/files/$storedId") -> {
                        val body = """{"id": "$storedId", "name": "Finance2026", "trashed": ${!driveIsUsable}}"""
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody(jsonMediaType))
                            .build()
                    }
                    url.contains("/files?") && request.method == "GET" -> {
                        val body = if (driveSearchFinds) {
                            """{"files": [{"id": "$foundReplacementId", "name": "Finance2026"}]}"""
                        } else {
                            """{"files": []}"""
                        }
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody(jsonMediaType))
                            .build()
                    }
                    request.method == "POST" && url.contains("/files") -> {
                        val body = """{"id": "new_created_wb_id", "name": "Finance2026"}"""
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
            tokenProvider
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
            tokenProvider
        )

        val rawPrefs = mutableMapOf<String, String?>()
        val prefs = object : PreferencesRepository {
            override fun getSpreadsheetId(): Flow<String?> = flowOf(rawPrefs[PreferencesRepository.KEY_SPREADSHEET_ID])
            override suspend fun getSpreadsheetIdSync(): String? = rawPrefs[PreferencesRepository.KEY_SPREADSHEET_ID]
            override suspend fun setSpreadsheetId(id: String) { rawPrefs[PreferencesRepository.KEY_SPREADSHEET_ID] = id }
            override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(year)])
            override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(year)]
            override suspend fun setSpreadsheetIdForYear(year: Int, id: String) { rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(year)] = id }
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
            override suspend fun getOrCreateDeviceId(): String = "device-1"
            override suspend fun getDeviceIdSync(): String? = "device-1"
            override fun getDeveloperMode(): Flow<Boolean> = flowOf(false)
            override suspend fun getDeveloperModeSync(): Boolean = false
            override suspend fun setDeveloperMode(enabled: Boolean) {}
            override fun getThemeMode(): Flow<com.financeautomation.domain.model.ThemeMode> = flowOf(com.financeautomation.domain.model.ThemeMode.SYSTEM)
            override suspend fun getThemeModeSync(): com.financeautomation.domain.model.ThemeMode = com.financeautomation.domain.model.ThemeMode.SYSTEM
            override suspend fun setThemeMode(mode: com.financeautomation.domain.model.ThemeMode) {}
            override suspend fun getPreference(key: String): String? = rawPrefs[key]
            override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {
                if (value != null) rawPrefs[key] = value else rawPrefs.remove(key)
            }
            override suspend fun deletePreference(key: String) { rawPrefs.remove(key) }
            override suspend fun clearKeysWithPrefix(prefix: String) {
                rawPrefs.keys.filter { it.startsWith(prefix) }.toList().forEach { rawPrefs.remove(it) }
            }
        }

        val resolver = WorkbookResolver(
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.Unconfined,
            clock = clock
        )

        // 1. Healthy workbook resolve: isFileUsable is true -> flag must NOT be set
        driveIsUsable = true
        rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(2026)] = storedId
        rawPrefs[PreferencesRepository.KEY_SPREADSHEET_ID] = storedId
        val resHealthy = resolver.resolveForYear(2026)
        assertTrue(resHealthy is Result.Success)
        assertNull("Healthy resolve must NOT set notice pending flag",
            rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])

        // 2. createFreshWorkbook -> flag must NOT be set
        val resFresh = resolver.createFreshWorkbook(2026)
        assertTrue(resFresh is Result.Success)
        assertNull("createFreshWorkbook must NOT set notice pending flag",
            rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])

        // 3. Trashed stored workbook: isFileUsable is false -> replaced -> flag MUST be set
        driveIsUsable = false
        driveSearchFinds = true
        rawPrefs[PreferencesRepository.keySpreadsheetIdForYear(2026)] = storedId
        rawPrefs[PreferencesRepository.KEY_SPREADSHEET_ID] = storedId
        // Force resolution so daily cache doesn't skip
        val resTrashed = resolver.resolveForYear(2026, forceValidateTabs = true)
        assertTrue(resTrashed is Result.Success)
        assertEquals("true", rawPrefs[PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING])
    }
}
