package com.financeautomation.presentation.viewmodel

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.core.model.Result
import com.financeautomation.data.remote.AuthResult
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.GoogleDriveApiException
import com.financeautomation.data.remote.GoogleSheetsApiException
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
    val step1Error: String? = null,
    val step2State: StepState = StepState.NOT_STARTED,
    val step2Error: String? = null,
    val step3State: StepState = StepState.NOT_STARTED,
    val step4State: StepState = StepState.NOT_STARTED,
    val googleAccountEmail: String? = null,
    val workbookName: String = "",
    val spreadsheetId: String = ""
)

class OnboardingViewModel(
    private val googleAuthTokenProvider: GoogleAuthTokenProvider,
    private val workbookResolver: WorkbookResolver,
    private val preferencesRepository: PreferencesRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

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

    private fun checkInitialState() {
        viewModelScope.launch(ioDispatcher) {
            val email = preferencesRepository.getGoogleAccountEmailSync()
            val sheetId = preferencesRepository.getSpreadsheetIdForYearSync(currentYear)
            if (!email.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        step1State = StepState.COMPLETED,
                        googleAccountEmail = email
                    )
                }
                if (!sheetId.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            step2State = StepState.COMPLETED,
                            spreadsheetId = sheetId
                        )
                    }
                }
            }
        }
    }

    fun startSignIn() {
        viewModelScope.launch(ioDispatcher) {
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
                            step1Error = res.exception.message ?: "Authorization failed"
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
                    step1Error = "Consent was not granted. Please grant Google Drive access to continue."
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
                            step1Error = res.exception.message ?: "Authorization failed"
                        )
                    }
                }
            }
        }
    }

    private suspend fun onTokenAcquired() {
        val email = "Authorized User"
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

    fun startWorkspaceProvisioning() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(step2State = StepState.IN_PROGRESS, step2Error = null) }
            when (val res = workbookResolver.resolveForYear(currentYear)) {
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

    internal fun formatFriendlyErrorMessage(e: Throwable): String {
        val apiError = when (e) {
            is GoogleSheetsApiException -> e.apiError
            is GoogleDriveApiException -> e.apiError
            else -> null
        }
        if (apiError != null) {
            val r = apiError.reason
            return when {
                r == "SERVICE_DISABLED" || r == "accessNotConfigured" ->
                    "Google Sheets or Drive API is not enabled for your Google Cloud project. Please enable them in Google Cloud Console."
                apiError.httpStatus == 403 || r == "ACCESS_TOKEN_SCOPE_INSUFFICIENT" || r == "insufficientPermissions" ->
                    "Required Google Drive or Sheets permissions were not granted. Please sign out and sign in granting all requested scopes."
                apiError.httpStatus == 429 || r == "RATE_LIMIT_EXCEEDED" || r == "rateLimitExceeded" || r == "userRateLimitExceeded" ->
                    "Google API rate limit reached. Please wait a moment and tap Retry."
                else ->
                    "Workspace setup failed (${apiError.endpointId} HTTP ${apiError.httpStatus} ${apiError.reason ?: apiError.status ?: "UNKNOWN"}). Tap retry."
            }
        }
        return e.message ?: "Failed to resolve finance workspace"
    }

    companion object {
        fun provideFactory(
            googleAuthTokenProvider: GoogleAuthTokenProvider,
            workbookResolver: WorkbookResolver,
            preferencesRepository: PreferencesRepository,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OnboardingViewModel(
                    googleAuthTokenProvider = googleAuthTokenProvider,
                    workbookResolver = workbookResolver,
                    preferencesRepository = preferencesRepository,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
    }
}

