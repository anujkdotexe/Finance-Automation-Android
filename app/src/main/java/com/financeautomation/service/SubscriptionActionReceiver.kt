package com.financeautomation.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.financeautomation.FinanceApplication
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.usecase.SubscriptionActionUseCase
import com.financeautomation.domain.usecase.SubscriptionYesOutcome
import com.financeautomation.service.worker.SyncTransactionsWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the Yes, Not yet and Skip this time notification actions of a subscription reminder.
 * Registered with android:exported="false"; only explicit, immutable PendingIntents reach it.
 * Yes for a subscription with a varying amount does not come here: that action opens the expense sheet.
 */
class SubscriptionActionReceiver : BroadcastReceiver() {

    internal var testActions: SubscriptionActionUseCase? = null
    internal var testDispatcher: CoroutineDispatcher? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action != ACTION_YES && action != ACTION_NOT_YET && action != ACTION_SKIP) return
        val cycleId = intent.getStringExtra(EXTRA_CYCLE_ID) ?: return

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(notificationId)
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as? FinanceApplication
        val dispatcher = testDispatcher ?: Dispatchers.IO
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            try {
                val actions = testActions ?: app?.appContainer?.subscriptionActionUseCase
                if (actions != null) {
                    val logged = handle(action, cycleId, actions)
                    if (logged) {
                        try {
                            SyncTransactionsWorker.enqueueExpedited(context.applicationContext)
                        } catch (e: Exception) {
                            Diagnostics.record("SubscriptionActionReceiver", "${e.javaClass.simpleName}: enqueueExpedited")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Diagnostics.record("SubscriptionActionReceiver", "${e.javaClass.simpleName}: onReceive")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_YES: String = "com.financeautomation.ACTION_SUBSCRIPTION_YES"
        const val ACTION_NOT_YET: String = "com.financeautomation.ACTION_SUBSCRIPTION_NOT_YET"
        const val ACTION_SKIP: String = "com.financeautomation.ACTION_SUBSCRIPTION_SKIP"
        const val EXTRA_CYCLE_ID: String = "com.financeautomation.extra.SUBSCRIPTION_CYCLE_ID"
        const val EXTRA_NOTIFICATION_ID: String = "com.financeautomation.extra.NOTIFICATION_ID"

        /** Applies the action. Returns true when an expense was logged (a sync should follow). */
        suspend fun handle(action: String, cycleId: String, actions: SubscriptionActionUseCase): Boolean {
            return when (action) {
                ACTION_YES -> actions.yes(cycleId) is SubscriptionYesOutcome.Logged
                ACTION_NOT_YET -> { actions.notYet(cycleId); false }
                ACTION_SKIP -> { actions.skip(cycleId); false }
                else -> false
            }
        }
    }
}
