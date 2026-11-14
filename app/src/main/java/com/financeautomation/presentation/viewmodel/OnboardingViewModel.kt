package com.financeautomation.presentation.viewmodel

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.R
import com.financeautomation.core.model.Result
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
    val pendingAccountSwitch: com.financeautomation.data.account.AccountSwitchPrompt? = null
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
    private val googleAuthTokenProvider: GoogleAuthTokenProvider,
    private val workbookResolver: WorkbookResolver,
    private val preferencesRepository: PreferencesRepository,
    private val profileAvatarManager: ProfileAvatarManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val accountSwitchGuard: com.financeautomation.data.account.AccountSwitchGuard? = null
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
            kotlinx.coroutines.flow.combine(
                preferencesRepository.getGoogleAccountEmail(),
                preferencesRepository.getSpreadsheetIdForYear(currentYear)
            ) { email, sheetId -> email to sheetId }
                .collect { (email, sheetId) ->
                    _uiState.update { it.withPreferences(email, sheetId) }
                }
        }
    }

    fun startSignIn(skipGuard: Boolean = false) {
        viewModelScope.launch(ioDispatcher) {
            val guard = accountSwitchGuard
            val picked = candidate?.first
            if (guard != null && !skipGuard && !picked.isNullOrBlank()) {
                val evaluation = guard.evaluate(picked)
                if (evaluation.decision == com.financeautomation.data.account.SwitchDecision.CONFIRM_SWITCH) {
                    val (email, name, photo) = candidate!!
                    _uiState.update {
                        it.copy(pendingAccountSwitch = com.financeautomation.data.account.AccountSwitchPrompt(email.orEmpty(), name, photo, evaluation.pendingCount))
                    }
                    return@launch
                }
                guard.recordOwner(picked)
                saveAccountDetails(candidate!!.first, candidate!!.second, candidate!!.third)
            }
            _uiState.update { it.copy(step1State = StepState.IN_PROGRESS, step1Error = null) }
            when (val res = googleAuthTokenProvider.requestAuthorization(isForeground = true)) {
                is AuthResult.Token -> {
                    onTokenAcquired()
                }
                is AuthResult.NeedsConsent -> {
                    _consentRequests.emit(res.pendingIntent)
                }
                is AuthResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            step1State = StepState.ERROR,
                            step1Error = res.exception.message?.let { msg -> UiText.dynamic(msg) } ?: UiText.res(R.string.onboarding_err_auth_failed)
                        )
                    }
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
            when (val res = googleAuthTokenProvider.requestAuthorization(isForeground = true)) {
                is AuthResult.Token -> {
                    onTokenAcquired()
                }
                is AuthResult.NeedsConsent -> {
                    _consentRequests.emit(res.pendingIntent)
                }
                is AuthResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            step1State = StepState.ERROR,
                            step1Error = res.exception.message?.let { msg -> UiText.dynamic(msg) } ?: UiText.res(R.string.onboarding_err_auth_failed)
                        )
                    }
                }
            }
        }
    }

    fun onGoogleAccountDetails(email: String?, displayName: String?, photoUrl: String?) {
        // Held, not saved: the email is only saved once the account switch guard has allowed this account
        candidate = Triple(email, displayName, photoUrl)
        if (accountSwitchGuard == null) saveCandidateNow()
    }

    private fun saveCandidateNow() {
        val (email, displayName, photoUrl) = candidate ?: return
        viewModelScope.launch(ioDispatcher) { saveAccountDetails(email, displayName, photoUrl) }
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
            accountSwitchGuard?.switchTo(prompt.email)
            profileAvatarManager?.deleteAvatar()
            _uiState.update { it.copy(pendingAccountSwitch = null) }
            saveAccountDetails(prompt.email, prompt.displayName, prompt.photoUrl)
            startSignIn(skipGuard = true)
        }
    }

    /** The user cancelled: nothing is saved and the app stays signed out. */
    fun cancelAccountSwitch() {
        candidate = null
        _uiState.update { it.copy(pendingAccountSwitch = null, step1State = StepState.NOT_STARTED, step1Error = null) }
    }

    private suspend fun onTokenAcquired() {
        val existingEmail = preferencesRepository.getGoogleAccountEmailSync()
        val email = if (!existingEmail.isNullOrBlank()) existingEmail else "Authorized User"
        preferencesRepository.setGoogleAccountEmail(email)
        _uiState.update {
            it.copy(
                step1State = StepState.COMPLETED,
                googleAccountEmail = email,
                step1Error = null
            )
        }
        startWorkspaceProvisioning()
    }

    fun startWorkspaceProvisioning(forceValidateTabs: Boolean = false) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(step2State = StepState.IN_PROGRESS, step2Error = null) }
            when (val res = workbookResolver.resolveForYear(currentYear, forceValidateTabs = forceValidateTabs)) {
                is Result.Success -> {
                    val id = res.data
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
                        val messagePart = if (apiError.message.isNotBlank()) ": ${apiError.message.take(300)}" else ""
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
            val detail = e.message
            return if (detail.isNullOrBlank()) {
                UiText(R.string.onboarding_error_unknown)
            } else {
                UiText(R.string.onboarding_error_unknown_with_message, listOf(detail))
            }
        }

        fun provideFactory(
            googleAuthTokenProvider: GoogleAuthTokenProvider,
            workbookResolver: WorkbookResolver,
            preferencesRepository: PreferencesRepository,
            profileAvatarManager: ProfileAvatarManager? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            accountSwitchGuard: com.financeautomation.data.account.AccountSwitchGuard? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OnboardingViewModel(
                    googleAuthTokenProvider = googleAuthTokenProvider,
                    workbookResolver = workbookResolver,
                    preferencesRepository = preferencesRepository,
                    profileAvatarManager = profileAvatarManager,
                    ioDispatcher = ioDispatcher,
                    accountSwitchGuard = accountSwitchGuard
                ) as T
            }
        }
    }
}

