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
        PipelineTrail.record(PipelineStage.ALERT, PipelineCode.WHITELISTED_SEEN)

        val extras = sbn.notification.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        val postTime = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        val key = sbn.key ?: ""

        handleNotification(sourcePackage, title, text, postTime, key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        PipelineTrail.record(PipelineStage.LISTENER, PipelineCode.CONNECTED)
        val app = applicationContext as? FinanceApplication
        val watcher = app?.appContainer?.foregroundWatcher

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
        sbnKey: String = ""
    ) {
        val app = applicationContext as? FinanceApplication
        val container = app?.appContainer
        val watcher = container?.foregroundWatcher

        serviceScope.launch {
            try {
                val processor = testProcessor ?: container?.let {
                    val db = it.awaitDatabase()
                    NotificationProcessor(
                        pendingCaptureDao = db.pendingCaptureDao(),
                        handledNotificationDao = db.handledNotificationDao(),
                        accountDao = db.accountDao(),
                        preferenceDao = db.preferenceDao(),
                        parseUseCase = it.parseNotificationUseCase,
                        ownPackageName = packageName
                    )
                } ?: return@launch

                val outcome = processor.process(sourcePackage, title, text, postTime, sbnKey)
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
                    is NotificationProcessingOutcome.FallbackPrompt -> {
                        showFallbackNotification(outcome.message, outcome.sourcePackage)
                    }
                    is NotificationProcessingOutcome.Merged -> {
                        // Merged dual alert without posting duplicate notification
                    }
                    is NotificationProcessingOutcome.Dismissed -> {
                        // Dismissed payment alert
                    }
                    is NotificationProcessingOutcome.Ignored -> {
                        // Ignored by whitelist, blacklist, OTP suppression, or direction filter
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record(TAG, "${e.javaClass.simpleName}: handleNotification execution")
            }
        }
    }

    private fun showFallbackNotification(message: String, sourcePackage: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        createNotificationChannel(notificationManager)

        val intent = Intent(this, EntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("source_package", sourcePackage)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            sourcePackage.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_FINANCIAL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(getString(R.string.notif_payment_detected_title))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(sourcePackage.hashCode(), builder.build())
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
