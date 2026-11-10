package com.financeautomation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.financeautomation.FinanceApplication
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.presentation.ui.ModalBottomSheetActivity
import com.financeautomation.service.worker.SettleWindowWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Outcome of processing an incoming system notification.
 */
sealed interface NotificationProcessingOutcome {
    data class Created(
        val transaction: Transaction,
        val isPossibleDuplicate: Boolean,
        val headsUpNotificationData: HeadsUpData
    ) : NotificationProcessingOutcome

    data class Merged(
        val existingTransactionId: String,
        val enriched: Boolean
    ) : NotificationProcessingOutcome

    data class FallbackPrompt(
        val message: String,
        val sourcePackage: String
    ) : NotificationProcessingOutcome

    data class Ignored(
        val reason: String
    ) : NotificationProcessingOutcome
}

/**
 * Encapsulated data required to construct a 3-button Heads-Up notification.
 */
data class HeadsUpData(
    val notificationId: Int,
    val title: String,
    val text: String,
    val category1: String,
    val category2: String,
    val detailsActionTitle: String,
    val transactionId: String
)

/**
 * Processing engine decoupled from Android Service lifecycle for deterministic unit testing.
 */
class NotificationProcessor(
    private val repository: TransactionRepository,
    private val parseUseCase: ParseNotificationUseCase = ParseNotificationUseCase(),
    private val ownPackageName: String = "com.financeautomation"
) {

    suspend fun process(
        sourcePackage: String,
        title: String,
        text: String,
        postTime: Long = System.currentTimeMillis()
    ): NotificationProcessingOutcome {
        // 1. Self-notification check
        if (sourcePackage.trim() == ownPackageName) {
            return NotificationProcessingOutcome.Ignored("Self notification dropped")
        }

        // 2. Blacklist check
        if (PackageWhitelist.isBlacklisted(sourcePackage)) {
            return NotificationProcessingOutcome.Ignored("Blacklisted privacy package: $sourcePackage")
        }

        // 3. Whitelist check
        if (!PackageWhitelist.isWhitelisted(sourcePackage)) {
            return NotificationProcessingOutcome.Ignored("Non-whitelisted package: $sourcePackage")
        }

        // 4. OTP suppression check
        val fullContent = "$title $text".trim()
        if (PackageWhitelist.isOtpOrAuth(fullContent)) {
            return NotificationProcessingOutcome.Ignored("OTP or auth alert suppressed")
        }

        // 5. Parse candidate via domain ParseNotificationUseCase
        val parseResult = parseUseCase(
            sourcePackage = sourcePackage,
            title = title,
            text = text,
            appName = null,
            timestamp = postTime
        )

        return when (parseResult) {
            is NotificationParseResult.Dropped -> {
                NotificationProcessingOutcome.Ignored("ParseUseCase dropped: ${parseResult.reason}")
            }
            is NotificationParseResult.FallbackPrompt -> {
                NotificationProcessingOutcome.FallbackPrompt(
                    message = parseResult.message,
                    sourcePackage = parseResult.sourcePackage
                )
            }
            is NotificationParseResult.FailureAlert -> {
                NotificationProcessingOutcome.Ignored("Payment failed, declined or reversed")
            }
            is NotificationParseResult.Success -> {
                if (!parseResult.isDebit) {
                    return NotificationProcessingOutcome.Ignored("Non-debit transaction ignored for auto-expense logging")
                }

                val candidate = parseResult.transaction
                val recentSimilar = repository.findRecentSimilar(candidate.totalAmount, windowMillis = 120_000L)

                val exactMatch = recentSimilar.firstOrNull { existing ->
                    (existing.merchant.equals(candidate.merchant, ignoreCase = true) && existing.sourcePackage == candidate.sourcePackage) ||
                    (existing.merchant == "Unknown Payee" && candidate.merchant != "Unknown Payee") ||
                    (candidate.merchant == "Unknown Payee" && existing.merchant != "Unknown Payee")
                }

                if (exactMatch != null) {
                    var enriched = false
                    var mergedTxn = exactMatch

                    if (exactMatch.merchant == "Unknown Payee" && candidate.merchant != "Unknown Payee") {
                        mergedTxn = mergedTxn.copy(merchant = candidate.merchant)
                        enriched = true
                    }
                    if (exactMatch.account == CategoryConstants.ACCOUNT_OTHER && candidate.account != CategoryConstants.ACCOUNT_OTHER) {
                        mergedTxn = mergedTxn.copy(account = candidate.account)
                        enriched = true
                    }
                    if (enriched) {
                        repository.updateTransaction(mergedTxn)
                    }
                    return NotificationProcessingOutcome.Merged(
                        existingTransactionId = exactMatch.id,
                        enriched = enriched
                    )
                }

                val isPossibleDuplicate = recentSimilar.isNotEmpty()
                val finalTransaction = candidate.copy(
                    reviewState = ReviewState.NEEDS_REVIEW,
                    syncState = SyncState.PENDING,
                    isPossibleDuplicate = isPossibleDuplicate
                )

                repository.insertTransaction(finalTransaction)

                val notificationId = (finalTransaction.id.hashCode() and 0x7FFFFFFF)
                val headsUpData = HeadsUpData(
                    notificationId = notificationId,
                    title = String.format(Locale.US, "Paid ₹%.2f to %s", finalTransaction.totalAmount, finalTransaction.merchant),
                    text = if (isPossibleDuplicate) "Possible duplicate • Tap to review" else "Tap Details / Split",
                    category1 = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
                    category2 = CategoryConstants.CATEGORY_GROCERIES,
                    detailsActionTitle = "Details / Split",
                    transactionId = finalTransaction.id
                )

                NotificationProcessingOutcome.Created(
                    transaction = finalTransaction,
                    isPossibleDuplicate = isPossibleDuplicate,
                    headsUpNotificationData = headsUpData
                )
            }
        }
    }
}

/**
 * Android NotificationListenerService intercepting financial notifications from whitelisted package IDs.
 * Implements self-healing listener rebinds and multi-source deduplication.
 */
class NotificationListenerServiceImpl : NotificationListenerService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    internal var testRepository: TransactionRepository? = null
    internal var testProcessor: NotificationProcessor? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.notification == null) return

        val sourcePackage = sbn.packageName ?: return
        if (sourcePackage == packageName) return

        val extras = sbn.notification.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        val postTime = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()

        handleNotification(sourcePackage, title, text, postTime)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val notifications = activeNotifications ?: return
            val now = System.currentTimeMillis()
            for (sbn in notifications) {
                if (sbn != null && sbn.notification != null) {
                    val postTime = sbn.postTime
                    if (now - postTime < 300_000L) {
                        onNotificationPosted(sbn)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestRebind(ComponentName(this, NotificationListenerServiceImpl::class.java))
            }
        } catch (_: Exception) {
        }
    }

    fun handleNotification(sourcePackage: String, title: String, text: String, postTime: Long) {
        val app = applicationContext as? FinanceApplication
        val repository = testRepository ?: app?.appContainer?.transactionRepository ?: return
        val processor = testProcessor ?: NotificationProcessor(
            repository = repository,
            parseUseCase = (applicationContext as? FinanceApplication)?.appContainer?.parseNotificationUseCase
                ?: ParseNotificationUseCase(packageName),
            ownPackageName = packageName
        )

        serviceScope.launch {
            try {
                val outcome = processor.process(sourcePackage, title, text, postTime)
                when (outcome) {
                    is NotificationProcessingOutcome.Created -> {
                        if (!outcome.isPossibleDuplicate) {
                            try {
                                SettleWindowWorker.scheduleSettleWindow(
                                    context = applicationContext,
                                    txnId = outcome.transaction.id,
                                    delaySeconds = SettleWindowWorker.DEFAULT_SETTLE_WINDOW_SECONDS
                                )
                            } catch (_: Exception) {
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this@NotificationListenerServiceImpl)) {
                            val intent = Intent(this@NotificationListenerServiceImpl, ModalBottomSheetActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra(ModalBottomSheetActivity.EXTRA_TRANSACTION_ID, outcome.transaction.id)
                                putExtra(QuickActionReceiver.EXTRA_TRANSACTION_ID, outcome.transaction.id)
                            }
                            startActivity(intent)
                        } else {
                            showHeadsUpNotification(outcome.headsUpNotificationData)
                        }
                    }
                    is NotificationProcessingOutcome.FallbackPrompt -> {
                        showFallbackNotification(outcome.message, outcome.sourcePackage)
                    }
                    is NotificationProcessingOutcome.Merged -> {
                    }
                    is NotificationProcessingOutcome.Ignored -> {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showHeadsUpNotification(data: HeadsUpData) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        createNotificationChannel(notificationManager)

        val intentDetails = Intent(this, ModalBottomSheetActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(QuickActionReceiver.EXTRA_TRANSACTION_ID, data.transactionId)
            putExtra(QuickActionReceiver.EXTRA_NOTIFICATION_ID, data.notificationId)
        }
        val pendingIntentDetails = PendingIntent.getActivity(
            this,
            data.notificationId * 10 + 3,
            intentDetails,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_FINANCIAL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(data.title)
            .setContentText(data.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntentDetails)
            .addAction(0, data.detailsActionTitle, pendingIntentDetails)

        notificationManager.notify(data.notificationId, builder.build())
    }

    private fun showFallbackNotification(message: String, sourcePackage: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        createNotificationChannel(notificationManager)

        val intent = Intent(this, ModalBottomSheetActivity::class.java).apply {
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
            .setContentTitle("Payment Detected")
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
                "Financial Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Expense review and categorization alerts"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_FINANCIAL_ALERTS: String = "financial_alerts"
    }
}
