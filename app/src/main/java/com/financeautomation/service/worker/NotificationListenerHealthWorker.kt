package com.financeautomation.service.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.service.NotificationListenerServiceImpl
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Periodic health worker that monitors NotificationListenerService status every 6 hours.
 * Automatically attempts service rebind when disconnected and notifies user if access was revoked.
 */
class NotificationListenerHealthWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            val hasAccess = NotificationListenerServiceImpl.isAccessEnabled(applicationContext)
            if (!hasAccess) {
                // Post single low-priority alert instructing user to re-enable notification access
                postAccessRevokedAlert()
            } else if (!NotificationListenerServiceImpl.isConnected) {
                // Listener permission exists but service binder is disconnected; attempt rebind
                NotificationListenerServiceImpl.rebindService(applicationContext)
            }
            return Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record(TAG, "${e.javaClass.simpleName}: doWork failed")
            return Result.failure()
        }
    }

    private fun postAccessRevokedAlert() {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            return
        }

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_HEALTH_ALERTS,
                applicationContext.getString(R.string.health_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.health_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            HEALTH_ALERT_ID,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_HEALTH_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(applicationContext.getString(R.string.notif_expense_tracking_disabled_title))
            .setContentText(applicationContext.getString(R.string.notif_expense_tracking_disabled_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(HEALTH_ALERT_ID, builder.build())
    }

    companion object {
        const val WORK_NAME: String = "notification_listener_health_worker"
        const val CHANNEL_HEALTH_ALERTS: String = "channel_service_health"
        const val HEALTH_ALERT_ID: Int = 8888
        private const val TAG = "NotificationHealthWorker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().build()
            val request = PeriodicWorkRequestBuilder<NotificationListenerHealthWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
