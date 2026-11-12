package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
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
    val defaultAccount: String = CategoryConstants.ACCOUNT_OTHER,
    val activeTripTag: String = "",
    val settleDelaySeconds: Int = 120,
    val googleAccountEmail: String? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val companions: List<Companion> = emptyList(),
    val tripTags: List<TripTag> = emptyList(),
    val isSyncing: Boolean = false,
    val isConfigSyncing: Boolean = false,
    val isRegeneratingDashboard: Boolean = false,
    val isProvisioning: Boolean = false,
    val syncResult: String? = null,
    val userMessage: String? = null,
    val errorMessage: String? = null
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val customizationCoordinator = SettingsCustomizationCoordinator(
        preferencesRepository = preferencesRepository,
        peopleRepository = peopleRepository,
        accountRepository = accountRepository,
        categoryRepository = categoryRepository,
        tripTagRepository = tripTagRepository,
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
        scope = viewModelScope,
        uiState = _uiState,
        ioDispatcher = ioDispatcher
    )

    init {
        observePreferences()
        customizationCoordinator.observeEntities()
    }

    private fun observePreferences() {
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getSpreadsheetId().collect { id ->
                _uiState.update { it.copy(spreadsheetId = id ?: "") }
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
            preferencesRepository.getSettleDelaySeconds().collect { seconds ->
                _uiState.update { it.copy(settleDelaySeconds = seconds) }
            }
        }
    }

    // --- Google Identity & Automated Provisioning ---

    fun getGoogleAuthTokenProvider(): GoogleAuthTokenProvider? = syncCoordinator.getGoogleAuthTokenProvider()

    fun onGoogleSignInSuccess(email: String) = syncCoordinator.onGoogleSignInSuccess(email)

    fun onGoogleSignOut() = syncCoordinator.onGoogleSignOut()

    fun autoProvisionMasterSheet() = syncCoordinator.autoProvisionMasterSheet()

    // --- Google Sheets Connection & Sync ---

    fun onSpreadsheetIdChanged(newId: String) = syncCoordinator.onSpreadsheetIdChanged(newId)

    fun onSyncNow() = syncCoordinator.onSyncNow()

    // --- Bank & Funding Accounts ---

    fun onDefaultAccountChanged(account: String) = customizationCoordinator.onDefaultAccountChanged(account)

    fun onAddAccount(name: String, lastFourDigits: String? = null) = customizationCoordinator.onAddAccount(name, lastFourDigits)

    fun onArchiveAccount(accountId: String) = customizationCoordinator.onArchiveAccount(accountId)

    // --- Expense Categories & Living Burn ---

    fun onAddCategory(name: String, isExcludedFromLivingBurn: Boolean = false) = customizationCoordinator.onAddCategory(name, isExcludedFromLivingBurn)

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

    // --- Background & Settle Delay ---

    fun onSettleDelayChanged(seconds: Int) {
        val bounded = seconds.coerceIn(30, 300)
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setSettleDelaySeconds(bounded)
            _uiState.update { it.copy(settleDelaySeconds = bounded, userMessage = "Settle delay set to ${bounded}s") }
        }
    }

    // --- Config Cloud Sync & Dashboard Regeneration ---

    fun onMirrorConfigToCloud() = syncCoordinator.onMirrorConfigToCloud()

    fun onRestoreConfigFromCloud() = syncCoordinator.onRestoreConfigFromCloud()

    fun onRegenerateDashboard() = syncCoordinator.onRegenerateDashboard()

    fun clearMessages() {
        _uiState.update { it.copy(userMessage = null, errorMessage = null) }
    }

    fun setErrorMessage(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
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
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}
