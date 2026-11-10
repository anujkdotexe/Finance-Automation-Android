package com.financeautomation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics

/**
 * Manages low-priority persistent alert notifying user when notification capture access is disabled (Phase 7.4).
 */
object NotificationAccessAlertManager {

    const val NOTIFICATION_ID_CAPTURE_OFF = 9001
    private const val CHANNEL_ID_HEALTH_ALERT = "channel_health_alerts"

    @Volatile
    private var isAlertVisible = false

    fun checkAndSyncAlert(context: Context) {
        try {
            val isAccessGranted = NotificationListenerServiceImpl.isAccessEnabled(context)
            val notificationManager = NotificationManagerCompat.from(context)

            if (isAccessGranted) {
                if (isAlertVisible) {
                    notificationManager.cancel(NOTIFICATION_ID_CAPTURE_OFF)
                    isAlertVisible = false
                }
                return
            }

            if (!notificationManager.areNotificationsEnabled()) {
                return
            }

            if (isAlertVisible) {
                return
            }

            ensureChannel(context)

            val fixIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, NotificationListenerServiceImpl::class.java).flattenToString()
                    )
                }
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            fixIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_CAPTURE_OFF,
                fixIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_HEALTH_ALERT)
                .setSmallIcon(R.drawable.ic_quick_log_symbol)
                .setContentTitle(context.getString(R.string.health_persistent_notification_title))
                .setContentText(context.getString(R.string.health_persistent_notification_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .build()

            notificationManager.notify(NOTIFICATION_ID_CAPTURE_OFF, notification)
            isAlertVisible = true
        } catch (e: Exception) {
            Diagnostics.record("NotificationAccessAlertManager", "${e.javaClass.simpleName}: checkAndSyncAlert")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID_HEALTH_ALERT,
                "Health & Setup Alerts",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Alerts regarding required background permissions and listener status"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}

