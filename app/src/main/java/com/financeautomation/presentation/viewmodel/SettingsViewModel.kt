package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.ProfileAvatarManager
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.usecase.RegenerateDashboardUseCase
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Settings screen covering all customization tiers.
 */
data class SettingsUiState(
    val spreadsheetId: String = "",
    val workbookName: String? = null,
    val defaultAccount: String = CategoryConstants.ACCOUNT_OTHER,
    val activeTripTag: String = "",
    val settleDelaySeconds: Int = 120,
    val googleAccountEmail: String? = null,
    /** Set while the user is asked to confirm switching to another Google account. */
    val pendingAccountSwitch: com.financeautomation.data.account.AccountSwitchPrompt? = null,
    val googleAccountDisplayName: String? = null,
    val accounts: List<Account> = emptyList(),
    val accountTransactionCounts: Map<String, Int> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val companions: List<Companion> = emptyList(),
    val companionOpenItemCounts: Map<String, Int> = emptyMap(),
    val tripTags: List<TripTag> = emptyList(),
    /** Net expenditure per trip, keyed by the lower case trip name. */
    val tripSummaries: Map<String, com.financeautomation.domain.usecase.TripSummary> = emptyMap(),
    val isSyncing: Boolean = false,
    val isConfigSyncing: Boolean = false,
    val isRegeneratingDashboard: Boolean = false,
    val isProvisioning: Boolean = false,
    val syncResult: String? = null,
    val userMessage: UiText? = null,
    val errorMessage: UiText? = null,
    val developerMode: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLockEnabled: Boolean = false,
    val hideRecentsPreview: Boolean = false,
    val subscriptionReminderTime: String = PreferencesRepository.DEFAULT_SUBSCRIPTION_REMINDER_TIME
)

/**
 * ViewModel managing 100% config-driven customization: Accounts, Categories with Living Burn flags,
 * Companions, Trip Tags, Settle Delay, Google Sheets Config mirroring, and Dashboard regeneration.
 */
class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val peopleRepository: PeopleRepository,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository? = null,
    private val categoryRepository: CategoryRepository? = null,
    private val tripTagRepository: TripTagRepository? = null,
    private val syncConfigUseCase: SyncConfigUseCase? = null,
    private val regenerateDashboardUseCase: RegenerateDashboardUseCase? = null,
    private val googleDriveClient: GoogleDriveClient? = null,
    private val googleSheetsClient: GoogleSheetsClient? = null,
    private val googleAuthTokenProvider: GoogleAuthTokenProvider? = null,
    private val workbookResolver: WorkbookResolver? = null,
    private val profileAvatarManager: ProfileAvatarManager? = null,
    directPayableDao: PayableDao? = null,
    payableDaoProvider: (suspend () -> PayableDao)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val appLockManager: com.financeautomation.domain.security.AppLockManager? = null,
    private val privacySettings: com.financeautomation.domain.security.PrivacySettings? = null,
    private val accountSwitchGuard: com.financeautomation.data.account.AccountSwitchGuard? = null,
    private val budgetRepository: com.financeautomation.domain.repository.BudgetRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val customizationCoordinator = SettingsCustomizationCoordinator(
        preferencesRepository = preferencesRepository,
        peopleRepository = peopleRepository,
        transactionRepository = transactionRepository,
        payableDao = directPayableDao,
        payableDaoProvider = payableDaoProvider,
        accountRepository = accountRepository,
        categoryRepository = categoryRepository,
        tripTagRepository = tripTagRepository,
        budgetRepository = budgetRepository,
        scope = viewModelScope,
        uiState = _uiState,
        ioDispatcher = ioDispatcher
    )

    private val syncCoordinator = SettingsSyncCoordinator(
        preferencesRepository = preferencesRepository,
        transactionRepository = transactionRepository,
        syncConfigUseCase = syncConfigUseCase,
        regenerateDashboardUseCase = regenerateDashboardUseCase,
        googleDriveClient = googleDriveClient,
        googleSheetsClient = googleSheetsClient,
        googleAuthTokenProvider = googleAuthTokenProvider,
        workbookResolver = workbookResolver,
        accountRepository = accountRepository,
        categoryRepository = categoryRepository,
        profileAvatarManager = profileAvatarManager,
        accountSwitchGuard = accountSwitchGuard,
        scope = viewModelScope,
        uiState = _uiState,
        ioDispatcher = ioDispatcher
    )

    init {
        observePreferences()
        customizationCoordinator.observeEntities()
        observeAccountTransactionCounts()
    }

    /**
     * Number of non-void transactions per account in the current month (Asia/Kolkata), for the Accounts screen.
     */
    private fun observeAccountTransactionCounts() {
        val repo = accountRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            val zone = java.time.ZoneId.of("Asia/Kolkata")
            val monthStart = java.time.LocalDate.now(zone).withDayOfMonth(1)
            val from = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
            repo.getAccountTransactionCounts(from, to).collect { counts ->
                _uiState.update { it.copy(accountTransactionCounts = counts) }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getSpreadsheetId().collect { id ->
                _uiState.update { it.copy(spreadsheetId = id ?: "") }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getSpreadsheetTitle().collect { title ->
                _uiState.update { it.copy(workbookName = title) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getDefaultAccount().collect { account ->
                _uiState.update { it.copy(defaultAccount = account) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getActiveTrip().collect { trip ->
                _uiState.update { it.copy(activeTripTag = trip ?: "") }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getGoogleAccountEmail().collect { email ->
                _uiState.update { it.copy(googleAccountEmail = email) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getGoogleAccountDisplayName().collect { name ->
                _uiState.update { it.copy(googleAccountDisplayName = name) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getSettleDelaySeconds().collect { seconds ->
                _uiState.update { it.copy(settleDelaySeconds = seconds) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getDeveloperMode().collect { devMode ->
                _uiState.update { it.copy(developerMode = devMode) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getThemeMode().collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            val summaries = com.financeautomation.domain.usecase.TripSummaryUseCase()
            transactionRepository.getTransactions().collect { rows ->
                _uiState.update { it.copy(tripSummaries = summaries.byTrip(rows)) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            val lockOn = preferencesRepository.getAppLockEnabled()
            val hide = preferencesRepository.getHideRecentsPreview()
            _uiState.update { it.copy(appLockEnabled = lockOn, hideRecentsPreview = hide) }
        }
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getSubscriptionReminderTime().collect { time ->
                _uiState.update { it.copy(subscriptionReminderTime = time) }
            }
        }
    }

    fun onSubscriptionReminderTimeChanged(time: String) {
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setSubscriptionReminderTime(time)
            _uiState.update { it.copy(subscriptionReminderTime = PreferencesRepository.normalizeReminderTime(time)) }
        }
    }

    // --- Google Identity & Automated Provisioning ---

    fun getGoogleAuthTokenProvider(): GoogleAuthTokenProvider? = syncCoordinator.getGoogleAuthTokenProvider()

    fun onGoogleSignInSuccess(email: String, displayName: String? = null, photoUrl: String? = null) =
        syncCoordinator.onGoogleSignInSuccess(email, displayName, photoUrl)

    fun onGoogleSignOut(context: android.content.Context? = null) = syncCoordinator.onGoogleSignOut(context)
    fun confirmAccountSwitch() = syncCoordinator.confirmAccountSwitch()
    fun cancelAccountSwitch() = syncCoordinator.cancelAccountSwitch()

    fun autoProvisionMasterSheet() = syncCoordinator.autoProvisionMasterSheet()

    // --- Google Sheets Connection & Sync ---

    fun onSpreadsheetIdChanged(newId: String) = syncCoordinator.onSpreadsheetIdChanged(newId)

    fun onCreateFreshWorkbook() = syncCoordinator.onCreateFreshWorkbook()

    fun onSyncNow() = syncCoordinator.onSyncNow()

    // --- Bank & Funding Accounts ---

    fun onDefaultAccountChanged(account: String) = customizationCoordinator.onDefaultAccountChanged(account)

    fun onAddAccount(name: String, lastFourDigits: String? = null) = customizationCoordinator.onAddAccount(name, lastFourDigits)

    fun onRenameAccount(accountId: String, newName: String, lastFourDigits: String? = null) = customizationCoordinator.onRenameAccount(accountId, newName, lastFourDigits)

    fun onArchiveAccount(accountId: String) = customizationCoordinator.onArchiveAccount(accountId)

    // --- Expense Categories & Living Burn ---

    fun onAddCategory(name: String, isExcludedFromLivingBurn: Boolean = false, colorName: String = "default_tag") =
        customizationCoordinator.onAddCategory(name, isExcludedFromLivingBurn, colorName)

    fun onRenameCategory(categoryId: String, newName: String) = customizationCoordinator.onRenameCategory(categoryId, newName)

    fun onToggleCategoryLivingBurnExclusion(categoryId: String, isExcluded: Boolean) = customizationCoordinator.onToggleCategoryLivingBurnExclusion(categoryId, isExcluded)

    fun onArchiveCategory(categoryId: String) = customizationCoordinator.onArchiveCategory(categoryId)

    // --- Trip & Event Tags ---

    fun onActiveTripTagChanged(tag: String) = customizationCoordinator.onActiveTripTagChanged(tag)

    fun onAddTripTag(name: String) = customizationCoordinator.onAddTripTag(name)

    fun onToggleTripTagActive(tagId: String) = customizationCoordinator.onToggleTripTagActive(tagId)

    // --- Friends & Companions ---

    fun onAddCompanion(name: String) = customizationCoordinator.onAddCompanion(name)

    fun onRenameCompanion(companionId: String, newName: String) = customizationCoordinator.onRenameCompanion(companionId, newName)

    fun onRemoveCompanion(companionId: String) = customizationCoordinator.onRemoveCompanion(companionId)
    fun onRemoveTripTag(name: String) = customizationCoordinator.onRemoveTripTag(name)

    // --- Background & Settle Delay ---

    fun onSettleDelayChanged(seconds: Int) {
        val bounded = seconds.coerceIn(30, 300)
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setSettleDelaySeconds(bounded)
            _uiState.update { it.copy(settleDelaySeconds = bounded, userMessage = UiText.res(R.string.settings_msg_settle_delay_set, bounded)) }
        }
    }

    // --- Developer Mode & Theme ---

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setDeveloperMode(enabled)
            _uiState.update { it.copy(developerMode = enabled) }
        }
    }

    /**
     * Called after the phone confirmed the owner's identity. Turns the app lock on; without a successful
     * authentication nothing changes.
     */
    fun onAppLockAuthenticated(success: Boolean) {
        if (!success) return
        if (appLockManager?.enable(authenticated = true) == false) return
        privacySettings?.setAppLockOn(true)
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setAppLockEnabled(true)
            _uiState.update { it.copy(appLockEnabled = true) }
        }
    }

    fun onAppLockDisabled() {
        appLockManager?.disable()
        privacySettings?.setAppLockOn(false)
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setAppLockEnabled(false)
            _uiState.update { it.copy(appLockEnabled = false) }
        }
    }

    fun setHideRecentsPreview(enabled: Boolean) {
        privacySettings?.set(enabled)
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setHideRecentsPreview(enabled)
            _uiState.update { it.copy(hideRecentsPreview = enabled) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setThemeMode(mode)
            _uiState.update { it.copy(themeMode = mode) }
        }
    }

    private var tapCount = 0
    private var lastTapTimestamp = 0L

    fun onVersionTapped(currentTimeMillis: Long = System.currentTimeMillis()) {
        if (currentTimeMillis - lastTapTimestamp <= 3000L) {
            tapCount++
        } else {
            tapCount = 1
        }
        lastTapTimestamp = currentTimeMillis

        if (tapCount >= 7) {
            tapCount = 0
            setDeveloperMode(true)
            _uiState.update { it.copy(userMessage = UiText.dynamic("Developer mode unlocked")) }
        }
    }

    // --- Config Cloud Sync & Dashboard Regeneration ---

    fun onMirrorConfigToCloud() = syncCoordinator.onMirrorConfigToCloud()

    fun onRestoreConfigFromCloud() = syncCoordinator.onRestoreConfigFromCloud()

    fun onRegenerateDashboard() = syncCoordinator.onRegenerateDashboard()

    fun clearMessages() {
        _uiState.update { it.copy(userMessage = null, errorMessage = null) }
    }

    fun setErrorMessage(message: UiText?) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun setErrorMessage(message: String) {
        _uiState.update { it.copy(errorMessage = UiText.dynamic(message)) }
    }

    companion object {
        fun provideFactory(
            preferencesRepository: PreferencesRepository,
            peopleRepository: PeopleRepository,
            transactionRepository: TransactionRepository,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = provideFactory(
            preferencesRepository = preferencesRepository,
            peopleRepository = peopleRepository,
            transactionRepository = transactionRepository,
            accountRepository = null,
            categoryRepository = null,
            tripTagRepository = null,
            syncConfigUseCase = null,
            regenerateDashboardUseCase = null,
            ioDispatcher = ioDispatcher
        )

        fun provideFactory(
            preferencesRepository: PreferencesRepository,
            peopleRepository: PeopleRepository,
            transactionRepository: TransactionRepository,
            accountRepository: AccountRepository? = null,
            categoryRepository: CategoryRepository? = null,
            tripTagRepository: TripTagRepository? = null,
            syncConfigUseCase: SyncConfigUseCase? = null,
            regenerateDashboardUseCase: RegenerateDashboardUseCase? = null,
            googleDriveClient: GoogleDriveClient? = null,
            googleSheetsClient: GoogleSheetsClient? = null,
            googleAuthTokenProvider: GoogleAuthTokenProvider? = null,
            workbookResolver: WorkbookResolver? = null,
            profileAvatarManager: ProfileAvatarManager? = null,
            payableDao: PayableDao? = null,
            payableDaoProvider: (suspend () -> PayableDao)? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            appLockManager: com.financeautomation.domain.security.AppLockManager? = null,
            privacySettings: com.financeautomation.domain.security.PrivacySettings? = null,
            accountSwitchGuard: com.financeautomation.data.account.AccountSwitchGuard? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    preferencesRepository = preferencesRepository,
                    peopleRepository = peopleRepository,
                    transactionRepository = transactionRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    tripTagRepository = tripTagRepository,
                    syncConfigUseCase = syncConfigUseCase,
                    regenerateDashboardUseCase = regenerateDashboardUseCase,
                    googleDriveClient = googleDriveClient,
                    googleSheetsClient = googleSheetsClient,
                    googleAuthTokenProvider = googleAuthTokenProvider,
                    workbookResolver = workbookResolver,
                    profileAvatarManager = profileAvatarManager,
                    directPayableDao = payableDao,
                    payableDaoProvider = payableDaoProvider,
                    ioDispatcher = ioDispatcher,
                    appLockManager = appLockManager,
                    accountSwitchGuard = accountSwitchGuard,
                    privacySettings = privacySettings
                ) as T
            }
        }
    }
}
