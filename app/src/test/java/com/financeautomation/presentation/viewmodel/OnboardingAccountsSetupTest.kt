package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.data.remote.AuthResult
import com.financeautomation.data.remote.AuthorizationSource
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.data.repository.AccountRepositoryImpl
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.testutil.InMemoryAccountDao
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingAccountsSetupTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeAuth(var result: AuthResult) : AuthorizationSource {
        override suspend fun requestAuthorization(isForeground: Boolean, accountEmail: String?): AuthResult = result
    }

    private val tokens = object : TokenProvider {
        override fun getAccessToken(): String = "test-token"
        override fun refreshAccessToken(): String = "test-token"
    }

    private fun createResolver(h: SyncHarness) = object : WorkbookResolver(
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

    @Test
    fun firstRunShowsTheStep() = runTest(dispatcher) {
        val h = SyncHarness()
        h.googleEmail = null
        val dao = InMemoryAccountDao()
        dao.insert(AccountEntity("acc_cash", CategoryConstants.ACCOUNT_CASH, null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", CategoryConstants.ACCOUNT_OTHER, null, isDefault = true, isArchived = false, displayOrder = 1))
        val repo = AccountRepositoryImpl(accountDao = dao, ioDispatcher = dispatcher)

        val vm = OnboardingViewModel(
            googleAuthTokenProvider = FakeAuth(AuthResult.Token("tok")),
            workbookResolver = createResolver(h),
            preferencesRepository = h.prefs,
            ioDispatcher = dispatcher,
            accountRepository = repo
        )
        advanceUntilIdle()

        assertFalse("First run must not have seen accounts step yet", vm.uiState.value.isAccountsSeen)
        assertFalse(vm.uiState.value.showAccountSetupStep)

        vm.openAccountSetup()
        assertTrue(vm.uiState.value.showAccountSetupStep)
    }

    @Test
    fun skipWorks() = runTest(dispatcher) {
        val h = SyncHarness()
        h.googleEmail = null
        val dao = InMemoryAccountDao()
        dao.insert(AccountEntity("acc_cash", CategoryConstants.ACCOUNT_CASH, null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", CategoryConstants.ACCOUNT_OTHER, null, isDefault = true, isArchived = false, displayOrder = 1))
        val repo = AccountRepositoryImpl(accountDao = dao, ioDispatcher = dispatcher)

        val vm = OnboardingViewModel(
            googleAuthTokenProvider = FakeAuth(AuthResult.Token("tok")),
            workbookResolver = createResolver(h),
            preferencesRepository = h.prefs,
            ioDispatcher = dispatcher,
            accountRepository = repo
        )
        advanceUntilIdle()

        vm.openAccountSetup()
        assertTrue(vm.uiState.value.showAccountSetupStep)

        vm.skipAccountSetup()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showAccountSetupStep)
        assertTrue(vm.uiState.value.isAccountsSeen)
        assertTrue(h.prefs.isOnboardingAccountsSeen())
    }

    @Test
    fun addedAccountsAppearAndFirstBecomesDefault() = runTest(dispatcher) {
        val h = SyncHarness()
        h.googleEmail = null
        val dao = InMemoryAccountDao()
        dao.insert(AccountEntity("acc_cash", CategoryConstants.ACCOUNT_CASH, null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", CategoryConstants.ACCOUNT_OTHER, null, isDefault = true, isArchived = false, displayOrder = 1))
        val repo = AccountRepositoryImpl(accountDao = dao, ioDispatcher = dispatcher)

        val vm = OnboardingViewModel(
            googleAuthTokenProvider = FakeAuth(AuthResult.Token("tok")),
            workbookResolver = createResolver(h),
            preferencesRepository = h.prefs,
            ioDispatcher = dispatcher,
            accountRepository = repo
        )
        advanceUntilIdle()

        vm.addAccount("SBI", "4821")
        advanceUntilIdle()

        val sbi = dao.getByName("SBI")
        assertNotNull(sbi)
        assertTrue("First added account must become default", sbi!!.isDefault)
        assertEquals("4821", sbi.lastFourDigits)
        assertEquals("SBI", h.prefs.getDefaultAccountSync())
        assertEquals("SBI", vm.uiState.value.defaultAccountName)

        vm.addAccount("HDFC Bank", "1122")
        advanceUntilIdle()

        val hdfc = dao.getByName("HDFC Bank")
        assertNotNull(hdfc)
        assertFalse("Second added account must not overwrite default automatically", hdfc!!.isDefault)
        assertEquals("SBI", vm.uiState.value.defaultAccountName)

        // User can explicitly change default
        vm.setDefaultAccount(hdfc.id, hdfc.name)
        advanceUntilIdle()
        assertEquals("HDFC Bank", vm.uiState.value.defaultAccountName)
        assertEquals("HDFC Bank", h.prefs.getDefaultAccountSync())
    }

    @Test
    fun cashAndOtherCannotBeRemoved() = runTest(dispatcher) {
        val h = SyncHarness()
        h.googleEmail = null
        val dao = InMemoryAccountDao()
        dao.insert(AccountEntity("acc_cash", CategoryConstants.ACCOUNT_CASH, null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", CategoryConstants.ACCOUNT_OTHER, null, isDefault = true, isArchived = false, displayOrder = 1))
        val repo = AccountRepositoryImpl(accountDao = dao, ioDispatcher = dispatcher)

        val vm = OnboardingViewModel(
            googleAuthTokenProvider = FakeAuth(AuthResult.Token("tok")),
            workbookResolver = createResolver(h),
            preferencesRepository = h.prefs,
            ioDispatcher = dispatcher,
            accountRepository = repo
        )
        advanceUntilIdle()

        vm.deleteAccount("acc_cash")
        advanceUntilIdle()

        assertNotNull("Cash must not be deleted", dao.getById("acc_cash"))
        assertNotNull(vm.uiState.value.accountSetupError)

        vm.deleteAccount("acc_other")
        advanceUntilIdle()

        assertNotNull("Other must not be deleted", dao.getById("acc_other"))
        assertNotNull(vm.uiState.value.accountSetupError)
    }

    @Test
    fun anExistingInstallDoesNotSeeTheStep() = runTest(dispatcher) {
        val h = SyncHarness()
        h.googleEmail = "existing@example.com"
        val dao = InMemoryAccountDao()
        dao.insert(AccountEntity("acc_cash", CategoryConstants.ACCOUNT_CASH, null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", CategoryConstants.ACCOUNT_OTHER, null, isDefault = true, isArchived = false, displayOrder = 1))
        val repo = AccountRepositoryImpl(accountDao = dao, ioDispatcher = dispatcher)

        val vm = OnboardingViewModel(
            googleAuthTokenProvider = FakeAuth(AuthResult.Token("tok")),
            workbookResolver = createResolver(h),
            preferencesRepository = h.prefs,
            ioDispatcher = dispatcher,
            accountRepository = repo
        )
        advanceUntilIdle()

        assertTrue("Existing install must automatically mark accounts seen", vm.uiState.value.isAccountsSeen)
        assertFalse(vm.uiState.value.showAccountSetupStep)
    }

    @Test
    fun notificationParserMatchesByLastFourDigitsAndFallsBackToDefault() {
        val configured = listOf("SBI", "Cash", "Other")
        val digits = mapOf("SBI" to "4821", "Cash" to null, "Other" to null)

        val matched = ParseNotificationUseCase.inferAccountWithFlag(
            text = "Debited Rs 450 from A/c XX4821 at Starbucks",
            configuredAccounts = configured,
            defaultAccount = "Other",
            accountDigits = digits
        )
        assertEquals("SBI", matched.account)
        assertFalse(matched.isNewAccount)

        val unknown = ParseNotificationUseCase.inferAccountWithFlag(
            text = "Debited Rs 450 from A/c XX9999 at Starbucks",
            configuredAccounts = configured,
            defaultAccount = "Other",
            accountDigits = digits
        )
        assertEquals("Other", unknown.account)
        assertTrue(unknown.isNewAccount)
    }
}
