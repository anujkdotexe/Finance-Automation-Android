package com.financeautomation.presentation.telemetry

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.AnalyticsManager
import com.financeautomation.core.utils.UsageEvents
import com.financeautomation.data.remote.AuthResult
import com.financeautomation.data.remote.AuthorizationSource
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.data.repository.AccountRepositoryImpl
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.presentation.viewmodel.OnboardingViewModel
import com.financeautomation.presentation.viewmodel.SettingsSyncCoordinator
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.testutil.InMemoryAccountDao
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class ProductionTelemetryWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private val capturedEvents = mutableListOf<Pair<String, Map<String, Any>?>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        AnalyticsManager.resetForTesting()
        capturedEvents.clear()
        AnalyticsManager.testEventListener = { event, params ->
            capturedEvents.add(event to params)
        }
    }

    @After
    fun tearDown() {
        AnalyticsManager.resetForTesting()
        capturedEvents.clear()
        Dispatchers.resetMain()
    }

    private fun createCoordinator(
        prefs: PreferencesRepository,
        scope: kotlinx.coroutines.CoroutineScope,
        syncResult: Result<Int>
    ): SettingsSyncCoordinator {
        val repo = object : TransactionRepository by (Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, _, _ -> null } as TransactionRepository) {
            override suspend fun syncPendingTransactions(userInitiated: Boolean): Result<Int> = syncResult
        }

        return SettingsSyncCoordinator(
            preferencesRepository = prefs,
            transactionRepository = repo,
            syncConfigUseCase = null,
            regenerateDashboardUseCase = null,
            googleDriveClient = null,
            googleSheetsClient = null,
            googleAuthTokenProvider = null,
            workbookResolver = null,
            accountRepository = null,
            categoryRepository = null,
            scope = scope,
            uiState = MutableStateFlow(SettingsUiState()),
            ioDispatcher = dispatcher
        )
    }

    private fun createOnboardingVm(h: SyncHarness): OnboardingViewModel {
        val dao = InMemoryAccountDao()
        val repo = AccountRepositoryImpl(accountDao = dao, ioDispatcher = dispatcher)
        val tokens = object : TokenProvider {
            override fun getAccessToken(): String = "test-token"
            override fun refreshAccessToken(): String = "test-token"
        }
        val resolver = object : WorkbookResolver(
            GoogleDriveClient(OkHttpClient(), tokens),
            GoogleSheetsClient(OkHttpClient(), tokens),
            h.prefs
        ) {
            override suspend fun resolveForYear(
                year: Int,
                forceValidateTabs: Boolean,
                createBackup: Boolean,
                archiveOlderBackups: Boolean
            ): Result<String> = Result.Success("sheet123")
        }

        return OnboardingViewModel(
            googleAuthTokenProvider = object : AuthorizationSource {
                override suspend fun requestAuthorization(isForeground: Boolean, accountEmail: String?): AuthResult =
                    AuthResult.Token("tok")
            },
            workbookResolver = resolver,
            preferencesRepository = h.prefs,
            ioDispatcher = dispatcher,
            accountRepository = repo
        )
    }

    @Test
    fun settingsSyncSuccessDispatchesSyncResultWithNoneErrorKind() = runTest(dispatcher) {
        AnalyticsManager.testOptInOverride = true
        val h = SyncHarness()
        val coordinator = createCoordinator(h.prefs, this, Result.Success(3))

        coordinator.syncPendingTransactions()
        advanceUntilIdle()

        assertEquals("Exactly one sync_result event should be logged", 1, capturedEvents.size)
        val (eventName, params) = capturedEvents.first()
        assertEquals(UsageEvents.EVENT_SYNC_RESULT, eventName)
        assertEquals("none", params?.get("error_kind"))
        assertEquals(3, params?.get("count"))
    }

    @Test
    fun settingsSyncFailureDispatchesSyncResultWithNormalisedErrorKind() = runTest(dispatcher) {
        AnalyticsManager.testOptInOverride = true
        val h = SyncHarness()
        val coordinator = createCoordinator(h.prefs, this, Result.Error(IOException("Connection failed")))

        coordinator.syncPendingTransactions()
        advanceUntilIdle()

        assertEquals("Exactly one sync_result event should be logged", 1, capturedEvents.size)
        val (eventName, params) = capturedEvents.first()
        assertEquals(UsageEvents.EVENT_SYNC_RESULT, eventName)
        assertEquals("network", params?.get("error_kind"))
        assertEquals(1, params?.get("count"))
    }

    @Test
    fun settingsSyncWhenOptedOutDoesNotDispatchAnyTelemetry() = runTest(dispatcher) {
        AnalyticsManager.testOptInOverride = false
        val h = SyncHarness()
        val coordinator = createCoordinator(h.prefs, this, Result.Success(5))

        coordinator.syncPendingTransactions()
        advanceUntilIdle()

        assertTrue("No events must reach listener when opted out", capturedEvents.isEmpty())
    }

    @Test
    fun onboardingViewModelDispatchesOnboardingStepEventsWhenOptedIn() = runTest(dispatcher) {
        AnalyticsManager.testOptInOverride = true
        val h = SyncHarness()
        val vm = createOnboardingVm(h)
        advanceUntilIdle()
        capturedEvents.clear()

        // 1. Skip account setup
        vm.skipAccountSetup()
        advanceUntilIdle()

        assertEquals(1, capturedEvents.size)
        assertEquals(UsageEvents.EVENT_ONBOARDING_STEP, capturedEvents[0].first)
        assertEquals("accounts", capturedEvents[0].second?.get("step_id"))
        assertEquals("skipped", capturedEvents[0].second?.get("status"))

        // 2. Complete account setup
        capturedEvents.clear()
        vm.completeAccountSetup()
        advanceUntilIdle()

        assertEquals(1, capturedEvents.size)
        assertEquals(UsageEvents.EVENT_ONBOARDING_STEP, capturedEvents[0].first)
        assertEquals("accounts", capturedEvents[0].second?.get("step_id"))
        assertEquals("done", capturedEvents[0].second?.get("status"))

        // 3. Permissions step
        capturedEvents.clear()
        vm.updatePermissionsState(hasNotificationListener = true)
        advanceUntilIdle()

        assertEquals(1, capturedEvents.size)
        assertEquals(UsageEvents.EVENT_ONBOARDING_STEP, capturedEvents[0].first)
        assertEquals("permissions", capturedEvents[0].second?.get("step_id"))
        assertEquals("done", capturedEvents[0].second?.get("status"))
    }

    @Test
    fun onboardingViewModelDropsTelemetryWhenOptedOut() = runTest(dispatcher) {
        AnalyticsManager.testOptInOverride = false
        val h = SyncHarness()
        val vm = createOnboardingVm(h)
        advanceUntilIdle()
        capturedEvents.clear()

        vm.skipAccountSetup()
        vm.completeAccountSetup()
        vm.updatePermissionsState(hasNotificationListener = true)
        advanceUntilIdle()

        assertTrue("No events must reach listener when opt-in is false", capturedEvents.isEmpty())
    }
}
