package com.financeautomation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.financeautomation.FinanceApplication
import com.financeautomation.data.local.entity.PendingCaptureEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver enabling live testing of incoming notifications on real Android devices over ADB.
 * Simulates genuine notifications from whitelisted payment apps (Google Pay, PhonePe, Paytm, BHIM, Bank SMS).
 */
class NotificationSimulatorReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != ACTION_SIMULATE_NOTIFICATION) return

        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE) ?: "com.google.android.apps.nbu.paisa.user"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Paid to Merchant"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "Paid Rs. 500 to Starbucks"
        val postTime = intent.getLongExtra(EXTRA_POST_TIME, System.currentTimeMillis())
        val sbnKey = intent.getStringExtra("sbn_key") ?: "$sourcePackage|$postTime"

        val app = context.applicationContext as? FinanceApplication ?: return
        val container = app.appContainer
        val watcher = container.foregroundWatcher

        scope.launch {
            val db = container.awaitDatabase()
            val processor = NotificationProcessor(
                pendingCaptureDao = db.pendingCaptureDao(),
                handledNotificationDao = db.handledNotificationDao(),
                accountDao = db.accountDao(),
                preferenceDao = db.preferenceDao(),
                repository = container.transactionRepository,
                parseUseCase = container.parseNotificationUseCase,
                ownPackageName = context.packageName
            )
            val outcome = processor.process(sourcePackage, title, text, postTime, sbnKey)
            when (outcome) {
                is NotificationProcessingOutcome.Captured -> {
                    if (outcome.capture.state == PendingCaptureEntity.STATE_WAITING) {
                        watcher.notifyCaptureWaiting(scope)
                    }
                }
                is NotificationProcessingOutcome.Updated -> {
                    if (outcome.capture.state == PendingCaptureEntity.STATE_WAITING) {
                        watcher.notifyCaptureWaiting(scope)
                    }
                }
                is NotificationProcessingOutcome.Created -> {
                    val launchIntent = Intent(context, com.financeautomation.presentation.ui.ModalBottomSheetActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(com.financeautomation.presentation.ui.ModalBottomSheetActivity.EXTRA_TRANSACTION_ID, outcome.transaction.id)
                        putExtra(QuickActionReceiver.EXTRA_TRANSACTION_ID, outcome.transaction.id)
                    }
                    context.startActivity(launchIntent)
                }
                else -> {
                }
            }
        }
    }

    companion object {
        const val ACTION_SIMULATE_NOTIFICATION: String = "com.financeautomation.ACTION_SIMULATE_NOTIFICATION"
        const val EXTRA_SOURCE_PACKAGE: String = "source_package"
        const val EXTRA_TITLE: String = "title"
        const val EXTRA_TEXT: String = "text"
        const val EXTRA_POST_TIME: String = "post_time"
    }
}
