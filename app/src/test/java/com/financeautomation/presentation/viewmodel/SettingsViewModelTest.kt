package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.R
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.entity.PayableEntity
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
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val spreadsheetIdFlow = MutableStateFlow<String?>("sheet_123")
        var activeSpreadsheetId: String?
            get() = spreadsheetIdFlow.value
            set(value) { spreadsheetIdFlow.value = value }
        val yearMap = mutableMapOf<Int, String>()
        val devModeFlow = MutableStateFlow(false)
        val themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)

        override fun getSpreadsheetId(): Flow<String?> = spreadsheetIdFlow.asStateFlow()
        override suspend fun getSpreadsheetIdSync(): String? = spreadsheetIdFlow.value
        override suspend fun setSpreadsheetId(id: String) {
            spreadsheetIdFlow.value = id
        }

        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(yearMap[year])
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = yearMap[year]
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {
            yearMap[year] = id
        }

        val titleFlow = MutableStateFlow<String?>("Finance2026")
        override fun getSpreadsheetTitle(): Flow<String?> = titleFlow.asStateFlow()
        override suspend fun getSpreadsheetTitleSync(): String? = titleFlow.value
        override suspend fun setSpreadsheetTitle(title: String) { titleFlow.value = title }

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
        val noticePendingFlow = MutableStateFlow(false)
        override fun getWorkbookReplacedNoticePending(): Flow<Boolean> = noticePendingFlow.asStateFlow()
        private val generic = mutableMapOf<String, String>()
        override suspend fun getPreference(key: String): String? = generic[key]
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {
            if (key == PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING) {
                noticePendingFlow.value = (value == "true")
            }
            if (value == null) generic.remove(key) else generic[key] = value
        }
        override suspend fun deletePreference(key: String) {
            if (key == PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING) {
                noticePendingFlow.value = false
            }
            generic.remove(key)
        }
    }

    private class FakeWorkbookResolver(
        driveClient: GoogleDriveClient,
        sheetsClient: GoogleSheetsClient,
        prefs: PreferencesRepository
    ) : WorkbookResolver(driveClient, sheetsClient, prefs) {
        var resolveCalls = 0
        var lastForceValidateTabs: Boolean? = null
        var lastCreateBackup: Boolean? = null
        var lastArchiveOlderBackups: Boolean? = null
        var delayMs: Long = 0

        override suspend fun resolveForYear(
            year: Int,
            forceValidateTabs: Boolean,
            createBackup: Boolean,
            archiveOlderBackups: Boolean
        ): Result<String> {
            resolveCalls++
            lastForceValidateTabs = forceValidateTabs
            lastCreateBackup = createBackup
            lastArchiveOlderBackups = archiveOlderBackups
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
        assertEquals("Expected createBackup to be false for autoProvisionMasterSheet", false, resolver.lastCreateBackup)
        assertEquals("repaired_sheet_id_456", stateFlow.value.spreadsheetId)
        assertFalse(stateFlow.value.isProvisioning)
        assertEquals(R.string.settings_msg_provisioned_workbook, stateFlow.value.userMessage?.resId)
    }

    @Test
    fun testOnRepairWorkbookCallsResolverWithCreateBackupTrue() = runBlocking<Unit> {
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

        coordinator.onRepairWorkbook()

        assertEquals("Expected resolver to be called once", 1, resolver.resolveCalls)
        assertEquals("Expected forceValidateTabs to be true", true, resolver.lastForceValidateTabs)
        assertEquals("Expected createBackup to be true for onRepairWorkbook", true, resolver.lastCreateBackup)
        assertEquals("Expected archiveOlderBackups to be false by default", false, resolver.lastArchiveOlderBackups)
        assertEquals("repaired_sheet_id_456", stateFlow.value.spreadsheetId)
        assertFalse(stateFlow.value.isProvisioning)
        assertEquals(R.string.settings_msg_provisioned_workbook, stateFlow.value.userMessage?.resId)
    }

    @Test
    fun testOnRepairWorkbookWithArchiveOlderCallsResolverWithArchiveOlderTrue() = runBlocking<Unit> {
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

        coordinator.onRepairWorkbook(archiveOlder = true)

        assertEquals("Expected resolver to be called once", 1, resolver.resolveCalls)
        assertEquals("Expected forceValidateTabs to be true", true, resolver.lastForceValidateTabs)
        assertEquals("Expected createBackup to be true for onRepairWorkbook", true, resolver.lastCreateBackup)
        assertEquals("Expected archiveOlderBackups to be true", true, resolver.lastArchiveOlderBackups)
        assertEquals("repaired_sheet_id_456", stateFlow.value.spreadsheetId)
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
    fun testSettingsUiStateDisplaysRealWorkbookNameAndNeverFakeLedgerString() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        prefs.setSpreadsheetId("sheet_123")
        prefs.setSpreadsheetTitle("Finance2026")

        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val state = vm.uiState.value
        assertEquals("sheet_123", state.spreadsheetId)
        assertEquals("Finance2026", state.workbookName)
        assertFalse("Must never display fake 'Finance Ledger 2026'", state.workbookName?.contains("Finance Ledger") == true)
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

    private class FakePeopleRepo(
        private var list: List<Companion> = emptyList()
    ) : PeopleRepository {
        var deletedId: String? = null
        var updatedName: Pair<String, String>? = null

        override fun getCompanions(): Flow<List<Companion>> = flowOf(list)
        override suspend fun getAllCompanions(): List<Companion> = list
        override suspend fun getCompanionById(id: String): Companion? = list.find { it.id == id }
        override suspend fun getCompanionByName(name: String): Companion? = list.find { it.name.equals(name, ignoreCase = true) }
        override suspend fun insertCompanion(companion: Companion) { list = list + companion }
        override suspend fun updateCompanion(companion: Companion) {}
        override suspend fun updateCompanionName(id: String, newName: String) {
            updatedName = Pair(id, newName)
            list = list.map { if (it.id == id) it.copy(name = newName) else it }
        }
        override suspend fun deleteCompanion(companion: Companion) {}
        override suspend fun deleteCompanionById(id: String) {
            deletedId = id
            list = list.filter { it.id != id }
        }
    }

    private class FakePayableDao(
        private val pending: List<PayableEntity> = emptyList()
    ) : PayableDao {
        override fun observeAll(): Flow<List<PayableEntity>> = flowOf(pending)
        override fun observePending(): Flow<List<PayableEntity>> = flowOf(pending)
        override suspend fun getPending(): List<PayableEntity> = pending
        override suspend fun getAll(): List<PayableEntity> = pending
        override suspend fun getByUuid(uuid: String): PayableEntity? = pending.find { it.uuid == uuid }
        override suspend fun upsert(payable: PayableEntity) {}
        override suspend fun upsertAll(payables: List<PayableEntity>) {}
        override suspend fun deleteByUuid(uuid: String) {}
        override suspend fun deleteAll() {}
        override suspend fun updatePaidByName(oldName: String, newName: String): Int = 0
        override suspend fun getAllForYear(
            startSerial: Int,
            endSerial: Int,
            startMillis: Long,
            endMillis: Long
        ): List<PayableEntity> =
            pending.filter {
                val serial = it.dateIncurredSerial
                val matchesYear = if (serial != null) {
                    serial in startSerial until endSerial
                } else {
                    it.updatedAt in startMillis until endMillis
                }
                matchesYear && !it.repaymentStatus.equals("VOID", ignoreCase = true)
            }.sortedWith(compareBy({ it.dateIncurredSerial }, { it.timeSerial }))
    }

    private class FakeTransactionRepo : TransactionRepository {
        override fun getTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = null
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> = Result.Success(Unit)
        override suspend fun undoRecentSave(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
        var reconcileAllMonthsResult: Result<Int> = Result.Success(0)
        override suspend fun reconcileAllMonths(): Result<Int> = reconcileAllMonthsResult
        override suspend fun requeueYearForNewWorkbook(year: Int) {}
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
        assertEquals("Developer mode unlocked", (vm.uiState.value.userMessage as UiText.DynamicString).value)
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

    @Test
    fun testAccountTransactionCountsAreExposedInState() = runBlocking {
        val accountRepo = java.lang.reflect.Proxy.newProxyInstance(
            com.financeautomation.domain.repository.AccountRepository::class.java.classLoader,
            arrayOf(com.financeautomation.domain.repository.AccountRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAccounts" -> flowOf(emptyList<com.financeautomation.domain.model.Account>())
                "getAccountTransactionCounts" -> flowOf(mapOf("HDFC" to 4, "Cash" to 1))
                else -> throw UnsupportedOperationException(method.name)
            }
        } as com.financeautomation.domain.repository.AccountRepository

        val vm = SettingsViewModel(
            preferencesRepository = FakePreferencesRepository(),
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            accountRepository = accountRepo,
            ioDispatcher = Dispatchers.Unconfined
        )

        assertEquals(mapOf("HDFC" to 4, "Cash" to 1), vm.uiState.value.accountTransactionCounts)
    }

    @Test
    fun testCompanionsObserveNetBalancesWithOpenPayable() = runBlocking {
        val prefs = FakePreferencesRepository()
        val people = FakePeopleRepo(listOf(Companion("c1", "Charlie", balance = 0.0)))
        val payableDao = FakePayableDao(
            listOf(
                PayableEntity(
                    uuid = "p_charlie_1",
                    dateIncurredSerial = 46000,
                    timeSerial = 0.5,
                    paidBy = "Charlie",
                    whatFor = "Dinner",
                    amountIOwe = 1200.0,
                    dateRepaidSerial = null,
                    repaymentStatus = "Pending",
                    tabName = "Nov26",
                    sheetRowIndex = 5
                )
            )
        )
        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = people,
            transactionRepository = FakeTransactionRepo(),
            directPayableDao = payableDao,
            ioDispatcher = Dispatchers.Unconfined
        )

        val companions = vm.uiState.value.companions
        val charlie = companions.find { it.name.equals("Charlie", ignoreCase = true) }
        assertNotNull("Charlie must be present in companions", charlie)
        assertEquals(-1200.0, charlie!!.balance, 0.001)
    }

    private class AccessSheetsClient(
        okHttpClient: OkHttpClient,
        tokenProvider: TokenProvider,
        private val accessibleIds: Set<String>
    ) : GoogleSheetsClient(okHttpClient, tokenProvider) {
        override suspend fun getSheetTabsDetailed(spreadsheetId: String): Result<Map<String, GoogleSheetsClient.SheetTabInfo>> =
            if (spreadsheetId in accessibleIds) {
                Result.Success(emptyMap())
            } else {
                Result.Error(GoogleSheetsApiException(404, "Requested entity was not found"))
            }
    }

    private fun idCoordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        prefs: FakePreferencesRepository,
        sheets: GoogleSheetsClient,
        state: MutableStateFlow<SettingsUiState>
    ) = SettingsSyncCoordinator(
        preferencesRepository = prefs,
        transactionRepository = null,
        syncConfigUseCase = null,
        regenerateDashboardUseCase = null,
        googleDriveClient = null,
        googleSheetsClient = sheets,
        googleAuthTokenProvider = null,
        workbookResolver = null,
        accountRepository = null,
        categoryRepository = null,
        scope = scope,
        uiState = state,
        ioDispatcher = Dispatchers.Unconfined
    )

    private class FreshResolver(
        driveClient: GoogleDriveClient,
        sheetsClient: GoogleSheetsClient,
        private val prefs: FakePreferencesRepository,
        private val fail: Boolean
    ) : WorkbookResolver(driveClient, sheetsClient, prefs) {
        override suspend fun createFreshWorkbook(year: Int, copyHistory: Boolean): Result<String> {
            if (fail) return Result.Error(java.io.IOException("Drive quota"))
            prefs.setSpreadsheetIdForYear(year, "fresh_id")
            prefs.setSpreadsheetId("fresh_id")
            return Result.Success("fresh_id")
        }
    }

    private fun freshCoordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        prefs: FakePreferencesRepository,
        fail: Boolean,
        state: MutableStateFlow<SettingsUiState>
    ): SettingsSyncCoordinator {
        val http = OkHttpClient.Builder().build()
        val drive = GoogleDriveClient(http, fakeTokenProvider)
        val sheets = GoogleSheetsClient(http, fakeTokenProvider)
        return SettingsSyncCoordinator(
            preferencesRepository = prefs,
            transactionRepository = null,
            syncConfigUseCase = null,
            regenerateDashboardUseCase = null,
            googleDriveClient = drive,
            googleSheetsClient = sheets,
            googleAuthTokenProvider = null,
            workbookResolver = FreshResolver(drive, sheets, prefs, fail),
            accountRepository = null,
            categoryRepository = null,
            scope = scope,
            uiState = state,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testCreateFreshWorkbook_successSwitchesIdAndShowsMessage() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        val state = MutableStateFlow(SettingsUiState(spreadsheetId = "sheet_123"))
        val year = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).year

        freshCoordinator(this, prefs, fail = false, state = state).onCreateFreshWorkbook()

        assertEquals("fresh_id", prefs.yearMap[year])
        assertEquals("fresh_id", prefs.activeSpreadsheetId)
        assertEquals("fresh_id", state.value.spreadsheetId)
        assertFalse(state.value.isProvisioning)
        assertEquals(null, state.value.errorMessage)
    }

    @Test
    fun testCreateFreshWorkbook_failureIsShownAndStoredIdUnchanged() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        val state = MutableStateFlow(SettingsUiState(spreadsheetId = "sheet_123"))

        freshCoordinator(this, prefs, fail = true, state = state).onCreateFreshWorkbook()

        assertTrue(prefs.yearMap.isEmpty())
        assertEquals("sheet_123", prefs.activeSpreadsheetId)
        assertEquals("sheet_123", state.value.spreadsheetId)
        assertFalse(state.value.isProvisioning)
        val error = state.value.errorMessage
        assertNotNull(error)
        assertTrue(error is UiText.DynamicString)
        val msg = (error as UiText.DynamicString).value
        assertTrue("Expected safe code, got: $msg", msg.contains("fresh workbook, IOException"))
        assertFalse("Raw message must never appear in errorMessage", msg.contains("Drive quota"))
    }

    @Test
    fun testCreateFreshWorkbook_copyHistoryShowsCopyMessage() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        val state = MutableStateFlow(SettingsUiState())
        val coord = freshCoordinator(this, prefs, fail = false, state = state)
        coord.onCreateFreshWorkbook(copyHistory = true)

        assertEquals(com.financeautomation.R.string.fresh_workbook_msg_copy_history, state.value.userMessage?.resId)
    }

    @Test
    fun testCreateFreshWorkbook_startEmptyShowsEmptyMessage() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        val state = MutableStateFlow(SettingsUiState())
        val coord = freshCoordinator(this, prefs, fail = false, state = state)
        coord.onCreateFreshWorkbook(copyHistory = false)

        assertEquals(com.financeautomation.R.string.fresh_workbook_msg_empty, state.value.userMessage?.resId)
    }

    @Test
    fun cancelCreatesNothing() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val driveClient = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                calls += chain.request().url.toString()
                chain.proceed(chain.request())
            }.build(),
            fakeTokenProvider
        )
        // User cancels dialog -> onCreateFreshWorkbook is never called, 0 Drive calls made
        assertTrue("No Drive calls when fresh workbook creation is cancelled", calls.isEmpty())
    }

    @Test
    fun testCreateFreshWorkbook_copyHistoryWithRequeueFailureShowsRetryMessage() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        prefs.setPreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR, "2026")
        val state = MutableStateFlow(SettingsUiState())
        val coord = freshCoordinator(this, prefs, fail = false, state = state)
        coord.onCreateFreshWorkbook(copyHistory = true)

        assertEquals(com.financeautomation.R.string.fresh_workbook_msg_copy_history_retry, state.value.userMessage?.resId)
    }

    @Test
    fun testSaveId_accessibleIdIsSavedForCurrentYear() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        val sheets = AccessSheetsClient(OkHttpClient.Builder().build(), fakeTokenProvider, setOf("new_sheet_id"))
        val state = MutableStateFlow(SettingsUiState(spreadsheetId = "sheet_123"))
        val year = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).year

        idCoordinator(this, prefs, sheets, state).onSpreadsheetIdChanged("  new_sheet_id ")

        assertEquals("new_sheet_id", prefs.yearMap[year])
        assertEquals("new_sheet_id", state.value.spreadsheetId)
        assertEquals(R.string.settings_msg_spreadsheet_id_saved, state.value.userMessage?.resId)
        assertEquals(null, state.value.errorMessage)
    }

    @Test
    fun testSaveId_inaccessibleIdIsRejectedAndNotSaved() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        val sheets = AccessSheetsClient(OkHttpClient.Builder().build(), fakeTokenProvider, emptySet())
        val state = MutableStateFlow(SettingsUiState(spreadsheetId = "sheet_123"))

        idCoordinator(this, prefs, sheets, state).onSpreadsheetIdChanged("outside_sheet_id")

        assertTrue(prefs.yearMap.isEmpty())
        assertEquals("sheet_123", prefs.activeSpreadsheetId)
        assertEquals("sheet_123", state.value.spreadsheetId)
        assertEquals(R.string.settings_err_spreadsheet_id_rejected, state.value.errorMessage?.resId)
    }

    private class FakeTripTagRepo(
        private var tags: List<TripTag> = emptyList()
    ) : TripTagRepository {
        var activeTagId: String? = null
        override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> = flowOf(tags)
        override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> = tags
        override suspend fun getActiveTripTags(): List<TripTag> = tags.filter { it.isActive }
        override fun getActiveTag(): Flow<TripTag?> = flowOf(tags.find { it.isActive })
        override suspend fun getActiveTagSync(): TripTag? = tags.find { it.isActive }
        override suspend fun getTagById(id: String): TripTag? = tags.find { it.id == id }
        override suspend fun getTagByName(name: String): TripTag? = tags.find { it.name.equals(name, ignoreCase = true) }
        override suspend fun insertTripTag(name: String): TripTag {
            val tag = TripTag(id = "tag_new", name = name, isActive = false)
            tags = tags + tag
            return tag
        }
        override suspend fun updateTripTag(tripTag: TripTag) {
            tags = tags.map { if (it.id == tripTag.id) tripTag else it }
        }
        override suspend fun setActiveTag(id: String?) {
            activeTagId = id
            tags = tags.map { it.copy(isActive = it.id == id) }
        }
        override suspend fun archiveTripTag(id: String) {}
        override suspend fun unarchiveTripTag(id: String) {}
        override suspend fun deleteTripTag(id: String) {
            tags = tags.filter { it.id != id }
        }
    }

    @Test
    fun testSignOut_clearsStoredAccount() = runBlocking<Unit> {
        val prefs = FakePreferencesRepository()
        prefs.email = "owner@gmail.com"
        val state = MutableStateFlow(SettingsUiState(googleAccountEmail = "owner@gmail.com"))

        freshCoordinator(this, prefs, fail = false, state = state).onGoogleSignOut(null)

        assertEquals(null, prefs.email)
        assertEquals(null, state.value.googleAccountEmail)
        assertEquals(R.string.settings_msg_signed_out, state.value.userMessage?.resId)
    }

    @Test
    fun testRemoveFriend_blockedWithOpenBalance() = runBlocking<Unit> {
        val friendWithBalance = Companion(id = "comp_bob", name = "Bob", balance = 500.0)
        val peopleRepo = FakePeopleRepo(listOf(friendWithBalance))
        val prefs = FakePreferencesRepository()
        val state = MutableStateFlow(SettingsUiState(companions = listOf(friendWithBalance)))

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = prefs,
            peopleRepository = peopleRepo,
            transactionRepository = null,
            payableDao = null,
            payableDaoProvider = null,
            accountRepository = null,
            categoryRepository = null,
            tripTagRepository = null,
            scope = this,
            uiState = state,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.onRemoveCompanion("comp_bob")

        assertEquals(null, peopleRepo.deletedId)
        assertEquals(R.string.settings_err_cannot_remove_open_balance, state.value.errorMessage?.resId)
        assertEquals(listOf("Bob"), state.value.errorMessage?.args)
    }

    @Test
    fun testRenameFriend_updatesCompanionName() = runBlocking<Unit> {
        val friend = Companion(id = "comp_charlie", name = "Charlie", balance = 0.0)
        val peopleRepo = FakePeopleRepo(listOf(friend))
        val prefs = FakePreferencesRepository()
        val state = MutableStateFlow(SettingsUiState(companions = listOf(friend)))

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = prefs,
            peopleRepository = peopleRepo,
            transactionRepository = null,
            payableDao = null,
            payableDaoProvider = null,
            accountRepository = null,
            categoryRepository = null,
            tripTagRepository = null,
            scope = this,
            uiState = state,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.onRenameCompanion("comp_charlie", "Charles")

        assertEquals("comp_charlie" to "Charles", peopleRepo.updatedName)
        assertEquals(R.string.settings_msg_renamed_companion, state.value.userMessage?.resId)
        assertEquals(listOf("Charles"), state.value.userMessage?.args)
    }

    @Test
    fun testEndTrip_deactivatesTripTag() = runBlocking<Unit> {
        val activeTrip = TripTag(id = "trip_goa", name = "Goa 2026", isActive = true)
        val tripRepo = FakeTripTagRepo(listOf(activeTrip))
        val prefs = FakePreferencesRepository()
        prefs.setActiveTrip("Goa 2026")
        val state = MutableStateFlow(SettingsUiState(activeTripTag = "Goa 2026", tripTags = listOf(activeTrip)))

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = null,
            payableDao = null,
            payableDaoProvider = null,
            accountRepository = null,
            categoryRepository = null,
            tripTagRepository = tripRepo,
            scope = this,
            uiState = state,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.onToggleTripTagActive("trip_goa")

        assertEquals(null, tripRepo.activeTagId)
        assertEquals("", state.value.activeTripTag)
        assertEquals(R.string.settings_msg_deactivated_trip_tag, state.value.userMessage?.resId)
    }

    @Test
    fun testAppLock_enableNeedsSuccessfulAuthentication() = runBlocking {
        val prefs = FakePreferencesRepository()
        val manager = com.financeautomation.domain.security.AppLockManager()
        manager.onColdStart(false)
        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            ioDispatcher = Dispatchers.Unconfined,
            appLockManager = manager
        )
        assertFalse(vm.uiState.value.appLockEnabled)

        vm.onAppLockAuthenticated(false)
        assertFalse(vm.uiState.value.appLockEnabled)
        assertFalse(prefs.getAppLockEnabled())
        assertFalse(manager.isEnabled)

        vm.onAppLockAuthenticated(true)
        assertTrue(vm.uiState.value.appLockEnabled)
        assertTrue(prefs.getAppLockEnabled())
        assertTrue(manager.isEnabled)

        vm.onAppLockDisabled()
        assertFalse(vm.uiState.value.appLockEnabled)
        assertFalse(prefs.getAppLockEnabled())
        assertFalse(manager.isEnabled)
    }

    @Test
    fun testHideRecentsPreview_savesAndUpdatesTheLiveSetting() = runBlocking {
        val prefs = FakePreferencesRepository()
        val privacy = com.financeautomation.domain.security.PrivacySettings()
        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            ioDispatcher = Dispatchers.Unconfined,
            privacySettings = privacy
        )
        vm.setHideRecentsPreview(false)
        assertFalse(privacy.hideRecentsPreview.value)
        assertFalse(prefs.getHideRecentsPreview())
        vm.setHideRecentsPreview(true)
        assertTrue(privacy.hideRecentsPreview.value)
        assertTrue(prefs.getHideRecentsPreview())
        assertTrue(vm.uiState.value.hideRecentsPreview)
    }

    @Test
    fun testLockSettingsStayOnThisDevice() {
        assertTrue(com.financeautomation.core.constants.ConfigConstants.isLocalOnlyPref("app_lock_enabled"))
        assertTrue(com.financeautomation.core.constants.ConfigConstants.isLocalOnlyPref("hide_recents_preview"))
    }

    @Test
    fun testSubscriptionReminderTime_observesAndUpdatesPreference() = runBlocking {
        val prefs = FakePreferencesRepository()
        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            ioDispatcher = Dispatchers.Unconfined
        )
        assertEquals("21:00", vm.uiState.value.subscriptionReminderTime)

        vm.onSubscriptionReminderTimeChanged("08:30")
        assertEquals("08:30", vm.uiState.value.subscriptionReminderTime)
        assertEquals("08:30", prefs.getSubscriptionReminderTimeSync())
    }

    @Test
    fun testSettingsViewModel_collectsWorkbookReplacedNoticeFlow_andClearsOnDismiss() = runBlocking {
        val prefs = FakePreferencesRepository()
        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = FakePeopleRepo(),
            transactionRepository = FakeTransactionRepo(),
            ioDispatcher = Dispatchers.Unconfined
        )
        assertNull(vm.uiState.value.userMessage)

        prefs.setPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING, "true")
        assertNotNull(vm.uiState.value.userMessage)
        assertEquals(R.string.workbook_replaced_notice_partial, vm.uiState.value.userMessage?.resId)

        vm.clearMessages()
        assertNull(vm.uiState.value.userMessage)
        assertNull(prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING))
        assertFalse(prefs.noticePendingFlow.value)
    }
}
