package com.financeautomation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import com.financeautomation.presentation.ui.EntryActivity
import kotlinx.coroutines.CancellationException

/**
 * Helper object for constructing and posting heads-up fallback notifications when overlay permission is absent (Phase 5.5).
 */
object ForegroundHeadsUpHelper {

    suspend fun postHeadsUpNotification(
        context: Context,
        capture: PendingCaptureEntity,
        categoryStatDaoProvider: (suspend () -> CategoryStatDao)?,
        predictCategoryUseCase: PredictCategoryUseCase?
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NotificationListenerServiceImpl.CHANNEL_FINANCIAL_ALERTS,
                context.getString(R.string.notification_channel_heads_up_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_heads_up_desc)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationId = capture.id.hashCode()

        // Details button opens EntryActivity
        val isCredit = capture.kind.equals(PendingCaptureEntity.KIND_CREDIT, ignoreCase = true)
        val detailsIntent = com.financeautomation.presentation.ui.InternalIntentAuth.attach(
            context,
            Intent(context, EntryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EntryActivity.EXTRA_CAPTURE_ID, capture.id)
                if (isCredit) {
                    putExtra(EntryActivity.EXTRA_FLOW, "INCOME_RETURN")
                }
            }
        )
        val pendingDetails = PendingIntent.getActivity(
            context,
            notificationId,
            detailsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Top two predicted categories (spec 5.4 point 5 & 5.5 point 2)
        val statDao = categoryStatDaoProvider?.invoke()
        val (cat1, cat2) = if (statDao != null && predictCategoryUseCase != null) {
            try {
                val key = predictCategoryUseCase.computePayeeKey(capture.payee)
                val stats = statDao.getStatsForPayee(key).map { it.category }
                val global = statDao.getGlobalCategoryRank()
                val ranked = predictCategoryUseCase(
                    capture.payee,
                    stats,
                    global,
                    CategoryConstants.DEFAULT_CATEGORIES
                )
                val first = ranked.getOrNull(0) ?: CategoryConstants.CATEGORY_FOOD_AND_DRINK
                val second = ranked.getOrNull(1) ?: CategoryConstants.CATEGORY_GROCERIES
                Pair(first, second)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Pair(CategoryConstants.CATEGORY_FOOD_AND_DRINK, CategoryConstants.CATEGORY_GROCERIES)
            }
        } else {
            Pair(CategoryConstants.CATEGORY_FOOD_AND_DRINK, CategoryConstants.CATEGORY_GROCERIES)
        }

        val intentCat1 = Intent(context, QuickActionReceiver::class.java).apply {
            action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE
            putExtra(QuickActionReceiver.EXTRA_CAPTURE_ID, capture.id)
            putExtra(QuickActionReceiver.EXTRA_CATEGORY, cat1)
            putExtra(QuickActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingCat1 = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 1,
            intentCat1,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentCat2 = Intent(context, QuickActionReceiver::class.java).apply {
            action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE
            putExtra(QuickActionReceiver.EXTRA_CAPTURE_ID, capture.id)
            putExtra(QuickActionReceiver.EXTRA_CATEGORY, cat2)
            putExtra(QuickActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingCat2 = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 2,
            intentCat2,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, QuickActionReceiver::class.java).apply {
            action = QuickActionReceiver.ACTION_DISMISS_CAPTURE
            putExtra(QuickActionReceiver.EXTRA_CAPTURE_ID, capture.id)
            putExtra(QuickActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingDismiss = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 3,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val amountStr = capture.amount?.let { formatRupees(it, includeDecimals = true) } ?: "Pending review"
        val titleText = if (capture.payee.isNotBlank()) capture.payee else context.getString(R.string.notif_payment_detected_title)

        val appLockOn = NotificationPrivacy.isAppLockOn(context)
        val builder = NotificationCompat.Builder(context, NotificationListenerServiceImpl.CHANNEL_FINANCIAL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(titleText)
            .setContentText(amountStr)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingDetails)
            .setDeleteIntent(pendingDismiss)
        if (isCredit) {
            builder.addAction(0, context.getString(R.string.action_menu_income_title), pendingDetails)
        } else {
            builder.addAction(0, cat1, pendingCat1)
            builder.addAction(0, cat2, pendingCat2)
            builder.addAction(0, context.getString(R.string.label_details), pendingDetails)
        }
        NotificationPrivacy.apply(
            builder, context, NotificationListenerServiceImpl.CHANNEL_FINANCIAL_ALERTS, appLockOn,
            publicTitle = context.getString(R.string.notif_public_payment_title)
        )

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.HEADS_UP_BLOCKED_NOTIFICATIONS_OFF)
        } else {
            PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.HEADS_UP_POSTED)
        }

        notificationManager.notify(notificationId, builder.build())
    }
}

