package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * UI state for the Settings screen covering all customization tiers.
 */
data class SettingsUiState(
    val spreadsheetId: String = "",
    val defaultAccount: String = CategoryConstants.ACCOUNT_SBI,
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

    init {
        observePreferences()
        observeCompanions()
        observeAccounts()
        observeCategories()
        observeTripTags()
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

    private fun observeCompanions() {
        viewModelScope.launch(ioDispatcher) {
            peopleRepository.getCompanions().collect { list ->
                _uiState.update { it.copy(companions = list) }
            }
        }
    }

    private fun observeAccounts() {
        accountRepository?.let { repo ->
            viewModelScope.launch(ioDispatcher) {
                repo.getAccounts(includeArchived = false).collect { list ->
                    _uiState.update { it.copy(accounts = list) }
                }
            }
        }
    }

    private fun observeCategories() {
        categoryRepository?.let { repo ->
            viewModelScope.launch(ioDispatcher) {
                repo.getCategories(includeArchived = false).collect { list ->
                    _uiState.update { it.copy(categories = list) }
                }
            }
        }
    }

    private fun observeTripTags() {
        tripTagRepository?.let { repo ->
            viewModelScope.launch(ioDispatcher) {
                repo.getTripTags(includeArchived = false).collect { list ->
                    _uiState.update { it.copy(tripTags = list) }
                }
            }
        }
    }

    // --- Google Identity & Automated Provisioning ---

    fun getGoogleAuthTokenProvider(): GoogleAuthTokenProvider? = googleAuthTokenProvider

    fun onGoogleSignInSuccess(email: String) {
        if (email.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setGoogleAccountEmail(email)
            _uiState.update {
                it.copy(
                    googleAccountEmail = email,
                    userMessage = "Signed in as $email"
                )
            }
            syncConfigUseCase?.pull()
            autoProvisionMasterSheet()
        }
    }

    fun onGoogleSignOut() {
        viewModelScope.launch(ioDispatcher) {
            googleAuthTokenProvider?.clearCache()
            preferencesRepository.setGoogleAccountEmail(null)
            _uiState.update {
                it.copy(
                    googleAccountEmail = null,
                    userMessage = "Signed out of Google account"
                )
            }
        }
    }

    fun autoProvisionMasterSheet() {
        val resolver = workbookResolver
        if (resolver != null) {
            viewModelScope.launch(ioDispatcher) {
                _uiState.update { it.copy(isProvisioning = true, errorMessage = null) }
                val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
                val workbookName = "Finance$currentYear"
                when (val res = resolver.resolveForYear(currentYear)) {
                    is Result.Success -> {
                        val spreadsheetId = res.data
                        preferencesRepository.setSpreadsheetId(spreadsheetId)
                        regenerateDashboardUseCase?.invoke()
                        syncConfigUseCase?.mirrorToCloud()
                        _uiState.update {
                            it.copy(
                                spreadsheetId = spreadsheetId,
                                isProvisioning = false,
                                userMessage = "Successfully provisioned $workbookName master workbook"
                            )
                        }
                    }
                    is Result.Error -> {
                        _uiState.update {
                            it.copy(
                                isProvisioning = false,
                                errorMessage = res.message ?: "Failed to provision workbook: ${res.throwable.message}"
                            )
                        }
                    }
                    is Result.Loading -> {}
                }
            }
            return
        }

        val driveClient = googleDriveClient ?: return
        val sheetsClient = googleSheetsClient ?: return

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isProvisioning = true, errorMessage = null) }

            val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
            val workbookName = "Finance$currentYear"

            // 1. Search Google Drive using appProperties marker (app=FinanceAutomation, fiscalYear=YYYY)
            when (val searchResult = driveClient.findWorkbookForYear(currentYear)) {
                is Result.Success -> {
                    val existingId = searchResult.data
                    if (!existingId.isNullOrBlank()) {
                        // Found existing master sheet
                        preferencesRepository.setSpreadsheetIdForYear(currentYear, existingId)
                        val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
                        val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
                        sheetsClient.provisionMasterWorkbook(existingId, accounts, categories)
                        regenerateDashboardUseCase?.invoke()
                        _uiState.update {
                            it.copy(
                                spreadsheetId = existingId,
                                isProvisioning = false,
                                userMessage = "Connected and formatted $workbookName master workbook"
                            )
                        }
                    } else {
                        // 2. Spreadsheet does not exist: Provision new workbook
                        val folderResult = driveClient.findOrCreateFolder("Personal Finance")
                        if (folderResult is Result.Error) {
                            _uiState.update {
                                it.copy(
                                    isProvisioning = false,
                                    errorMessage = "Failed to create folder 'Personal Finance': ${folderResult.throwable.message}"
                                )
                            }
                            return@launch
                        }
                        val folderId = (folderResult as Result.Success).data

                        val createResult = driveClient.findOrCreateSpreadsheet(folderId, workbookName, currentYear)
                        if (createResult is Result.Error) {
                            _uiState.update {
                                it.copy(
                                    isProvisioning = false,
                                    errorMessage = "Failed to create spreadsheet '$workbookName': ${createResult.throwable.message}"
                                )
                            }
                            return@launch
                        }
                        val newSpreadsheetId = (createResult as Result.Success).data

                        // Provision tabs: Dashboard, Template, and current month
                        val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
                        val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
                        when (val provResult = sheetsClient.provisionMasterWorkbook(newSpreadsheetId, accounts, categories)) {
                            is Result.Success -> {
                                preferencesRepository.setSpreadsheetIdForYear(currentYear, newSpreadsheetId)
                                regenerateDashboardUseCase?.invoke()
                                syncConfigUseCase?.mirrorToCloud()
                                _uiState.update {
                                    it.copy(
                                        spreadsheetId = newSpreadsheetId,
                                        isProvisioning = false,
                                        userMessage = "Successfully provisioned $workbookName master workbook"
                                    )
                                }
                            }
                            is Result.Error -> {
                                _uiState.update {
                                    it.copy(
                                        isProvisioning = false,
                                        errorMessage = "Failed to provision workbook tabs: ${provResult.throwable.message}"
                                    )
                                }
                            }
                            is Result.Loading -> {}
                        }
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isProvisioning = false,
                            errorMessage = "Google Drive search failed: ${searchResult.throwable.message}"
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    // --- Google Sheets Connection ---

    fun onSpreadsheetIdChanged(newId: String) {
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setSpreadsheetId(newId.trim())
            _uiState.update { it.copy(userMessage = "Spreadsheet ID saved") }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSyncing = true, syncResult = null, errorMessage = null) }
            when (val result = transactionRepository.syncPendingTransactions()) {
                is Result.Success -> {
                    val count = result.data
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncResult = "Synced $count transaction(s) to Google Sheets",
                            userMessage = "Sync completed successfully"
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            errorMessage = result.message ?: "Sync failed with error: ${result.throwable.message}"
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    // --- Bank & Funding Accounts ---

    fun onDefaultAccountChanged(account: String) {
        viewModelScope.launch(ioDispatcher) {
            val trimmed = account.trim()
            preferencesRepository.setDefaultAccount(trimmed)
            accountRepository?.let { repo ->
                val match = repo.getAccountByName(trimmed)
                if (match != null) {
                    repo.setDefaultAccount(match.id)
                }
            }
            _uiState.update { it.copy(defaultAccount = trimmed, userMessage = "Default account set to $trimmed") }
        }
    }

    fun onAddAccount(name: String, lastFourDigits: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Account name cannot be blank") }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            val repo = accountRepository
            if (repo != null) {
                val existing = repo.getAccountByName(trimmed)
                if (existing != null) {
                    _uiState.update { it.copy(errorMessage = "Account '$trimmed' already exists") }
                    return@launch
                }
                val newAcc = Account(
                    id = "acc_${UUID.randomUUID().toString().take(8)}",
                    name = trimmed,
                    lastFourDigits = lastFourDigits?.trim()?.ifEmpty { null },
                    isDefault = false,
                    isArchived = false,
                    displayOrder = _uiState.value.accounts.size
                )
                repo.insertAccount(newAcc)
                _uiState.update { it.copy(userMessage = "Added account $trimmed") }
            } else {
                _uiState.update { it.copy(userMessage = "Added account $trimmed") }
            }
        }
    }

    fun onArchiveAccount(accountId: String) {
        viewModelScope.launch(ioDispatcher) {
            accountRepository?.archiveAccount(accountId)
            _uiState.update { it.copy(userMessage = "Account archived") }
        }
    }

    // --- Expense Categories & Living Burn ---

    fun onAddCategory(name: String, isExcludedFromLivingBurn: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Category name cannot be blank") }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            val repo = categoryRepository
            if (repo != null) {
                val existing = repo.getCategoryByName(trimmed)
                if (existing != null) {
                    _uiState.update { it.copy(errorMessage = "Category '$trimmed' already exists") }
                    return@launch
                }
                val newCat = Category(
                    id = "cat_${UUID.randomUUID().toString().take(8)}",
                    name = trimmed,
                    isDefault = false,
                    isExcludedFromLivingBurn = isExcludedFromLivingBurn,
                    isArchived = false,
                    displayOrder = _uiState.value.categories.size
                )
                repo.insertCategory(newCat)
                _uiState.update { it.copy(userMessage = "Added category $trimmed") }
            } else {
                _uiState.update { it.copy(userMessage = "Added category $trimmed") }
            }
        }
    }

    fun onToggleCategoryLivingBurnExclusion(categoryId: String, isExcluded: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            categoryRepository?.setExcludedFromLivingBurn(categoryId, isExcluded)
            val msg = if (isExcluded) "Category excluded from living burn" else "Category included in living burn"
            _uiState.update { it.copy(userMessage = msg) }
        }
    }

    fun onArchiveCategory(categoryId: String) {
        viewModelScope.launch(ioDispatcher) {
            categoryRepository?.archiveCategory(categoryId)
            _uiState.update { it.copy(userMessage = "Category archived") }
        }
    }

    // --- Trip & Event Tags ---

    fun onActiveTripTagChanged(tag: String) {
        viewModelScope.launch(ioDispatcher) {
            val trimmed = tag.trim()
            val finalTag = if (trimmed.isEmpty()) null else trimmed
            preferencesRepository.setActiveTrip(finalTag)
            tripTagRepository?.let { repo ->
                if (finalTag != null) {
                    val match = repo.getTagByName(finalTag)
                    repo.setActiveTag(match?.id)
                } else {
                    repo.setActiveTag(null)
                }
            }
            _uiState.update { it.copy(activeTripTag = trimmed) }
        }
    }

    fun onAddTripTag(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Trip tag cannot be blank") }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            tripTagRepository?.insertTripTag(trimmed)
            _uiState.update { it.copy(userMessage = "Added trip tag $trimmed") }
        }
    }

    fun onToggleTripTagActive(tagId: String) {
        viewModelScope.launch(ioDispatcher) {
            val repo = tripTagRepository ?: return@launch
            val tag = repo.getTagById(tagId) ?: return@launch
            if (tag.isActive) {
                repo.setActiveTag(null)
                preferencesRepository.setActiveTrip(null)
                _uiState.update { it.copy(activeTripTag = "", userMessage = "Deactivated trip tag") }
            } else {
                repo.setActiveTag(tagId)
                preferencesRepository.setActiveTrip(tag.name)
                _uiState.update { it.copy(activeTripTag = tag.name, userMessage = "Activated trip tag '${tag.name}'") }
            }
        }
    }

    // --- Friends & Companions ---

    fun onAddCompanion(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Friend name cannot be blank") }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            val existing = peopleRepository.getCompanionByName(trimmed)
            if (existing != null) {
                _uiState.update { it.copy(errorMessage = "A companion with this name already exists") }
                return@launch
            }

            val newId = "comp_${UUID.randomUUID().toString().take(8)}"
            peopleRepository.insertCompanion(Companion(id = newId, name = trimmed))
            _uiState.update { it.copy(userMessage = "Added companion $trimmed") }
        }
    }

    fun onRenameCompanion(companionId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Companion name cannot be blank") }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            peopleRepository.updateCompanionName(companionId, trimmed)
            _uiState.update { it.copy(userMessage = "Renamed companion to $trimmed") }
        }
    }

    fun onRemoveCompanion(companionId: String) {
        viewModelScope.launch(ioDispatcher) {
            peopleRepository.deleteCompanionById(companionId)
            _uiState.update { it.copy(userMessage = "Companion removed") }
        }
    }

    // --- Background & Settle Delay ---

    fun onSettleDelayChanged(seconds: Int) {
        val bounded = seconds.coerceIn(30, 300)
        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.setSettleDelaySeconds(bounded)
            _uiState.update { it.copy(settleDelaySeconds = bounded, userMessage = "Settle delay set to ${bounded}s") }
        }
    }

    // --- Config Cloud Sync & Dashboard Regeneration ---

    fun onMirrorConfigToCloud() {
        val useCase = syncConfigUseCase ?: return
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isConfigSyncing = true, errorMessage = null) }
            when (val res = useCase.mirrorToCloud()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isConfigSyncing = false,
                            userMessage = "Configuration successfully mirrored to Google Sheets Config tab"
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isConfigSyncing = false,
                            errorMessage = res.message ?: "Failed to mirror config: ${res.throwable.message}"
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    fun onRestoreConfigFromCloud() {
        val useCase = syncConfigUseCase ?: return
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isConfigSyncing = true, errorMessage = null) }
            when (val res = useCase.restoreFromCloud()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isConfigSyncing = false,
                            userMessage = "Restored configuration from Google Sheets Config tab"
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isConfigSyncing = false,
                            errorMessage = res.message ?: "Failed to restore config: ${res.throwable.message}"
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    fun onRegenerateDashboard() {
        val sheetsClient = googleSheetsClient ?: return
        viewModelScope.launch(ioDispatcher) {
            val spreadsheetId = preferencesRepository.getSpreadsheetIdSync() ?: return@launch
            android.util.Log.d("SettingsViewModel", "onRegenerateDashboard invoked")
            _uiState.update { it.copy(isRegeneratingDashboard = true, errorMessage = null) }
            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
            when (val provRes = sheetsClient.provisionMasterWorkbook(spreadsheetId, accounts, categories)) {
                is Result.Success -> {
                    android.util.Log.d("SettingsViewModel", "Master workbook provision succeeded")
                    regenerateDashboardUseCase?.invoke()
                    val currentMonthTab = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                        .format(DateTimeFormatter.ofPattern("MMMyy", Locale.US))
                    _uiState.update {
                        it.copy(
                            isRegeneratingDashboard = false,
                            userMessage = "Master Workbook (Dashboard, Template, $currentMonthTab) formatted & updated to v2 template"
                        )
                    }
                }
                is Result.Error -> {
                    val statusCode = (provRes.throwable as? com.financeautomation.data.remote.GoogleSheetsApiException)?.statusCode ?: -1
                    val errClass = provRes.throwable?.javaClass?.simpleName ?: "UnknownException"
                    android.util.Log.e("SettingsViewModel", "Master workbook provision failed with class=$errClass statusCode=$statusCode")
                    _uiState.update {
                        it.copy(
                            isRegeneratingDashboard = false,
                            errorMessage = "Failed to format master workbook (status $statusCode, $errClass). Check network connection."
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

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
