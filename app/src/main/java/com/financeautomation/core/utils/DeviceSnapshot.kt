package com.financeautomation.core.utils

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.financeautomation.data.local.dao.PipelineEventDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Captures device runtime status packed into bitmask metrics for pipeline trail and Crashlytics (Phase 9.4/9.5).
 *
 * Bitmask specification for metricA (Long):
 * Bit 0 (0x01): Notification Listener Access granted
 * Bit 1 (0x02): Overlay Permission (Settings.canDrawOverlays) granted
 * Bit 2 (0x04): Usage Stats Access (AppOpsManager.OPSTR_GET_USAGE_STATS) granted
 * Bit 3 (0x08): Post Notifications permission granted
 * Bit 4 (0x10): Battery Optimization Unrestricted (isIgnoringBatteryOptimizations)
 * Bit 5 (0x20): Background Restricted (ActivityManager.isBackgroundRestricted)
 * Bit 6 (0x40): Device Idle Mode / Doze Active (PowerManager.isDeviceIdleMode)
 *
 * metricB stores App Standby Bucket (UsageStatsManager.getAppStandbyBucket):
 * 10 = ACTIVE, 20 = WORKING_SET, 30 = FREQUENT, 40 = RARE, 45 = RESTRICTED, 50 = NEVER.
 */
object DeviceSnapshot {

    const val BIT_LISTENER_ACCESS = 0
    const val BIT_OVERLAY_PERMISSION = 1
    const val BIT_USAGE_ACCESS = 2
    const val BIT_POST_NOTIFICATIONS = 3
    const val BIT_BATTERY_UNRESTRICTED = 4
    const val BIT_BACKGROUND_RESTRICTED = 5
    const val BIT_DOZE_STATE = 6

    fun capture(context: Context): Pair<Long, Long> {
        var a = 0L

        // Bit 0: Listener Access
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        if (enabledListeners.contains(context.packageName)) {
            a = a or (1L shl BIT_LISTENER_ACCESS)
        }

        // Bit 1: Overlay Permission
        if (Settings.canDrawOverlays(context)) {
            a = a or (1L shl BIT_OVERLAY_PERMISSION)
        }

        // Bit 2: Usage Access
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        @Suppress("DEPRECATION")
        val usageMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        if (usageMode == AppOpsManager.MODE_ALLOWED) {
            a = a or (1L shl BIT_USAGE_ACCESS)
        }

        // Bit 3: Post Notifications
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        if (notificationsGranted) {
            a = a or (1L shl BIT_POST_NOTIFICATIONS)
        }

        // Bit 4: Battery Unrestricted & Bit 6: Doze Mode
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            a = a or (1L shl BIT_BATTERY_UNRESTRICTED)
        }
        if (powerManager?.isDeviceIdleMode == true) {
            a = a or (1L shl BIT_DOZE_STATE)
        }

        // Bit 5: Background Restricted (API 28+)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (activityManager?.isBackgroundRestricted == true) {
                a = a or (1L shl BIT_BACKGROUND_RESTRICTED)
            }
        }

        // metricB: Standby Bucket (API 28+)
        val b: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            usageStatsManager?.appStandbyBucket?.toLong() ?: 0L
        } else {
            0L
        }

        return Pair(a, b)
    }

    fun isBitSet(mask: Long, bit: Int): Boolean {
        return (mask and (1L shl bit)) != 0L
    }

    fun decodeFlag(mask: Long, bit: Int): String {
        return if (isBitSet(mask, bit)) "Yes" else "No"
    }

    fun decodeBucket(bucket: Long): String {
        return when (bucket.toInt()) {
            10 -> "ACTIVE (10)"
            20 -> "WORKING_SET (20)"
            30 -> "FREQUENT (30)"
            40 -> "RARE (40)"
            45 -> "RESTRICTED (45)"
            50 -> "NEVER (50)"
            0 -> "UNKNOWN (0)"
            else -> "OTHER ($bucket)"
        }
    }

    /**
     * Updates Crashlytics custom keys setup_health_summary and trail_funnel_summary with counts only.
     */
    suspend fun updateCrashlyticsSummaries(
        context: Context,
        bitmask: Long,
        pipelineEventDao: PipelineEventDao? = null
    ) = withContext(Dispatchers.IO) {
        if (!CrashlyticsManager.isOptedIn(context)) return@withContext

        // Calculate setup health counts: checks bits 0..4 (5 system permissions)
        var okCount = 0
        var missingCount = 0
        for (bit in 0..4) {
            if (isBitSet(bitmask, bit)) okCount++ else missingCount++
        }
        val manualCount = 1 // OEM background configuration is manual/unknown
        val setupSummary = "ok=$okCount,missing=$missingCount,manual=$manualCount"
        CrashlyticsManager.setCustomKey(context, "setup_health_summary", setupSummary)

        // Calculate funnel summary counts
        var captures = 0
        var saved = 0
        if (pipelineEventDao != null) {
            try {
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = calendar.timeInMillis
                val now = System.currentTimeMillis()
                val counts = pipelineEventDao.getFunnelCounts(startOfDay, now)
                for (item in counts) {
                    if (item.code == PipelineCode.CREATED_WAITING.name || item.code == PipelineCode.AWAITING_SUCCESS.name) {
                        captures += item.count
                    }
                    if (item.code == PipelineCode.USER_SAVED.name) {
                        saved += item.count
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("DeviceSnapshot", "funnelCounts: ${e.javaClass.simpleName}")
            }
        }
        val funnelSummary = "captures=$captures,saved=$saved"
        CrashlyticsManager.setCustomKey(context, "trail_funnel_summary", funnelSummary)
    }
}

