package com.financeautomation.data.account

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AuthResult
import com.financeautomation.data.remote.AuthorizationSource
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.usecase.SyncConfigUseCase
import com.financeautomation.presentation.viewmodel.OnboardingViewModel
import com.financeautomation.presentation.viewmodel.SettingsSyncCoordinator
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSwitchProductionWiringTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class HarnessAccountDataStore(private val h: SyncHarness) : AccountDataStore {
        var wipeCount = 0
        override suspend fun pendingCount(): Int =
            h.qDao.queue.values.count { it.operationType != SyncQueueEntity.OP_CONFIG_PUSH }

        override suspend fun hasLocalData(): Boolean =
            h.tDao.rows.isNotEmpty() || h.iDao.storage.isNotEmpty() || h.qDao.queue.isNotEmpty()

        override suspend fun wipe() {
            wipeCount++
            h.tDao.rows.clear()
            h.iDao.storage.clear()
            h.qDao.queue.clear()
        }
    }

    private class FakeAuth(var result: AuthResult = AuthResult.Token("fake_token")) : AuthorizationSource {
        val requestedFor = mutableListOf<String?>()
        override suspend fun requestAuthorization(isForeground: Boolean, accountEmail: String?): AuthResult {
            requestedFor += accountEmail
            return result
        }
    }

    private val fakeTokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "test_token"
        override fun refreshAccessToken(): String = "test_token"
    }

    private fun sampleTransaction(id: String): Transaction = Transaction(
        id = id,
        timestamp = 1000L,
        dateSerial = 45000,
        timeSerial = 0.5,
        account = "Cash",
        merchant = "Cafe Coffee",
        tripTag = "",
        category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
        totalAmount = 150.0,
        companions = "Personal",
        myShare = 150.0,
        owedToMe = 0.0,
        status = com.financeautomation.core.model.TransactionStatus.PERSONAL
    )

    @Test
    fun settingsPathEvaluatesConfirmSwitchWhenOwnerExistsAndNewEmailSignsIn() = runTest(dispatcher) {
        val h = SyncHarness()
        val store = HarnessAccountDataStore(h)
        val guard = AccountSwitchGuard(
            preferences = h.prefs,
            store = store,
            stopBackgroundWork = {},
            startBackgroundWork = {}
        )

        // 1. Existing owner account has logged transactions and active owner preference
        val ownerEmail = "owner@example.com"
        h.googleEmail = ownerEmail
        h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER] = ownerEmail
        h.repo.insertTransaction(sampleTransaction("txn_1"))
        advanceUntilIdle()
        assertTrue(store.hasLocalData())

        // 2. Settings coordinator wired with production dependencies
        val uiState = MutableStateFlow(SettingsUiState())
        val coordinator = SettingsSyncCoordinator(
            preferencesRepository = h.prefs,
            transactionRepository = h.repo,
            syncConfigUseCase = null,
            regenerateDashboardUseCase = null,
            googleDriveClient = null,
            googleSheetsClient = null,
            googleAuthTokenProvider = null,
            workbookResolver = null,
            accountRepository = null,
            categoryRepository = null,
            profileAvatarManager = null,
            accountSwitchGuard = guard,
            scope = this,
            uiState = uiState,
            ioDispatcher = dispatcher
        )

        // 3. User performs sign out
        coordinator.onGoogleSignOut()
        advanceUntilIdle()

        // Verify sign-out remembered KEY_LAST_EMAIL and cleared current email
        assertEquals(ownerEmail, h.preferences[AccountSwitchGuard.KEY_LAST_EMAIL])
        assertEquals(ownerEmail, h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER])
        assertNull(h.googleEmail)

        // 4. User attempts sign in with a different Google account
        val newEmail = "secondary@example.com"
        coordinator.onGoogleSignInSuccess(newEmail, "Secondary User", null)
        advanceUntilIdle()

        // 5. Decision must be CONFIRM_SWITCH: dialog prompt displayed, data not yet wiped
        val prompt = uiState.value.pendingAccountSwitch
        assertNotNull("Prompt must be shown for different account", prompt)
        assertEquals(newEmail, prompt?.email)
        assertEquals(1, prompt?.pendingCount)
        assertTrue(prompt?.hasLocalData == true)
        assertEquals(0, store.wipeCount)
        assertEquals(1, h.tDao.rows.size)
    }

    @Test
    fun onboardingPathEvaluatesConfirmSwitchWhenOwnerExistsAndNewEmailSignsIn() = runTest(dispatcher) {
        val h = SyncHarness()
        val store = HarnessAccountDataStore(h)
        val guard = AccountSwitchGuard(
            preferences = h.prefs,
            store = store,
            stopBackgroundWork = {},
            startBackgroundWork = {}
        )

        // 1. Existing owner account has logged transactions and recorded owner
        val ownerEmail = "owner@example.com"
        h.googleEmail = ownerEmail
        h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER] = ownerEmail
        h.repo.insertTransaction(sampleTransaction("txn_2"))
        advanceUntilIdle()
        assertTrue(store.hasLocalData())

        // 2. User signs out (simulating transition back to Onboarding screen)
        h.preferences[AccountSwitchGuard.KEY_LAST_EMAIL] = ownerEmail
        h.googleEmail = null

        val resolver = object : WorkbookResolver(
            GoogleDriveClient(OkHttpClient(), fakeTokenProvider),
            GoogleSheetsClient(OkHttpClient(), fakeTokenProvider),
            h.prefs
        ) {
            override suspend fun resolveForYear(year: Int, forceValidateTabs: Boolean, createBackup: Boolean, archiveOlderBackups: Boolean): Result<String> =
                Result.Success("sheet_123")
        }

        val auth = FakeAuth()
        val onboardingVm = OnboardingViewModel(
            googleAuthTokenProvider = auth,
            workbookResolver = resolver,
            preferencesRepository = h.prefs,
            ioDispatcher = dispatcher,
            accountSwitchGuard = guard
        )
        advanceUntilIdle()

        // 3. User picks a different Google account in Onboarding
        val newEmail = "secondary@example.com"
        onboardingVm.onGoogleAccountDetails(newEmail, "Secondary User", null)
        onboardingVm.startSignIn()
        advanceUntilIdle()

        // 4. Decision must be CONFIRM_SWITCH: prompt populated, authorization not yet called
        val prompt = onboardingVm.uiState.value.pendingAccountSwitch
        assertNotNull("Pending switch prompt must be set in OnboardingUiState", prompt)
        assertEquals(newEmail, prompt?.email)
        assertEquals(1, prompt?.pendingCount)
        assertTrue(prompt?.hasLocalData == true)
        assertTrue("No auth token requested before user confirms switch", auth.requestedFor.isEmpty())
        assertEquals(0, store.wipeCount)
    }

    @Test
    fun upgradeScenarioWhereOwnerIsMissingButLastEmailExistsEvaluatesConfirmSwitch() = runTest(dispatcher) {
        val h = SyncHarness()
        val store = HarnessAccountDataStore(h)
        val guard = AccountSwitchGuard(
            preferences = h.prefs,
            store = store
        )

        // Upgrade from older build where KEY_LEDGER_OWNER was never set,
        // but user signed out so KEY_LAST_EMAIL was recorded
        h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER] = null
        h.preferences[AccountSwitchGuard.KEY_LAST_EMAIL] = "previous@example.com"
        h.repo.insertTransaction(sampleTransaction("txn_3"))
        advanceUntilIdle()

        val eval = guard.evaluate("different@example.com")
        assertEquals(SwitchDecision.CONFIRM_SWITCH, eval.decision)
        assertEquals(1, eval.pendingCount)
        assertTrue(eval.hasLocalData)
    }

    @Test
    fun upgradeScenarioWhereNeitherOwnerNorLastEmailExistsButLocalDataExistsEvaluatesConfirmSwitch() = runTest(dispatcher) {
        val h = SyncHarness()
        val store = HarnessAccountDataStore(h)
        val guard = AccountSwitchGuard(
            preferences = h.prefs,
            store = store
        )

        // Upgrade where both owner and last email are null, but local transactions exist
        h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER] = null
        h.preferences[AccountSwitchGuard.KEY_LAST_EMAIL] = null
        h.repo.insertTransaction(sampleTransaction("txn_4"))
        advanceUntilIdle()

        val eval = guard.evaluate("newuser@example.com")
        assertEquals(SwitchDecision.CONFIRM_SWITCH, eval.decision)
        assertEquals(1, eval.pendingCount)
        assertTrue(eval.hasLocalData)
    }

    @Test
    fun cleanFirstRunWithoutOwnerOrDataProceedsDirectly() = runTest(dispatcher) {
        val h = SyncHarness()
        val store = HarnessAccountDataStore(h)
        val guard = AccountSwitchGuard(
            preferences = h.prefs,
            store = store
        )

        // Clean install: no owner, no last email, no local rows
        h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER] = null
        h.preferences[AccountSwitchGuard.KEY_LAST_EMAIL] = null

        val eval = guard.evaluate("firstuser@example.com")
        assertEquals(SwitchDecision.PROCEED, eval.decision)
        assertEquals(0, eval.pendingCount)
    }
}
