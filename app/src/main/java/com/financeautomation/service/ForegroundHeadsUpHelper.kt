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
import com.financeautomation.data.local.dao.CategoryDao
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import com.financeautomation.presentation.ui.EntryActivity
import kotlinx.coroutines.CancellationException

/**
 * Helper object for constructing and posting heads-up fallback notifications when overlay permission is absent (Phase 5.5).
 */
object ForegroundHeadsUpHelper {

    /**
     * Pure function to pick candidate categories for quick action buttons.
     * Uses the user's own live categories list (never hardcoded defaults).
     * If availableCategories is empty, returns Pair(null, null).
     */
    fun pickCategories(
        payee: String,
        availableCategories: List<String>,
        payeeHistory: List<String> = emptyList(),
        globalRank: List<String> = emptyList(),
        predictCategoryUseCase: PredictCategoryUseCase? = null
    ): Pair<String?, String?> {
        val candidates = availableCategories.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return Pair(null, null)

        val ranked = if (predictCategoryUseCase != null) {
            predictCategoryUseCase(payee, payeeHistory, globalRank, candidates)
                .filter { it in candidates }
        } else {
            emptyList()
        }

        val first = ranked.getOrNull(0) ?: candidates.getOrNull(0)
        val remaining = candidates.filter { it != first }
        val second = ranked.firstOrNull { it != first } ?: remaining.getOrNull(0)

        return Pair(first, second)
    }

    suspend fun postHeadsUpNotification(
        context: Context,
        capture: PendingCaptureEntity,
        categoryStatDaoProvider: (suspend () -> CategoryStatDao)?,
        predictCategoryUseCase: PredictCategoryUseCase?,
        categoryDaoProvider: (suspend () -> CategoryDao)? = null
    ): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
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

        // Top two predicted categories from user's live categories (finding 25)
        val categoryDao = categoryDaoProvider?.invoke()
        val userCategories = categoryDao?.getActiveCategories()?.map { it.name }.orEmpty()

        val statDao = categoryStatDaoProvider?.invoke()
        val (cat1, cat2) = if (statDao != null && predictCategoryUseCase != null) {
            try {
                val key = predictCategoryUseCase.computePayeeKey(capture.payee)
                val stats = statDao.getStatsForPayee(key).map { it.category }
                val global = statDao.getGlobalCategoryRank()
                pickCategories(
                    payee = capture.payee,
                    availableCategories = userCategories,
                    payeeHistory = stats,
                    globalRank = global,
                    predictCategoryUseCase = predictCategoryUseCase
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                pickCategories(capture.payee, userCategories)
            }
        } else {
            pickCategories(capture.payee, userCategories)
        }

        val pendingCat1 = cat1?.let { categoryName ->
            val intentCat1 = Intent(context, QuickActionReceiver::class.java).apply {
                action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE
                putExtra(QuickActionReceiver.EXTRA_CAPTURE_ID, capture.id)
                putExtra(QuickActionReceiver.EXTRA_CATEGORY, categoryName)
                putExtra(QuickActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            PendingIntent.getBroadcast(
                context,
                notificationId * 10 + 1,
                intentCat1,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val pendingCat2 = cat2?.let { categoryName ->
            val intentCat2 = Intent(context, QuickActionReceiver::class.java).apply {
                action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE
                putExtra(QuickActionReceiver.EXTRA_CAPTURE_ID, capture.id)
                putExtra(QuickActionReceiver.EXTRA_CATEGORY, categoryName)
                putExtra(QuickActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            PendingIntent.getBroadcast(
                context,
                notificationId * 10 + 2,
                intentCat2,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

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
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingDetails)
        if (isCredit) {
            builder.addAction(0, context.getString(R.string.action_menu_income_title), pendingDetails)
        } else {
            if (cat1 != null && pendingCat1 != null) {
                builder.addAction(0, cat1, pendingCat1)
            }
            if (cat2 != null && pendingCat2 != null) {
                builder.addAction(0, cat2, pendingCat2)
            }
            builder.addAction(0, context.getString(R.string.label_details), pendingDetails)
        }
        NotificationPrivacy.apply(
            builder, context, NotificationListenerServiceImpl.CHANNEL_FINANCIAL_ALERTS, appLockOn,
            publicTitle = context.getString(R.string.notif_public_payment_title)
        )

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.HEADS_UP_BLOCKED_NOTIFICATIONS_OFF)
            // Nothing can be shown; the caller backs off and the capture reaches Needs Review through normal expiry
            return false
        }
        PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.HEADS_UP_POSTED)

        notificationManager.notify(notificationId, builder.build())
        return true
    }
}

