package com.financeautomation.presentation.viewmodel

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.service.NotificationListenerServiceImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

enum class HealthStatus {
    OK,
    MISSING,
    UNKNOWN
}

enum class HealthCheckId {
    NOTIFICATION_ACCESS,
    DISPLAY_OVER_APPS,
    USAGE_ACCESS,
    POST_NOTIFICATIONS,
    BATTERY_OPTIMIZATION,
    OEM_BACKGROUND,
    GOOGLE_CONNECTION
}

data class HealthCheckItem(
    val id: HealthCheckId,
    val titleRes: Int,
    val descriptionRes: Int,
    val status: HealthStatus,
    val detailText: String? = null,
    val canFix: Boolean = true,
    val isOemToggleDone: Boolean = false
)

data class SetupHealthUiState(
    val checks: List<HealthCheckItem> = emptyList(),
    val hasMissing: Boolean = false,
    val isLoading: Boolean = false
)

/**
 * ViewModel managing the 7-point setup health checklist and resolution intents (Phase 7.1 & 7.2).
 */
class SetupHealthViewModel(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupHealthUiState())
    val uiState: StateFlow<SetupHealthUiState> = _uiState.asStateFlow()

    init {
        refreshChecks()
    }

    fun refreshChecks() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
                val oemDonePref = preferencesRepository.getPreference(PREF_KEY_OEM_COMPLETED) == "true"
                val email = preferencesRepository.getGoogleAccountEmailSync()
                val sheetId = preferencesRepository.getSpreadsheetIdForYearSync(currentYear)

                val items = listOf(
                    checkNotificationAccess(context),
                    checkDisplayOverApps(context),
                    checkUsageAccess(context),
                    checkPostNotifications(context),
                    checkBatteryOptimization(context),
                    checkOemBackgroundSettings(oemDonePref),
                    checkGoogleConnection(email, sheetId)
                )

                val hasMissing = items.any { it.status == HealthStatus.MISSING }

                _uiState.update {
                    it.copy(
                        checks = items,
                        hasMissing = hasMissing,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record(TAG, "${e.javaClass.simpleName}: refreshChecks")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleOemCompleted(done: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            try {
                preferencesRepository.setPreference(PREF_KEY_OEM_COMPLETED, done.toString())
                refreshChecks()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record(TAG, "${e.javaClass.simpleName}: toggleOemCompleted")
            }
        }
    }

    companion object {
        const val TAG = "SetupHealthViewModel"
        const val PREF_KEY_OEM_COMPLETED = "oem_setup_completed"

        fun checkNotificationAccess(context: Context): HealthCheckItem {
            val isEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            val isConnected = NotificationListenerServiceImpl.isConnected
            val detail = if (isConnected) {
                context.getString(R.string.health_listener_connected)
            } else {
                context.getString(R.string.health_listener_disconnected)
            }
            return HealthCheckItem(
                id = HealthCheckId.NOTIFICATION_ACCESS,
                titleRes = R.string.health_notification_access_title,
                descriptionRes = R.string.health_notification_access_desc,
                status = if (isEnabled) HealthStatus.OK else HealthStatus.MISSING,
                detailText = detail,
                canFix = !isEnabled
            )
        }

        fun checkDisplayOverApps(context: Context): HealthCheckItem {
            val canDraw = Settings.canDrawOverlays(context)
            return HealthCheckItem(
                id = HealthCheckId.DISPLAY_OVER_APPS,
                titleRes = R.string.health_overlay_title,
                descriptionRes = R.string.health_overlay_desc,
                status = if (canDraw) HealthStatus.OK else HealthStatus.MISSING,
                canFix = !canDraw
            )
        }

        fun checkUsageAccess(context: Context): HealthCheckItem {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            val hasAccess = mode == AppOpsManager.MODE_ALLOWED
            return HealthCheckItem(
                id = HealthCheckId.USAGE_ACCESS,
                titleRes = R.string.health_usage_access_title,
                descriptionRes = R.string.health_usage_access_desc,
                status = if (hasAccess) HealthStatus.OK else HealthStatus.MISSING,
                canFix = !hasAccess
            )
        }

        fun checkPostNotifications(context: Context): HealthCheckItem {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
            return HealthCheckItem(
                id = HealthCheckId.POST_NOTIFICATIONS,
                titleRes = R.string.health_post_notifications_title,
                descriptionRes = R.string.health_post_notifications_desc,
                status = if (granted) HealthStatus.OK else HealthStatus.MISSING,
                canFix = !granted
            )
        }

        fun checkBatteryOptimization(context: Context): HealthCheckItem {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            return HealthCheckItem(
                id = HealthCheckId.BATTERY_OPTIMIZATION,
                titleRes = R.string.health_battery_title,
                descriptionRes = R.string.health_battery_desc,
                status = if (isIgnoring) HealthStatus.OK else HealthStatus.MISSING,
                canFix = !isIgnoring
            )
        }

        fun checkOemBackgroundSettings(isDone: Boolean): HealthCheckItem {
            return HealthCheckItem(
                id = HealthCheckId.OEM_BACKGROUND,
                titleRes = R.string.health_oem_title,
                descriptionRes = R.string.health_oem_desc,
                status = HealthStatus.UNKNOWN,
                canFix = true,
                isOemToggleDone = isDone
            )
        }

        fun checkGoogleConnection(email: String?, sheetId: String?): HealthCheckItem {
            val isConnected = !email.isNullOrBlank() && !sheetId.isNullOrBlank()
            return HealthCheckItem(
                id = HealthCheckId.GOOGLE_CONNECTION,
                titleRes = R.string.health_google_title,
                descriptionRes = R.string.health_google_desc,
                status = if (isConnected) HealthStatus.OK else HealthStatus.MISSING,
                canFix = !isConnected
            )
        }

        fun canResolve(context: Context, intent: Intent): Boolean {
            val pm = context.packageManager ?: return false
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, 0)
            }
            return resolveInfo != null
        }

        fun getAppDetailsSettingsIntent(context: Context): Intent {
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        fun createFixIntent(id: HealthCheckId, context: Context): Intent {
            val intent = when (id) {
                HealthCheckId.NOTIFICATION_ACCESS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                            putExtra(
                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                ComponentName(context, NotificationListenerServiceImpl::class.java).flattenToString()
                            )
                        }
                    } else {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                }
                HealthCheckId.DISPLAY_OVER_APPS -> {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
                HealthCheckId.USAGE_ACCESS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    } else {
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    }
                }
                HealthCheckId.BATTERY_OPTIMIZATION -> {
                    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    if (canResolve(context, requestIntent)) {
                        requestIntent
                    } else {
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    }
                }
                HealthCheckId.POST_NOTIFICATIONS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    } else {
                        getAppDetailsSettingsIntent(context)
                    }
                }
                HealthCheckId.OEM_BACKGROUND -> {
                    return com.financeautomation.presentation.health.OemHardeningHelper.resolveCandidateIntent(context)
                }
                HealthCheckId.GOOGLE_CONNECTION -> {
                    getAppDetailsSettingsIntent(context)
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (canResolve(context, intent)) {
                intent
            } else {
                getAppDetailsSettingsIntent(context)
            }
        }
    }
}

class SetupHealthViewModelFactory(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SetupHealthViewModel::class.java)) {
            return SetupHealthViewModel(context, preferencesRepository, ioDispatcher) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }
}

