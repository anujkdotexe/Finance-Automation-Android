package com.financeautomation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import com.financeautomation.FinanceApplication
import com.financeautomation.R
import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.presentation.ui.EntryActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android NotificationListenerService intercepting financial notifications from whitelisted package IDs.
 * Implements self-healing listener rebinds, catch-up replay, and handoff to ForegroundWatcher.
 */
class NotificationListenerServiceImpl : NotificationListenerService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    internal var testProcessor: NotificationProcessor? = null

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        serviceJob.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        PipelineTrail.recordHeartbeat()
        if (sbn == null || sbn.notification == null) return

        val sourcePackage = sbn.packageName ?: return
        if (sourcePackage == packageName) return

        if (!PackageWhitelist.isWhitelisted(sourcePackage)) {
            PipelineTrail.recordDroppedNotWhitelisted()
            return
        }

        // Strict privacy check for chat apps that carry payments (WhatsApp, WhatsApp Business):
        // Only inspect text if the notification is definitively on a payments channel or category.
        if (PackageWhitelist.isChatApp(sourcePackage)) {
            val channelId = sbn.notification?.channelId
            val category = sbn.notification?.category
            if (!PackageWhitelist.isPaymentNotification(channelId, category)) {
                PipelineTrail.recordDroppedNotWhitelisted()
                return
            }
        }
        PipelineTrail.record(PipelineStage.ALERT, PipelineCode.WHITELISTED_SEEN)

        val extras = sbn.notification.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        // Reject non-bank SMS sender before reading any message text into memory (AGENTS Rule 15)
        if (!NotificationPreFilter.isAllowedSender(sourcePackage, title)) {
            PipelineTrail.recordDroppedNotWhitelisted()
            return
        }

        val extraText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // Sender and keyword filter on the title and a short prefix first; the long text is only read after it passes
        if (!NotificationPreFilter.shouldReadFullText(sourcePackage, title, extraText.take(NotificationPreFilter.PREFIX_LENGTH))) {
            PipelineTrail.recordDroppedNotWhitelisted()
            return
        }
        val extraBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = if (extraBigText.length > extraText.length) extraBigText else extraText
        val postTime = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        val key = sbn.key ?: ""
        val channelId = sbn.notification?.channelId
        val category = sbn.notification?.category

        handleNotification(sourcePackage, title, text, postTime, key, channelId, category)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        PipelineTrail.record(PipelineStage.LISTENER, PipelineCode.CONNECTED)
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancel(com.financeautomation.service.worker.NotificationListenerHealthWorker.HEALTH_ALERT_ID)
            nm?.cancel(NotificationAccessAlertManager.NOTIFICATION_ID_CAPTURE_OFF)
        } catch (_: Exception) {}
        val app = applicationContext as? FinanceApplication
        val watcher = app?.appContainer?.foregroundWatcher

        app?.appContainer?.preferencesRepository?.let { prefs ->
            serviceScope.launch {
                PackageWhitelist.loadFromPreferences(prefs)
            }
        }

        // Resume watcher for any persisted WAITING captures
        watcher?.checkAndResume(serviceScope)

        try {
            val notifications = activeNotifications ?: return
            val now = System.currentTimeMillis()
            for (sbn in notifications) {
                if (sbn != null && sbn.notification != null) {
                    val postTime = sbn.postTime
                    // Catch up only recent notifications within the last 5 minutes
                    if (now - postTime < 300_000L) {
                        onNotificationPosted(sbn)
                    }
                }
            }
        } catch (e: Exception) {
            Diagnostics.record(TAG, "${e.javaClass.simpleName}: onListenerConnected catchup")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        PipelineTrail.record(PipelineStage.LISTENER, PipelineCode.DISCONNECTED)
        // Self-healing rebind on listener disconnection
        try {
            PipelineTrail.record(PipelineStage.LISTENER, PipelineCode.REBIND_REQUESTED)
            rebindService(this)
            PipelineTrail.record(PipelineStage.LISTENER, PipelineCode.REBIND_OK)
        } catch (e: Exception) {
            Diagnostics.record(TAG, "${e.javaClass.simpleName}: onListenerDisconnected rebind")
        }
    }

    fun handleNotification(
        sourcePackage: String,
        title: String,
        text: String,
        postTime: Long,
        sbnKey: String = "",
        channelId: String? = null,
        category: String? = null
    ) {
        val app = applicationContext as? FinanceApplication
        val container = app?.appContainer
        val watcher = container?.foregroundWatcher

        serviceScope.launch {
            try {
                val processor = testProcessor ?: container?.getNotificationProcessor() ?: return@launch

                val outcome = processor.process(sourcePackage, title, text, postTime, sbnKey, channelId, category)
                when (outcome) {
                    is NotificationProcessingOutcome.Captured -> {
                        if (outcome.capture.state == PendingCaptureEntity.STATE_WAITING) {
                            watcher?.notifyCaptureWaiting(serviceScope)
                        }
                    }
                    is NotificationProcessingOutcome.Updated -> {
                        if (outcome.capture.state == PendingCaptureEntity.STATE_WAITING) {
                            watcher?.notifyCaptureWaiting(serviceScope)
                        }
                    }
                    is NotificationProcessingOutcome.Merged -> {
                        // Merged dual alert without posting duplicate notification
                    }
                    is NotificationProcessingOutcome.Dismissed -> {
                        // Dismissed payment alert
                    }
                    is NotificationProcessingOutcome.Ignored -> {
                        Diagnostics.recordDrop(outcome.reason)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record(TAG, "${e.javaClass.simpleName}: handleNotification execution")
            }
        }
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_FINANCIAL_ALERTS,
                getString(R.string.notification_channel_heads_up_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_heads_up_desc)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_FINANCIAL_ALERTS: String = "financial_alerts"
        private const val TAG = "NotificationListener"

        @Volatile
        var isConnected: Boolean = false

        /**
         * Rebind routine: Disables and re-enables component setting and requests rebind.
         */
        fun rebindService(context: Context) {
            try {
                val component = ComponentName(context, NotificationListenerServiceImpl::class.java)
                val pm = context.packageManager
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    requestRebind(component)
                }
            } catch (e: Exception) {
                Diagnostics.record(TAG, "${e.javaClass.simpleName}: rebindService")
            }
        }

        /**
         * Checks whether notification listener access is currently enabled in system settings.
         */
        fun isAccessEnabled(context: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }
    }
}
