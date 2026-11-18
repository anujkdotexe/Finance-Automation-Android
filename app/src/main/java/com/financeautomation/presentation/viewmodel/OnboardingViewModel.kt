package com.financeautomation.presentation.viewmodel

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.R
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PrivacyScrubber
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.data.remote.AuthResult
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.GoogleDriveApiException
import com.financeautomation.data.remote.GoogleSheetsApiException
import com.financeautomation.data.remote.ProfileAvatarManager
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

enum class StepState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    ERROR
}

data class OnboardingUiState(
    val step1State: StepState = StepState.NOT_STARTED,
    val step1Error: UiText? = null,
    val step2State: StepState = StepState.NOT_STARTED,
    val step2Error: UiText? = null,
    val step3State: StepState = StepState.NOT_STARTED,
    val step4State: StepState = StepState.NOT_STARTED,
    val googleAccountEmail: String? = null,
    val workbookName: String = "",
    val spreadsheetId: String = "",
    /** Set while the user is asked to confirm switching to another Google account. */
    val pendingAccountSwitch: com.financeautomation.data.account.AccountSwitchPrompt? = null,
    val showAccountSetupStep: Boolean = false,
    val accounts: List<com.financeautomation.domain.model.Account> = emptyList(),
    val defaultAccountName: String = com.financeautomation.core.constants.CategoryConstants.ACCOUNT_OTHER,
    val isAccountsSeen: Boolean = false,
    val accountSetupError: UiText? = null
) {
    /** Signed in and the workbook exists: the Continue button is enabled. */
    val isReady: Boolean get() = step1State == StepState.COMPLETED && step2State == StepState.COMPLETED

    /** The Sign in step is the one that needs the user. */
    val needsSignIn: Boolean get() = step1State != StepState.COMPLETED

    /** Signed in but the workbook is missing or failed. */
    val needsWorkbook: Boolean get() = step1State == StepState.COMPLETED && step2State != StepState.COMPLETED
}

/**
 * Brings the first two steps in line with what is saved: the Google account and this year's workbook. Called every
 * time either changes, so signing out turns the page back to Sign in and signing in shows Continue, with no restart.
 * A step that is running or has failed keeps that state until it finishes.
 */
fun OnboardingUiState.withPreferences(email: String?, sheetId: String?): OnboardingUiState {
    val hasEmail = !email.isNullOrBlank()
    val hasSheet = hasEmail && !sheetId.isNullOrBlank()
    val busyOrFailed = { state: StepState -> state == StepState.IN_PROGRESS || state == StepState.ERROR }
    val s1 = if (hasEmail) StepState.COMPLETED else if (busyOrFailed(step1State)) step1State else StepState.NOT_STARTED
    val s2 = if (hasSheet) StepState.COMPLETED else if (hasEmail && busyOrFailed(step2State)) step2State else StepState.NOT_STARTED
    return copy(
        step1State = s1,
        step1Error = if (s1 == StepState.COMPLETED) null else step1Error,
        step2State = s2,
        step2Error = if (s2 == StepState.COMPLETED) null else step2Error,
        step4State = if (s1 == StepState.COMPLETED && s2 == StepState.COMPLETED && step3State == StepState.COMPLETED) StepState.COMPLETED else StepState.NOT_STARTED,
        googleAccountEmail = if (hasEmail) email else null,
        spreadsheetId = if (hasSheet) sheetId.orEmpty() else ""
    )
}

class OnboardingViewModel(
    private val googleAuthTokenProvider: com.financeautomation.data.remote.AuthorizationSource,
    private val workbookResolver: WorkbookResolver,
    private val preferencesRepository: PreferencesRepository,
    private val profileAvatarManager: ProfileAvatarManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val accountSwitchGuard: com.financeautomation.data.account.AccountSwitchGuard? = null,
    private val accountRepository: com.financeautomation.domain.repository.AccountRepository? = null
) : ViewModel() {

    /** The account details chosen in the Google picker, kept until the guard has been asked. */
    @Volatile
    private var candidate: Triple<String?, String?, String?>? = null

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _consentRequests = MutableSharedFlow<PendingIntent>()
    val consentRequests: SharedFlow<PendingIntent> = _consentRequests.asSharedFlow()

    private val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year

    init {
        val initialWorkbook = "Finance$currentYear"
        _uiState.update { it.copy(workbookName = initialWorkbook) }
        checkInitialState()
    }

    /** Follows the saved Google account and workbook for as long as the screen model lives. */
    private fun checkInitialState() {
        viewModelScope.launch(ioDispatcher) {
            val seen = preferencesRepository.isOnboardingAccountsSeen()
            val existingEmail = preferencesRepository.getGoogleAccountEmailSync()
            val existingSheetId = preferencesRepository.getSpreadsheetIdSync()
            val isExistingInstall = !existingEmail.isNullOrBlank() && !existingSheetId.isNullOrBlank()
            if (isExistingInstall && !seen) {
                // Existing install already past onboarding: do not ask accounts again
                preferencesRepository.setOnboardingAccountsSeen(true)
                _uiState.update { it.copy(isAccountsSeen = true) }
            } else {
                _uiState.update { it.copy(isAccountsSeen = seen) }
            }
        }

        viewModelScope.launch(ioDispatcher) {
            kotlinx.coroutines.flow.combine(
                preferencesRepository.getGoogleAccountEmail(),
                preferencesRepository.getSpreadsheetIdForYear(currentYear)
            ) { email, sheetId -> email to sheetId }
                .collect { (email, sheetId) ->
                    _uiState.update { it.withPreferences(email, sheetId) }
                }
        }

        accountRepository?.let { repo ->
            viewModelScope.launch(ioDispatcher) {
                repo.getAccounts().collect { accountsList ->
                    val defaultAcc = accountsList.firstOrNull { it.isDefault }?.name
                        ?: preferencesRepository.getDefaultAccountSync()
                    val hasCustomAccounts = accountsList.any { !com.financeautomation.core.constants.CategoryConstants.isProtectedAccount(it.name) }
                    if (hasCustomAccounts && !_uiState.value.isAccountsSeen) {
                        preferencesRepository.setOnboardingAccountsSeen(true)
                        _uiState.update { it.copy(accounts = accountsList, defaultAccountName = defaultAcc, isAccountsSeen = true) }
                    } else {
                        _uiState.update { it.copy(accounts = accountsList, defaultAccountName = defaultAcc) }
                    }
                }
            }
        }

        viewModelScope.launch(ioDispatcher) {
            preferencesRepository.getDefaultAccount().collect { defAcc ->
                _uiState.update { it.copy(defaultAccountName = defAcc) }
            }
        }
    }

    fun openAccountSetup() {
        _uiState.update { it.copy(showAccountSetupStep = true, accountSetupError = null) }
    }

    fun dismissAccountSetup() {
        _uiState.update { it.copy(showAccountSetupStep = false, accountSetupError = null) }
    }

    fun skipAccountSetup() {
        viewModelScope.launch(ioDispatcher) {
            com.financeautomation.core.utils.AnalyticsManager.logEvent(
                eventName = com.financeautomation.core.utils.UsageEvents.EVENT_ONBOARDING_STEP,
                params = mapOf("step_id" to "accounts", "status" to "skipped")
            )
            preferencesRepository.setOnboardingAccountsSeen(true)
            _uiState.update { it.copy(showAccountSetupStep = false, isAccountsSeen = true, accountSetupError = null) }
        }
    }

    fun completeAccountSetup() {
        viewModelScope.launch(ioDispatcher) {
            com.financeautomation.core.utils.AnalyticsManager.logEvent(
                eventName = com.financeautomation.core.utils.UsageEvents.EVENT_ONBOARDING_STEP,
                params = mapOf("step_id" to "accounts", "status" to "done")
            )
            preferencesRepository.setOnboardingAccountsSeen(true)
            _uiState.update { it.copy(showAccountSetupStep = false, isAccountsSeen = true, accountSetupError = null) }
        }
    }

    fun addAccount(name: String, lastFourDigits: String? = null) {
        val existing = _uiState.value.accounts.map { it.name }
        val validation = com.financeautomation.core.utils.InputPolicy.validateName(name, existing)
        if (!validation.isValid) {
            val err = validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Error
            val errorText = when (err.reason) {
                com.financeautomation.core.utils.InputPolicy.InputError.BLANK -> UiText.res(R.string.input_err_blank)
                com.financeautomation.core.utils.InputPolicy.InputError.TOO_LONG -> UiText.res(R.string.input_err_too_long, listOf(err.limit))
                com.financeautomation.core.utils.InputPolicy.InputError.DUPLICATE_NAME -> UiText.res(R.string.input_err_duplicate)
                else -> UiText.res(R.string.input_err_invalid_chars)
            }
            _uiState.update { it.copy(accountSetupError = errorText) }
            return
        }
        val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue
        val cleanDigits = lastFourDigits?.let { com.financeautomation.core.utils.InputPolicy.cleanDigits4(it) }?.ifEmpty { null }

        viewModelScope.launch(ioDispatcher) {
            val repo = accountRepository
            if (repo != null) {
                val existingAccounts = repo.getAllAccounts()
                val isFirstUserAccount = existingAccounts.none { !com.financeautomation.core.constants.CategoryConstants.isProtectedAccount(it.name) }
                val newId = "acc_${java.util.UUID.randomUUID().toString().take(8)}"
                val newAcc = com.financeautomation.domain.model.Account(
                    id = newId,
                    name = cleanName,
                    lastFourDigits = cleanDigits,
                    isDefault = isFirstUserAccount,
                    isArchived = false,
                    displayOrder = existingAccounts.size
                )
                repo.insertAccount(newAcc)
                if (isFirstUserAccount) {
                    repo.setDefaultAccount(newId)
                    preferencesRepository.setDefaultAccount(cleanName)
                }
            }
            _uiState.update { it.copy(accountSetupError = null) }
        }
    }

    fun setDefaultAccount(accountId: String, accountName: String) {
        viewModelScope.launch(ioDispatcher) {
            accountRepository?.setDefaultAccount(accountId)
            preferencesRepository.setDefaultAccount(accountName)
            _uiState.update { it.copy(defaultAccountName = accountName) }
        }
    }

    fun deleteAccount(accountId: String) {
        val target = _uiState.value.accounts.firstOrNull { it.id == accountId } ?: return
        if (com.financeautomation.core.constants.CategoryConstants.isProtectedAccount(target.name)) {
            _uiState.update { it.copy(accountSetupError = UiText.res(R.string.onboarding_accounts_cannot_remove_builtin)) }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            accountRepository?.deleteAccount(accountId)
            _uiState.update { it.copy(accountSetupError = null) }
        }
    }

    fun startSignIn(skipGuard: Boolean = false) {
        viewModelScope.launch(ioDispatcher) {
            val pickedCandidate = candidate
            val picked = pickedCandidate?.first?.takeIf { it.isNotBlank() }
            val stored = preferencesRepository.getGoogleAccountEmailSync()?.takeIf { it.isNotBlank() }
            // Nothing picked and nothing stored (for example the picker was cancelled): do nothing and stay on this step
            if (picked == null && stored == null) return@launch

            val guard = accountSwitchGuard
            if (guard != null && !skipGuard && picked != null) {
                val evaluation = guard.evaluate(picked)
                if (evaluation.decision == com.financeautomation.data.account.SwitchDecision.CONFIRM_SWITCH) {
                    val (email, name, photo) = pickedCandidate ?: return@launch
                    _uiState.update {
                        it.copy(pendingAccountSwitch = com.financeautomation.data.account.AccountSwitchPrompt(email.orEmpty(), name, photo, evaluation.pendingCount, evaluation.hasLocalData))
                    }
                    return@launch
                }
            }
            authorize(picked ?: stored)
        }
    }

    /** Authorizes [email] explicitly. The account is stored only after this worked, never before. */
    private suspend fun authorize(email: String?) {
        _uiState.update { it.copy(step1State = StepState.IN_PROGRESS, step1Error = null) }
        when (val res = googleAuthTokenProvider.requestAuthorization(isForeground = true, accountEmail = email)) {
            is AuthResult.Token -> onTokenAcquired()
            is AuthResult.NeedsConsent -> _consentRequests.emit(res.pendingIntent)
            is AuthResult.Failed -> {
                val signedOut = res.exception.message == GoogleAuthTokenProvider.NO_ACCOUNT_MESSAGE
                _uiState.update {
                    it.copy(
                        step1State = if (signedOut) StepState.NOT_STARTED else StepState.ERROR,
                        step1Error = when {
                            signedOut -> null
                            else -> {
                                Diagnostics.record("OnboardingViewModel", "auth_failed: ${SyncErrorKind.safeCode("onboarding auth", res.exception)} ${PrivacyScrubber.scrub(res.exception.message.orEmpty())}")
                                UiText.dynamic(SyncErrorKind.formatUserMessage("onboarding auth", res.exception))
                            }
                        }
                    )
                }
            }
        }
    }

    fun onConsentResult(success: Boolean) {
        if (!success) {
            _uiState.update {
                it.copy(
                    step1State = StepState.ERROR,
                    step1Error = UiText.res(R.string.onboarding_err_consent_denied)
                )
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            authorize(candidate?.first?.takeIf { it.isNotBlank() } ?: preferencesRepository.getGoogleAccountEmailSync())
        }
    }

    fun onGoogleAccountDetails(email: String?, displayName: String?, photoUrl: String?) {
        // Held, not saved: the account is stored only after the guard allowed it and Google authorized it
        candidate = Triple(email, displayName, photoUrl)
    }

    private suspend fun saveAccountDetails(email: String?, displayName: String?, photoUrl: String?) {
        if (!email.isNullOrBlank()) {
            preferencesRepository.setGoogleAccountEmail(email)
        }
        if (!displayName.isNullOrBlank()) {
            preferencesRepository.setGoogleAccountDisplayName(displayName)
        }
        if (!photoUrl.isNullOrBlank()) {
            preferencesRepository.setGoogleAccountPhotoUrl(photoUrl)
            profileAvatarManager?.downloadAndSaveAvatar(photoUrl)
        }
        _uiState.update {
            it.copy(
                googleAccountEmail = email ?: it.googleAccountEmail
            )
        }
    }

    /** The user confirmed the switch: wipe the previous account's data, then go on with the new one. */
    fun confirmAccountSwitch() {
        val prompt = _uiState.value.pendingAccountSwitch ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                accountSwitchGuard?.switchTo(prompt.email)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Diagnostics.record("OnboardingViewModel", "accountSwitch: ${e.javaClass.simpleName}")
                _uiState.update {
                    it.copy(pendingAccountSwitch = null, step1State = StepState.ERROR, step1Error = UiText.res(R.string.account_switch_failed))
                }
                return@launch
            }
            profileAvatarManager?.deleteAvatar()
            _uiState.update { it.copy(pendingAccountSwitch = null) }
            authorize(prompt.email)
        }
    }

    /** The user cancelled: nothing is saved and the app stays signed out. */
    fun cancelAccountSwitch() {
        candidate = null
        _uiState.update { it.copy(pendingAccountSwitch = null, step1State = StepState.NOT_STARTED, step1Error = null) }
    }

    private suspend fun onTokenAcquired() {
        val picked = candidate
        if (picked != null && !picked.first.isNullOrBlank()) {
            accountSwitchGuard?.recordOwner(picked.first.orEmpty())
            saveAccountDetails(picked.first, picked.second, picked.third)
        }
        val email = preferencesRepository.getGoogleAccountEmailSync()?.takeIf { it.isNotBlank() } ?: picked?.first.orEmpty()
        com.financeautomation.core.utils.AnalyticsManager.logEvent(
            eventName = com.financeautomation.core.utils.UsageEvents.EVENT_ONBOARDING_STEP,
            params = mapOf("step_id" to "sign_in", "status" to "done")
        )
        _uiState.update {
            it.copy(
                step1State = StepState.COMPLETED,
                googleAccountEmail = email,
                step1Error = null
            )
        }
        startWorkspaceProvisioning(forceValidateTabs = true)
    }

    fun startWorkspaceProvisioning(forceValidateTabs: Boolean = false) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(step2State = StepState.IN_PROGRESS, step2Error = null) }
            when (val res = workbookResolver.resolveForYear(currentYear, forceValidateTabs = forceValidateTabs, createBackup = false)) {
                is Result.Success -> {
                    val id = res.data
                    com.financeautomation.core.utils.AnalyticsManager.logEvent(
                        eventName = com.financeautomation.core.utils.UsageEvents.EVENT_ONBOARDING_STEP,
                        params = mapOf("step_id" to "workbook", "status" to "done")
                    )
                    _uiState.update {
                        it.copy(
                            step2State = StepState.COMPLETED,
                            spreadsheetId = id,
                            step2Error = null
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            step2State = StepState.ERROR,
                            step2Error = formatFriendlyErrorMessage(res.exception)
                        )
                    }
                }
                is Result.Loading -> {}
            }
        }
    }

    fun updatePermissionsState(hasNotificationListener: Boolean) {
        if (hasNotificationListener) {
            com.financeautomation.core.utils.AnalyticsManager.logEvent(
                eventName = com.financeautomation.core.utils.UsageEvents.EVENT_ONBOARDING_STEP,
                params = mapOf("step_id" to "permissions", "status" to "done")
            )
            _uiState.update {
                it.copy(
                    step3State = StepState.COMPLETED,
                    step4State = if (it.step1State == StepState.COMPLETED && it.step2State == StepState.COMPLETED) {
                        StepState.COMPLETED
                    } else {
                        StepState.NOT_STARTED
                    }
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    step3State = StepState.NOT_STARTED,
                    step4State = StepState.NOT_STARTED
                )
            }
        }
    }

    companion object {
        fun formatFriendlyErrorMessage(e: Throwable): UiText {
            val apiError = when (e) {
                is GoogleSheetsApiException -> e.apiError
                is GoogleDriveApiException -> e.apiError
                else -> null
            }
            if (apiError != null) {
                val r = apiError.reason
                return when {
                    r == "SERVICE_DISABLED" || r == "accessNotConfigured" ->
                        UiText(R.string.onboarding_error_api_disabled)
                    apiError.httpStatus == 403 || r == "ACCESS_TOKEN_SCOPE_INSUFFICIENT" || r == "insufficientPermissions" ->
                        UiText(R.string.onboarding_error_insufficient_scope)
                    apiError.httpStatus == 429 || r == "RATE_LIMIT_EXCEEDED" || r == "rateLimitExceeded" || r == "userRateLimitExceeded" ->
                        UiText(R.string.onboarding_error_rate_limited)
                    else -> {
                        val messagePart = if (apiError.message.isNotBlank()) ": ${PrivacyScrubber.scrub(apiError.message.take(300))}" else ""
                        UiText(
                            R.string.onboarding_error_generic,
                            listOf(
                                apiError.endpointId,
                                apiError.httpStatus,
                                apiError.reason ?: apiError.status ?: "UNKNOWN",
                                messagePart
                            )
                        )
                    }
                }
            }
            val detail = e.message?.let { PrivacyScrubber.scrub(it) }
            return if (detail.isNullOrBlank()) {
                UiText(R.string.onboarding_error_unknown)
            } else {
                UiText(R.string.onboarding_error_unknown_with_message, listOf(detail))
            }
        }

        fun provideFactory(
            googleAuthTokenProvider: com.financeautomation.data.remote.AuthorizationSource,
            workbookResolver: WorkbookResolver,
            preferencesRepository: PreferencesRepository,
            profileAvatarManager: ProfileAvatarManager? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            accountSwitchGuard: com.financeautomation.data.account.AccountSwitchGuard? = null,
            accountRepository: com.financeautomation.domain.repository.AccountRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OnboardingViewModel(
                    googleAuthTokenProvider = googleAuthTokenProvider,
                    workbookResolver = workbookResolver,
                    preferencesRepository = preferencesRepository,
                    profileAvatarManager = profileAvatarManager,
                    ioDispatcher = ioDispatcher,
                    accountSwitchGuard = accountSwitchGuard,
                    accountRepository = accountRepository
                ) as T
            }
        }
    }
}

