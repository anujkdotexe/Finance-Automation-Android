package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.data.remote.GoogleApiError
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsApiException
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {

    private val fakeTokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "test_token"
        override fun refreshAccessToken(): String = "test_token"
    }

    private class FakePreferencesRepository : PreferencesRepository {
        var email: String? = "test@example.com"
        var activeSpreadsheetId: String? = "sheet_123"
        val yearMap = mutableMapOf<Int, String>()

        override fun getSpreadsheetId(): Flow<String?> = flowOf(activeSpreadsheetId)
        override suspend fun getSpreadsheetIdSync(): String? = activeSpreadsheetId
        override suspend fun setSpreadsheetId(id: String) {
            activeSpreadsheetId = id
        }

        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(yearMap[year])
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = yearMap[year]
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {
            yearMap[year] = id
        }

        override fun getDefaultAccount(): Flow<String> = flowOf("SBI")
        override suspend fun getDefaultAccountSync(): String = "SBI"
        override suspend fun setDefaultAccount(account: String) {}

        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(trip: String?) {}

        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(email)
        override suspend fun getGoogleAccountEmailSync(): String? = email
        override suspend fun setGoogleAccountEmail(email: String?) {
            this.email = email
        }

        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(60)
        override suspend fun getSettleDelaySecondsSync(): Int = 60
        override suspend fun setSettleDelaySeconds(seconds: Int) {}

        override suspend fun getOrCreateDeviceId(): String = "test_device_id"
        override suspend fun getDeviceIdSync(): String? = "test_device_id"
        override suspend fun getPreference(key: String): String? = null
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {}
        override suspend fun deletePreference(key: String) {}
    }

    private class FakeWorkbookResolver(
        driveClient: GoogleDriveClient,
        sheetsClient: GoogleSheetsClient,
        prefs: PreferencesRepository
    ) : WorkbookResolver(driveClient, sheetsClient, prefs) {
        var resolveCalls = 0
        var lastForceValidateTabs: Boolean? = null
        var delayMs: Long = 0

        override suspend fun resolveForYear(year: Int, forceValidateTabs: Boolean): Result<String> {
            resolveCalls++
            lastForceValidateTabs = forceValidateTabs
            if (delayMs > 0) {
                delay(delayMs)
            }
            return Result.Success("repaired_sheet_id_456")
        }
    }

    @Test
    fun testAutoProvisionMasterSheetCallsResolverWithForceValidateTabsTrue() = runBlocking<Unit> {
        val okHttpClient = OkHttpClient.Builder().build()
        val driveClient = GoogleDriveClient(okHttpClient, fakeTokenProvider)
        val sheetsClient = GoogleSheetsClient(okHttpClient, fakeTokenProvider)
        val prefs = FakePreferencesRepository()
        val resolver = FakeWorkbookResolver(driveClient, sheetsClient, prefs)

        val stateFlow = MutableStateFlow(SettingsUiState())
        val coordinator = SettingsSyncCoordinator(
            preferencesRepository = prefs,
            transactionRepository = null,
            syncConfigUseCase = null,
            regenerateDashboardUseCase = null,
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            googleAuthTokenProvider = null,
            workbookResolver = resolver,
            accountRepository = null,
            categoryRepository = null,
            scope = this,
            uiState = stateFlow,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.autoProvisionMasterSheet()

        assertEquals("Expected resolver to be called once", 1, resolver.resolveCalls)
        assertEquals("Expected forceValidateTabs to be true", true, resolver.lastForceValidateTabs)
        assertEquals("repaired_sheet_id_456", stateFlow.value.spreadsheetId)
        assertFalse(stateFlow.value.isProvisioning)
        assertTrue(stateFlow.value.userMessage?.contains("repaired") == true || stateFlow.value.userMessage?.contains("provisioned") == true)
    }

    @Test
    fun testAutoProvisionMasterSheetIgnoresSecondTapWhileRunning() = runBlocking<Unit> {
        val okHttpClient = OkHttpClient.Builder().build()
        val driveClient = GoogleDriveClient(okHttpClient, fakeTokenProvider)
        val sheetsClient = GoogleSheetsClient(okHttpClient, fakeTokenProvider)
        val prefs = FakePreferencesRepository()
        val resolver = FakeWorkbookResolver(driveClient, sheetsClient, prefs).apply {
            delayMs = 200
        }

        val stateFlow = MutableStateFlow(SettingsUiState())
        val coordinator = SettingsSyncCoordinator(
            preferencesRepository = prefs,
            transactionRepository = null,
            syncConfigUseCase = null,
            regenerateDashboardUseCase = null,
            googleDriveClient = driveClient,
            googleSheetsClient = sheetsClient,
            googleAuthTokenProvider = null,
            workbookResolver = resolver,
            accountRepository = null,
            categoryRepository = null,
            scope = this,
            uiState = stateFlow,
            ioDispatcher = Dispatchers.Default
        )

        // First tap: begins provisioning
        coordinator.autoProvisionMasterSheet()
        delay(20)
        assertTrue("Expected isProvisioning to be true after first tap", stateFlow.value.isProvisioning)

        // Second tap while running: must be ignored
        coordinator.autoProvisionMasterSheet()

        // Wait until completion
        while (stateFlow.value.isProvisioning) {
            delay(20)
        }

        // Verify resolver was called exactly once
        assertEquals("Expected resolver to be called only once despite second tap", 1, resolver.resolveCalls)
    }

    @Test
    fun testReRunSetupDoesNotStartProvisioningByItself() {
        val okHttpClient = OkHttpClient.Builder().build()
        val driveClient = GoogleDriveClient(okHttpClient, fakeTokenProvider)
        val sheetsClient = GoogleSheetsClient(okHttpClient, fakeTokenProvider)
        val prefs = FakePreferencesRepository()
        val resolver = FakeWorkbookResolver(driveClient, sheetsClient, prefs)

        var forceOnboarding = false
        val onReRunSetup: () -> Unit = { forceOnboarding = true }

        onReRunSetup()

        assertTrue("Expected forceOnboarding to be true", forceOnboarding)
        assertEquals("Expected resolver not to be called by re-running setup alone", 0, resolver.resolveCalls)
    }

    @Test
    fun testOnboardingFormatFriendlyErrorMessageIncludesGoogleMessageAndDiagnosticsNotice() {
        val googleError = GoogleApiError(
            endpointId = "sheets.batchUpdate",
            httpStatus = 400,
            status = "INVALID_ARGUMENT",
            reason = "INVALID_ARGUMENT",
            message = "Range ('Nov26'!AC4:AC) exceeds grid limits. Max rows: 1000, max columns: 26"
        )
        val exception = GoogleSheetsApiException(
            message = "Sheets batchUpdate failed (code 400)",
            statusCode = 400,
            apiError = googleError
        )

        val friendlyMessage = OnboardingViewModel.formatFriendlyErrorMessage(exception)

        assertTrue("Expected message to contain Google error message", friendlyMessage.contains("exceeds grid limits"))
        assertTrue("Expected message to point to Advanced Diagnostics", friendlyMessage.contains("Settings > Advanced Diagnostics"))
    }
}
