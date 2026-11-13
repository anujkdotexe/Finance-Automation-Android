package com.financeautomation.presentation.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import com.financeautomation.FinanceApplication
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.usecase.SubscriptionYesOutcome
import com.financeautomation.service.SubscriptionActionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target of the Yes notification action for a subscription whose amount varies. It is started by the action's own
 * PendingIntent (an activity, so Android allows it), is not exported, and creates the prefilled capture only now,
 * when the user really taps Yes. Then it opens the normal expense sheet through [EntryActivity].
 */
class SubscriptionYesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cycleId = intent.getStringExtra(SubscriptionActionReceiver.EXTRA_CYCLE_ID)
        val notificationId = intent.getIntExtra(SubscriptionActionReceiver.EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(notificationId)
        }
        if (cycleId.isNullOrBlank()) {
            finish()
            return
        }
        val app = application as FinanceApplication
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = try {
                app.appContainer.subscriptionActionUseCase.yes(cycleId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Diagnostics.record("SubscriptionYesActivity", "${e.javaClass.simpleName}: yes")
                null
            }
            withContext(Dispatchers.Main) {
                if (outcome is SubscriptionYesOutcome.NeedsAmount) {
                    startActivity(
                        Intent(this@SubscriptionYesActivity, EntryActivity::class.java).apply {
                            putExtra(EntryActivity.EXTRA_CAPTURE_ID, outcome.captureId)
                        }
                    )
                }
                finish()
            }
        }
    }
}
