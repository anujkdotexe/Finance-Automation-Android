package com.financeautomation.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.presentation.ui.EntryActivity
import com.financeautomation.service.worker.SubscriptionReminderNotifier

/**
 * Posts the "Did you pay <name>?" notification with Yes, Not yet and Skip this time actions.
 * All PendingIntents are immutable and target explicit, non-exported components.
 */
class SubscriptionNotifier(private val context: Context) : SubscriptionReminderNotifier {

    override fun post(subscription: Subscription, cycle: SubscriptionCycle) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(manager)

        val notificationId = notificationId(cycle.id)
        val amountText = subscription.amount?.let { formatRupees(it) } ?: "Amount varies"

        val yesIntent = receiverIntent(SubscriptionActionReceiver.ACTION_YES, cycle.id, notificationId, 0)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.subscription_notification_title, subscription.name))
            .setContentText(context.getString(R.string.subscription_notification_due_today, amountText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.notification_action_yes), yesIntent)
            .addAction(0, context.getString(R.string.notification_action_not_yet), receiverIntent(SubscriptionActionReceiver.ACTION_NOT_YET, cycle.id, notificationId, 1))
            .addAction(0, context.getString(R.string.notification_action_skip), receiverIntent(SubscriptionActionReceiver.ACTION_SKIP, cycle.id, notificationId, 2))
            .build()
        manager.notify(notificationId, notification)
    }

    private fun receiverIntent(action: String, cycleId: String, notificationId: Int, slot: Int): PendingIntent {
        val intent = Intent(context, SubscriptionActionReceiver::class.java).apply {
            this.action = action
            putExtra(SubscriptionActionReceiver.EXTRA_CYCLE_ID, cycleId)
            putExtra(SubscriptionActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(cycleId, slot),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID: String = "subscription_reminders"
        const val CHANNEL_NAME: String = "Subscription reminders"

        fun notificationId(cycleId: String): Int = cycleId.hashCode()
        private fun requestCode(cycleId: String, slot: Int): Int = cycleId.hashCode() * 3 + slot
    }
}
