package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.R
import com.financeautomation.data.remote.GoogleApiError
import com.financeautomation.data.remote.GoogleEndpoint
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsApiException
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fakeTokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "test_token"
        override fun refreshAccessToken(): String = "test_token"
    }

    private class FakePreferencesRepository : PreferencesRepository {
        var email: String? = "test@example.com"
        var activeSpreadsheetId: String? = "sheet_123"
        val yearMap = mutableMapOf<Int, String>()
        val devModeFlow = MutableStateFlow(false)
        val themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)

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
        override fun getDeveloperMode(): Flow<Boolean> = devModeFlow.asStateFlow()
        override suspend fun getDeveloperModeSync(): Boolean = devModeFlow.value
        override suspend fun setDeveloperMode(enabled: Boolean) { devModeFlow.value = enabled }
        override fun getThemeMode(): Flow<ThemeMode> = themeModeFlow.asStateFlow()
        override suspend fun getThemeModeSync(): ThemeMode = themeModeFlow.value
        override suspend fun setThemeMode(mode: ThemeMode) { themeModeFlow.value = mode }
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
    fun testOnboardingFormatFriendlyErrorMessageCarriesGoogleMessageAsStringResourceArguments() {
        val googleError = GoogleApiError(
            endpointId = GoogleEndpoint.SHEETS_BATCH_UPDATE,
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

        val friendly = OnboardingViewModel.formatFriendlyErrorMessage(exception)

        assertEquals(R.string.onboarding_error_generic, friendly.resId)
        assertEquals(GoogleEndpoint.SHEETS_BATCH_UPDATE, friendly.args[0])
        assertEquals(400, friendly.args[1])
        assertEquals("INVALID_ARGUMENT", friendly.args[2])
        assertTrue(
            "Expected the Google message in the arguments",
            (friendly.args[3] as String).contains("exceeds grid limits")
        )
    }

    @Test
    fun testOnboardingFormatFriendlyErrorMessageMapsKnownReasonsToDedicatedStrings() {
        fun sheetsException(status: Int, reason: String?) = GoogleSheetsApiException(
            message = "failed",
            statusCode = status,
            apiError = GoogleApiError(
                endpointId = GoogleEndpoint.DRIVE_FILES_LIST,
                httpStatus = status,
                status = null,
                reason = reason,
                message = "m"
            )
        )

        assertEquals(
            R.string.onboarding_error_api_disabled,
            OnboardingViewModel.formatFriendlyErrorMessage(sheetsException(403, "SERVICE_DISABLED")).resId
        )
        assertEquals(
            R.string.onboarding_error_insufficient_scope,
            OnboardingViewModel.formatFriendlyErrorMessage(sheetsException(403, "insufficientPermissions")).resId
        )
        assertEquals(
            R.string.onboarding_error_rate_limited,
            OnboardingViewModel.formatFriendlyErrorMessage(sheetsException(429, "rateLimitExceeded")).resId
        )
    }

    @Test
    fun testOnboardingFormatFriendlyErrorMessageHandlesNonGoogleErrors() {
        val withMessage = OnboardingViewModel.formatFriendlyErrorMessage(java.io.IOException("socket closed"))
        assertEquals(R.string.onboarding_error_unknown_with_message, withMessage.resId)
        assertEquals(listOf<Any>("socket closed"), withMessage.args)

        val withoutMessage = OnboardingViewModel.formatFriendlyErrorMessage(java.io.IOException())
        assertEquals(R.string.onboarding_error_unknown, withoutMessage.resId)
    }

    private class FakePeopleRepo : PeopleRepository {
        override fun getCompanions(): Flow<List<Companion>> = flowOf(emptyList())
        override suspend fun getAllCompanions(): List<Companion> = emptyList()
        override suspend fun getCompanionById(id: String): Companion? = null
        override suspend fun getCompanionByName(name: String): Companion? = null
        override suspend fun insertCompanion(companion: Companion) {}
        override suspend fun updateCompanion(companion: Companion) {}
        override suspend fun updateCompanionName(id: String, newName: String) {}
        override suspend fun deleteCompanion(companion: Companion) {}
        override suspend fun deleteCompanionById(id: String) {}
    }

    private class FakeTransactionRepo : TransactionRepository {
        override fun getTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = null
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
    }

    @Test
    fun testOnVersionTapped_unlocksDeveloperModeAfter7TapsWithinWindow() = runBlocking {
        val prefs = FakePreferencesRepository()
        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            ioDispatcher = Dispatchers.Unconfined
        )

        assertFalse(prefs.getDeveloperModeSync())
        assertFalse(vm.uiState.value.developerMode)

        val t0 = 100_000L
        for (i in 0 until 6) {
            vm.onVersionTapped(t0 + (i * 1000L))
            assertFalse("Developer mode should not unlock before 7 taps", prefs.getDeveloperModeSync())
            assertFalse(vm.uiState.value.developerMode)
        }

        // 7th tap within 3s of 6th tap
        vm.onVersionTapped(t0 + 6000L)
        assertTrue("Developer mode must unlock on the 7th tap", prefs.getDeveloperModeSync())
        assertTrue(vm.uiState.value.developerMode)
        assertEquals("Developer mode unlocked", vm.uiState.value.userMessage)
    }

    @Test
    fun testOnVersionTapped_resetsCounterIfGapExceeds3Seconds() = runBlocking {
        val prefs = FakePreferencesRepository()
        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val t0 = 100_000L
        // 6 rapid taps
        for (i in 0 until 6) {
            vm.onVersionTapped(t0 + (i * 500L))
        }
        assertFalse(prefs.getDeveloperModeSync())

        // 7th tap delayed by 3500ms (> 3000ms threshold)
        vm.onVersionTapped(t0 + 2500L + 3500L)
        assertFalse("Counter should have reset because interval exceeded 3s", prefs.getDeveloperModeSync())
        assertFalse(vm.uiState.value.developerMode)
    }

    @Test
    fun testThemeModeAndDeveloperMode_roundTrip() = runBlocking {
        val prefs = FakePreferencesRepository()
        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            ioDispatcher = Dispatchers.Unconfined
        )

        assertEquals(ThemeMode.SYSTEM, vm.uiState.value.themeMode)
        vm.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, vm.uiState.value.themeMode)
        assertEquals(ThemeMode.DARK, prefs.getThemeModeSync())

        vm.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, vm.uiState.value.themeMode)
        assertEquals(ThemeMode.LIGHT, prefs.getThemeModeSync())

        assertFalse(vm.uiState.value.developerMode)
        vm.setDeveloperMode(true)
        assertTrue(vm.uiState.value.developerMode)
        assertTrue(prefs.getDeveloperModeSync())

        vm.setDeveloperMode(false)
        assertFalse(vm.uiState.value.developerMode)
        assertFalse(prefs.getDeveloperModeSync())
    }
}
