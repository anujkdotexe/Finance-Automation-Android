package com.financeautomation.presentation.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.presentation.health.OemInfo
import com.financeautomation.presentation.viewmodel.HealthCheckId
import com.financeautomation.presentation.viewmodel.HealthCheckItem
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel

/**
 * Intent launching helpers for Setup Health settings deep-links with guarded fallback.
 */
object SetupHealthIntents {

    private val oemFeedbackMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun recordOemFeedback(target: String, openedRightScreen: Boolean) {
        oemFeedbackMap[target] = if (openedRightScreen) "Yes" else "No"
    }

    fun getOemFeedback(): Map<String, String> = HashMap(oemFeedbackMap)

    fun launchSettingsWithGuardedFallback(
        context: Context,
        item: HealthCheckItem,
        oemInfo: OemInfo,
        onFallbackFailed: (String) -> Unit
    ) {
        val intent = SetupHealthViewModel.createFixIntent(item.id, context)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            if (e !is ActivityNotFoundException && e !is SecurityException) throw e
            Diagnostics.record("SetupHealthIntents", "${e.javaClass.simpleName}: launchFixIntent")
            val fallback = SetupHealthViewModel.getAppDetailsSettingsIntent(context)
            try {
                context.startActivity(fallback)
            } catch (fallbackEx: Exception) {
                if (fallbackEx !is ActivityNotFoundException && fallbackEx !is SecurityException) throw fallbackEx
                Diagnostics.record("SetupHealthIntents", "${fallbackEx.javaClass.simpleName}: launchFallbackIntent")
                val instruction = if (item.id == HealthCheckId.OEM_BACKGROUND) {
                    context.getString(oemInfo.instructionRes)
                } else {
                    context.getString(item.descriptionRes)
                }
                onFallbackFailed(instruction)
            }
        }
    }

    fun launchAppNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            SetupHealthViewModel.getAppDetailsSettingsIntent(context)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            if (e !is ActivityNotFoundException && e !is SecurityException) throw e
            Diagnostics.record("SetupHealthIntents", "${e.javaClass.simpleName}: launchNotificationSettings")
            try {
                context.startActivity(SetupHealthViewModel.getAppDetailsSettingsIntent(context))
            } catch (fe: Exception) {
                if (fe !is ActivityNotFoundException && fe !is SecurityException) throw fe
                Diagnostics.record("SetupHealthIntents", "${fe.javaClass.simpleName}: launchNotificationFallback")
            }
        }
    }
}

