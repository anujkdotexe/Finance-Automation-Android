package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
import com.financeautomation.core.model.Result
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsApiException
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.ProfileAvatarManager
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
    private val profileAvatarManager: ProfileAvatarManager? = null,
    private val accountSwitchGuard: com.financeautomation.data.account.AccountSwitchGuard? = null,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val ioDispatcher: CoroutineDispatcher
) {

    fun getGoogleAuthTokenProvider(): GoogleAuthTokenProvider? = googleAuthTokenProvider

    fun onGoogleSignInSuccess(email: String, displayName: String? = null, photoUrl: String? = null) {
        if (email.isBlank()) return
        scope.launch(ioDispatcher) {
            // A different Google account must never inherit the previous account's ledger: ask before anything is synced
            val guard = accountSwitchGuard
            if (guard != null) {
                val evaluation = guard.evaluate(email)
                if (evaluation.decision == com.financeautomation.data.account.SwitchDecision.CONFIRM_SWITCH) {
                    uiState.update {
                        it.copy(
                            pendingAccountSwitch = com.financeautomation.data.account.AccountSwitchPrompt(
                                email, displayName, photoUrl, evaluation.pendingCount, evaluation.hasLocalData
                            )
                        )
                    }
                    return@launch
                }
                guard.recordOwner(email)
            }
            continueSignIn(email, displayName, photoUrl)
        }
    }

    /** The user confirmed the switch: wipe the previous account's data on this phone, then sign in as the new one. */
    fun confirmAccountSwitch() {
        val prompt = uiState.value.pendingAccountSwitch ?: return
        scope.launch(ioDispatcher) {
            try {
                accountSwitchGuard?.switchTo(prompt.email)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Nothing was switched: stay with the previous account and say so
                uiState.update {
                    it.copy(pendingAccountSwitch = null, errorMessage = UiText.res(R.string.account_switch_failed))
                }
                return@launch
            }
            profileAvatarManager?.deleteAvatar()
            uiState.update { it.copy(pendingAccountSwitch = null) }
            continueSignIn(prompt.email, prompt.displayName, prompt.photoUrl)
        }
    }

    /** The user cancelled: nothing changes and the app stays signed out. */
    fun cancelAccountSwitch() {
        uiState.update { it.copy(pendingAccountSwitch = null) }
        // The picker already made the other account Play Services' last account; sign it out so it is not shown or used
        scope.launch(ioDispatcher) { googleAuthTokenProvider?.signOutGoogle() }
    }

    private suspend fun continueSignIn(email: String, displayName: String?, photoUrl: String?) {
        preferencesRepository.setGoogleAccountEmail(email)
        if (!displayName.isNullOrBlank()) {
            preferencesRepository.setGoogleAccountDisplayName(displayName)
        }
        if (!photoUrl.isNullOrBlank()) {
            preferencesRepository.setGoogleAccountPhotoUrl(photoUrl)
            profileAvatarManager?.downloadAndSaveAvatar(photoUrl)
        }
        uiState.update {
            it.copy(
                googleAccountEmail = email,
                googleAccountDisplayName = displayName ?: it.googleAccountDisplayName,
                userMessage = UiText.res(R.string.settings_msg_signed_in, email)
            )
        }
        syncConfigUseCase?.pull()
        autoProvisionMasterSheet()
        // A run paused by an expired sign-in left its items pending; resume them now
        try {
            transactionRepository?.syncPendingTransactions()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    fun onGoogleSignOut(context: android.content.Context? = null) {
        scope.launch(ioDispatcher) {
            googleAuthTokenProvider?.signOutGoogle()
            // Remember who was signed in, so signing back in with the same account is not questioned
            preferencesRepository.getGoogleAccountEmailSync()?.takeIf { it.isNotBlank() }?.let {
                preferencesRepository.setPreference(com.financeautomation.data.account.AccountSwitchGuard.KEY_LAST_EMAIL, it, isDirty = false)
            }
            preferencesRepository.setGoogleAccountEmail(null)
            preferencesRepository.setGoogleAccountDisplayName(null)
            preferencesRepository.setGoogleAccountPhotoUrl(null)
            profileAvatarManager?.deleteAvatar()
            context?.let { ctx ->
                try {
                    val avatarFile = java.io.File(ctx.filesDir, "google_avatar.png")
                    if (avatarFile.exists()) {
                        avatarFile.delete()
                    }
                } catch (_: Exception) {}
            }
            uiState.update {
                it.copy(
                    googleAccountEmail = null,
                    googleAccountDisplayName = null,
                    userMessage = UiText.res(R.string.settings_msg_signed_out)
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
                                userMessage = UiText.res(R.string.settings_msg_provisioned_workbook, workbookName)
                            )
                        }
                    }
                    is Result.Error -> {
                        uiState.update {
                            it.copy(
                                isProvisioning = false,
                                errorMessage = UiText.res(R.string.settings_err_provision_workbook, res.throwable.message ?: "")
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
                                userMessage = UiText.res(R.string.settings_msg_connected_workbook, workbookName)
                            )
                        }
                    } else {
                        val folderResult = driveClient.findOrCreateFolder("Personal Finance")
                        if (folderResult is Result.Error) {
                            uiState.update {
                                it.copy(
                                    isProvisioning = false,
                                    errorMessage = UiText.res(R.string.settings_err_create_folder, folderResult.throwable.message ?: "")
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
                                    errorMessage = UiText.res(R.string.settings_err_create_spreadsheet, workbookName, createResult.throwable.message ?: "")
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
                                        userMessage = UiText.res(R.string.settings_msg_provisioned_workbook, workbookName)
                                    )
                                }
                            }
                            is Result.Error -> {
                                uiState.update {
                                    it.copy(
                                        isProvisioning = false,
                                        errorMessage = UiText.res(R.string.settings_err_provision_tabs, provResult.throwable.message ?: "")
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
                            errorMessage = UiText.res(R.string.settings_err_drive_search, searchResult.throwable.message ?: "")
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    /**
     * Saves a typed spreadsheet id only if the app can open it. The app has only the drive.file scope,
     * so a workbook created outside the app answers 403 or 404; such ids are rejected and not stored.
     * On success the id is stored for the current year (which also sets the legacy key), because the
     * resolver and the main screen read the per-year key first.
     */
    fun onSpreadsheetIdChanged(newId: String) {
        scope.launch(ioDispatcher) {
            val trimmed = newId.trim()
            val sheetsClient = googleSheetsClient
            if (trimmed.isBlank() || sheetsClient == null) {
                uiState.update { it.copy(userMessage = null, errorMessage = SPREADSHEET_ID_REJECTED_TEXT) }
                return@launch
            }
            when (sheetsClient.getSheetTabsDetailed(trimmed)) {
                is Result.Success -> {
                    val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
                    preferencesRepository.setSpreadsheetIdForYear(currentYear, trimmed)
                    uiState.update {
                        it.copy(spreadsheetId = trimmed, errorMessage = null, userMessage = UiText.res(R.string.settings_msg_spreadsheet_id_saved))
                    }
                }
                is Result.Error -> {
                    uiState.update { it.copy(userMessage = null, errorMessage = SPREADSHEET_ID_REJECTED_TEXT) }
                }
                is Result.Loading -> {}
            }
        }
    }

    /**
     * Creates a new empty workbook and switches the app to it. The old workbook stays in Google Drive.
     * The stored id is changed only on success.
     */
    fun onCreateFreshWorkbook() {
        val resolver = workbookResolver ?: return
        if (uiState.value.isProvisioning) return
        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isProvisioning = true, errorMessage = null) }
            val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
            when (val res = resolver.createFreshWorkbook(currentYear)) {
                is Result.Success -> {
                    uiState.update {
                        it.copy(
                            spreadsheetId = res.data,
                            isProvisioning = false,
                            userMessage = UiText.res(R.string.settings_msg_fresh_workbook_switched, currentYear)
                        )
                    }
                }
                is Result.Error -> {
                    uiState.update {
                        it.copy(
                            isProvisioning = false,
                            errorMessage = UiText.res(R.string.settings_err_fresh_workbook_failed, res.throwable.message ?: "")
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    fun onSyncNow() {
        val repo = transactionRepository ?: return
        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isSyncing = true, syncResult = null, errorMessage = null) }
            try {
                when (val result = repo.syncPendingTransactions(userInitiated = true)) {
                    is Result.Success -> {
                        val count = result.data
                        uiState.update {
                            it.copy(
                                syncResult = "Synced $count transaction(s) to Google Sheets",
                                userMessage = UiText.res(R.string.settings_msg_sync_completed)
                            )
                        }
                    }
                    is Result.Error -> {
                        uiState.update {
                            it.copy(
                                errorMessage = UiText.res(R.string.settings_err_sync_failed, result.throwable.message ?: "")
                            )
                        }
                    }
                    is Result.Loading -> {}
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                uiState.update {
                    it.copy(
                        errorMessage = UiText.res(R.string.settings_err_sync_failed, e.message ?: "")
                    )
                }
            } finally {
                uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun onCheckAllMonths() {
        val repo = transactionRepository ?: return
        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isCheckingAllMonths = true, errorMessage = null) }
            try {
                when (val result = repo.reconcileAllMonths()) {
                    is Result.Success -> {
                        val count = result.data
                        val message = if (count > 0) {
                            UiText.res(R.string.settings_msg_reconciled_all_months, count)
                        } else {
                            UiText.res(R.string.settings_msg_all_months_up_to_date)
                        }
                        uiState.update { it.copy(userMessage = message) }
                    }
                    is Result.Error -> {
                        val err = result.throwable.message ?: "Failed to check all months"
                        uiState.update { it.copy(errorMessage = UiText.dynamic(err)) }
                    }
                    is Result.Loading -> {}
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                uiState.update {
                    it.copy(errorMessage = UiText.dynamic(e.message ?: "Error checking all months"))
                }
            } finally {
                uiState.update { it.copy(isCheckingAllMonths = false) }
            }
        }
    }

    fun onMirrorConfigToCloud() {
        val useCase = syncConfigUseCase ?: return
        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isConfigSyncing = true, errorMessage = null) }
            try {
                when (val res = useCase.mirrorToCloud()) {
                    is Result.Success -> {
                        uiState.update {
                            it.copy(
                                userMessage = UiText.res(R.string.settings_msg_config_mirrored)
                            )
                        }
                    }
                    is Result.Error -> {
                        uiState.update {
                            it.copy(
                                errorMessage = UiText.res(R.string.settings_err_config_mirror_failed, res.throwable.message ?: "")
                            )
                        }
                    }
                    is Result.Loading -> {}
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                uiState.update {
                    it.copy(
                        errorMessage = UiText.res(R.string.settings_err_config_mirror_failed, e.message ?: "")
                    )
                }
            } finally {
                uiState.update { it.copy(isConfigSyncing = false) }
            }
        }
    }

    fun onRestoreConfigFromCloud() {
        val useCase = syncConfigUseCase ?: return
        scope.launch(ioDispatcher) {
            uiState.update { it.copy(isConfigSyncing = true, errorMessage = null) }
            try {
                when (val res = useCase.restoreFromCloud()) {
                    is Result.Success -> {
                        uiState.update {
                            it.copy(
                                userMessage = UiText.res(R.string.settings_msg_config_restored)
                            )
                        }
                    }
                    is Result.Error -> {
                        uiState.update {
                            it.copy(
                                errorMessage = UiText.res(R.string.settings_err_config_restore_failed, res.throwable.message ?: "")
                            )
                        }
                    }
                    is Result.Loading -> {}
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                uiState.update {
                    it.copy(
                        errorMessage = UiText.res(R.string.settings_err_config_restore_failed, e.message ?: "")
                    )
                }
            } finally {
                uiState.update { it.copy(isConfigSyncing = false) }
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
                            userMessage = UiText.res(R.string.settings_msg_workbook_formatted, currentMonthTab)
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
                            errorMessage = UiText.res(R.string.settings_err_format_workbook, statusCode, errClass)
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    companion object {
        val SPREADSHEET_ID_REJECTED_TEXT: UiText = UiText.res(R.string.settings_err_spreadsheet_id_rejected)
        const val SPREADSHEET_ID_REJECTED_MESSAGE: String =
            "The app can only open workbooks it created. Use Create a fresh workbook, or ask Google Drive to open the file with this app."
    }
}
