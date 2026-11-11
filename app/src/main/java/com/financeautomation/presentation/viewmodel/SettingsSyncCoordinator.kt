package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsApiException
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.RegenerateDashboardUseCase
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Coordinator managing Google Drive/Sheets auth, automated provisioning, and sync (Phase 10).
 */
class SettingsSyncCoordinator(
    private val preferencesRepository: PreferencesRepository,
    private val transactionRepository: TransactionRepository? = null,
    private val syncConfigUseCase: SyncConfigUseCase?,
    private val regenerateDashboardUseCase: RegenerateDashboardUseCase?,
    private val googleDriveClient: GoogleDriveClient?,
    private val googleSheetsClient: GoogleSheetsClient?,
    private val googleAuthTokenProvider: GoogleAuthTokenProvider?,
    private val workbookResolver: WorkbookResolver?,
    private val accountRepository: AccountRepository?,
    private val categoryRepository: CategoryRepository?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val ioDispatcher: CoroutineDispatcher
) {

    fun getGoogleAuthTokenProvider(): GoogleAuthTokenProvider? = googleAuthTokenProvider

    fun onGoogleSignInSuccess(email: String) {
        if (email.isBlank()) return
        scope.launch(ioDispatcher) {
            preferencesRepository.setGoogleAccountEmail(email)
            uiState.update {
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
        scope.launch(ioDispatcher) {
            googleAuthTokenProvider?.clearCache()
            preferencesRepository.setGoogleAccountEmail(null)
            uiState.update {
                it.copy(
                    googleAccountEmail = null,
                    userMessage = "Signed out of Google account"
                )
            }
        }
    }

    fun autoProvisionMasterSheet() {
        if (uiState.value.isProvisioning) return
        val resolver = workbookResolver
        if (resolver != null) {
            scope.launch(ioDispatcher) {
                uiState.update { it.copy(isProvisioning = true, errorMessage = null) }
                val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
                val workbookName = "Finance$currentYear"
                when (val res = resolver.resolveForYear(currentYear, forceValidateTabs = true)) {
                    is Result.Success -> {
                        val spreadsheetId = res.data
                        preferencesRepository.setSpreadsheetId(spreadsheetId)
                        regenerateDashboardUseCase?.invoke()
                        syncConfigUseCase?.mirrorToCloud()
                        uiState.update {
                            it.copy(
                                spreadsheetId = spreadsheetId,
                                isProvisioning = false,
                                userMessage = "Successfully provisioned $workbookName master workbook"
                            )
                        }
                    }
                    is Result.Error -> {
                        uiState.update {
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

        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isProvisioning = true, errorMessage = null) }

            val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
            val workbookName = "Finance$currentYear"

            when (val searchResult = driveClient.findWorkbookForYear(currentYear)) {
                is Result.Success -> {
                    val existingId = searchResult.data
                    if (!existingId.isNullOrBlank()) {
                        preferencesRepository.setSpreadsheetIdForYear(currentYear, existingId)
                        val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
                        val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
                        sheetsClient.provisionMasterWorkbook(existingId, accounts, categories)
                        regenerateDashboardUseCase?.invoke()
                        uiState.update {
                            it.copy(
                                spreadsheetId = existingId,
                                isProvisioning = false,
                                userMessage = "Connected and formatted $workbookName master workbook"
                            )
                        }
                    } else {
                        val folderResult = driveClient.findOrCreateFolder("Personal Finance")
                        if (folderResult is Result.Error) {
                            uiState.update {
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
                            uiState.update {
                                it.copy(
                                    isProvisioning = false,
                                    errorMessage = "Failed to create spreadsheet '$workbookName': ${createResult.throwable.message}"
                                )
                            }
                            return@launch
                        }
                        val newSpreadsheetId = (createResult as Result.Success).data

                        val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
                        val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
                        when (val provResult = sheetsClient.provisionMasterWorkbook(newSpreadsheetId, accounts, categories)) {
                            is Result.Success -> {
                                preferencesRepository.setSpreadsheetIdForYear(currentYear, newSpreadsheetId)
                                regenerateDashboardUseCase?.invoke()
                                syncConfigUseCase?.mirrorToCloud()
                                uiState.update {
                                    it.copy(
                                        spreadsheetId = newSpreadsheetId,
                                        isProvisioning = false,
                                        userMessage = "Successfully provisioned $workbookName master workbook"
                                    )
                                }
                            }
                            is Result.Error -> {
                                uiState.update {
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
                    uiState.update {
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

    fun onSpreadsheetIdChanged(newId: String) {
        scope.launch(ioDispatcher) {
            preferencesRepository.setSpreadsheetId(newId.trim())
            uiState.update { it.copy(userMessage = "Spreadsheet ID saved") }
        }
    }

    fun onSyncNow() {
        val repo = transactionRepository ?: return
        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isSyncing = true, syncResult = null, errorMessage = null) }
            when (val result = repo.syncPendingTransactions()) {
                is Result.Success -> {
                    val count = result.data
                    uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncResult = "Synced $count transaction(s) to Google Sheets",
                            userMessage = "Sync completed successfully"
                        )
                    }
                }
                is Result.Error -> {
                    uiState.update {
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

    fun onMirrorConfigToCloud() {
        val useCase = syncConfigUseCase ?: return
        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isConfigSyncing = true, errorMessage = null) }
            when (val res = useCase.mirrorToCloud()) {
                is Result.Success -> {
                    uiState.update {
                        it.copy(
                            isConfigSyncing = false,
                            userMessage = "Configuration successfully mirrored to Google Sheets Config tab"
                        )
                    }
                }
                is Result.Error -> {
                    uiState.update {
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
        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isConfigSyncing = true, errorMessage = null) }
            when (val res = useCase.restoreFromCloud()) {
                is Result.Success -> {
                    uiState.update {
                        it.copy(
                            isConfigSyncing = false,
                            userMessage = "Restored configuration from Google Sheets Config tab"
                        )
                    }
                }
                is Result.Error -> {
                    uiState.update {
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
        scope.launch(ioDispatcher) {
            val spreadsheetId = preferencesRepository.getSpreadsheetIdSync() ?: return@launch
            android.util.Log.d("SettingsViewModel", "onRegenerateDashboard invoked")
            uiState.update { it.copy(isRegeneratingDashboard = true, errorMessage = null) }
            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
            when (val provRes = sheetsClient.provisionMasterWorkbook(spreadsheetId, accounts, categories)) {
                is Result.Success -> {
                    android.util.Log.d("SettingsViewModel", "Master workbook provision succeeded")
                    regenerateDashboardUseCase?.invoke()
                    val currentMonthTab = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                        .format(DateTimeFormatter.ofPattern("MMMyy", Locale.US))
                    uiState.update {
                        it.copy(
                            isRegeneratingDashboard = false,
                            userMessage = "Master Workbook (Dashboard, Template, $currentMonthTab) formatted & updated to v2 template"
                        )
                    }
                }
                is Result.Error -> {
                    val statusCode = (provRes.throwable as? GoogleSheetsApiException)?.statusCode ?: -1
                    val errClass = provRes.throwable?.javaClass?.simpleName ?: "UnknownException"
                    android.util.Log.e("SettingsViewModel", "Master workbook provision failed with class=$errClass statusCode=$statusCode")
                    uiState.update {
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
}

