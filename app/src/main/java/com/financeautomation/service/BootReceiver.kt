package com.financeautomation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.financeautomation.FinanceApplication
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.service.worker.MonthlyRolloverWorker
import com.financeautomation.service.worker.NotificationListenerHealthWorker
import com.financeautomation.service.worker.SyncTransactionsWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles device boot and app update events to maintain service resilience.
 * Triggers notification listener rebind, restarts ForegroundWatcher for pending captures,
 * and re-enqueues periodic background synchronization and health workers.
 */
class BootReceiver : BroadcastReceiver() {

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        try {
            // 1. Rebind notification listener if access is granted but service is disconnected
            if (NotificationListenerServiceImpl.isAccessEnabled(context) && !NotificationListenerServiceImpl.isConnected) {
                NotificationListenerServiceImpl.rebindService(context)
            }

            // 2. Re-enqueue all periodic WorkManager tasks
            MonthlyRolloverWorker.schedule(context)
            NotificationListenerHealthWorker.schedule(context)
            SyncTransactionsWorker.enqueuePeriodic(context)

            // 3. Reset incomplete SHOWN captures to WAITING and resume ForegroundWatcher
            val app = context.applicationContext as? FinanceApplication
            receiverScope.launch {
                try {
                    app?.appContainer?.database?.pendingCaptureDao()?.resetShownToWaiting()
                } catch (_: Exception) {}
                app?.appContainer?.foregroundWatcher?.checkAndResume(receiverScope)
            }
        } catch (e: Exception) {
            Diagnostics.record("BootReceiver", "${e.javaClass.simpleName}: onReceive")
        }
    }
}
