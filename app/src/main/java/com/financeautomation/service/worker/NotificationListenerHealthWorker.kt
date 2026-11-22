package com.financeautomation.service.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.service.NotificationAccessAlertManager
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
            // The single shared alert manager posts or clears the capture-off alert
            NotificationAccessAlertManager.checkAndSyncAlert(applicationContext)
            if (NotificationListenerServiceImpl.isAccessEnabled(applicationContext) &&
                !NotificationListenerServiceImpl.isConnected
            ) {
                // Listener permission exists but service binder is disconnected; request a rebind
                NotificationListenerServiceImpl.rebindService(applicationContext)
            }
            return Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record(TAG, "${e.javaClass.simpleName}: doWork failed")
            return Result.failure()
        }
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
