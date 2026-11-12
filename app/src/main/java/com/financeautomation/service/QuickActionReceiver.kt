package com.financeautomation.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.financeautomation.FinanceApplication
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.service.worker.SyncTransactionsWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Result representing the outcome of a quick action intent processing.
 */
sealed interface QuickActionResult {
    data class Success(val transaction: Transaction, val confirmedCategory: String) : QuickActionResult
    data class NotFound(val transactionId: String) : QuickActionResult
    data class Ignored(val reason: String) : QuickActionResult
}

/**
 * BroadcastReceiver processing 1-tap notification action chips via goAsync().
 * Upgrades ReviewState from NEEDS_REVIEW to CONFIRMED, dismisses the heads-up alert,
 * and dispatches expedited synchronization to Google Sheets.
 */
class QuickActionReceiver : BroadcastReceiver() {

    // Test overrides for unit testing
    internal var testRepository: TransactionRepository? = null
    internal var testDispatcher: CoroutineDispatcher? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        if (action != ACTION_QUICK_CATEGORIZE && action != ACTION_QUICK_CONFIRM) {
            return
        }

        val captureId = intent.getStringExtra(EXTRA_CAPTURE_ID)
            ?: intent.getStringExtra("capture_id")

        val txnId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
            ?: intent.getStringExtra("txn_id")
            ?: intent.getStringExtra("txn_uuid")

        if (captureId.isNullOrBlank() && txnId.isNullOrBlank()) {
            return
        }

        val category = intent.getStringExtra(EXTRA_CATEGORY)
            ?: intent.getStringExtra("category_name")

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(notificationId)
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as? FinanceApplication
        val dispatcher = testDispatcher ?: Dispatchers.IO

        CoroutineScope(SupervisorJob() + dispatcher).launch {
            try {
                val container = app?.appContainer
                if (!captureId.isNullOrBlank() && container != null) {
                    val db = container.awaitDatabase()
                    val captureDao = db.pendingCaptureDao()
                    val capture = captureDao.getById(captureId)
                    if (capture != null) {
                        val amount = capture.amount ?: 0.0
                        val resolvedCategory = category ?: CategoryConstants.CATEGORY_FOOD_AND_DRINK
                        val account = capture.inferredAccount?.takeIf { it.isNotBlank() } ?: CategoryConstants.ACCOUNT_OTHER

                        if (amount > 0.0) {
                            val subscriptionId = com.financeautomation.data.local.entity.PendingCaptureEntity.flagValue(
                                capture.flags,
                                com.financeautomation.data.local.entity.PendingCaptureEntity.FLAG_SUBSCRIPTION_PREFIX
                            )
                            val captureEpochDay = java.time.Instant.ofEpochMilli(capture.timestamp)
                                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().toEpochDay()
                            val logResult = container.logExpenseUseCase(
                                amount = amount,
                                merchant = capture.payee.ifBlank { "Expense" },
                                category = resolvedCategory,
                                account = account,
                                sourcePackage = capture.sourcePackage,
                                timestamp = capture.timestamp,
                                reviewState = ReviewState.CONFIRMED,
                                afterInsert = if (subscriptionId != null) {
                                    { txn ->
                                        container.subscriptionRepository
                                            .markCyclePaidForSubscription(subscriptionId, captureEpochDay, txn.id)
                                    }
                                } else null
                            )
                            if (logResult is com.financeautomation.core.model.Result.Success<*>) {
                                captureDao.updateState(capture.id, com.financeautomation.data.local.entity.PendingCaptureEntity.STATE_DONE)
                                try {
                                    val statDao = db.categoryStatDao()
                                    val key = container.predictCategoryUseCase.computePayeeKey(capture.payee)
                                    statDao.incrementStat(key, resolvedCategory, System.currentTimeMillis())
                                } catch (statEx: Exception) {
                                    Diagnostics.record("QuickActionReceiver", "${statEx.javaClass.simpleName}: incrementStat")
                                }
                                try {
                                    SyncTransactionsWorker.enqueueExpedited(context.applicationContext)
                                } catch (e: Exception) {
                                    Diagnostics.record("QuickActionReceiver", "${e.javaClass.simpleName}: enqueueExpedited")
                                }
                            }
                        }
                    }
                } else if (!txnId.isNullOrBlank()) {
                    val repository = testRepository ?: container?.transactionRepository
                    if (repository != null) {
                        val outcome = handleAction(
                            action = action,
                            txnId = txnId,
                            category = category,
                            repository = repository
                        )
                        if (outcome is QuickActionResult.Success) {
                            try {
                                SyncTransactionsWorker.enqueueExpedited(context.applicationContext)
                            } catch (e: Exception) {
                                Diagnostics.record("QuickActionReceiver", "${e.javaClass.simpleName}: enqueueExpedited")
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_QUICK_CATEGORIZE: String = "com.financeautomation.ACTION_QUICK_CATEGORIZE"
        const val ACTION_QUICK_CONFIRM: String = "com.financeautomation.ACTION_QUICK_CONFIRM"

        const val EXTRA_TRANSACTION_ID: String = "com.financeautomation.extra.TRANSACTION_ID"
        const val EXTRA_CAPTURE_ID: String = "com.financeautomation.extra.CAPTURE_ID"
        const val EXTRA_CATEGORY: String = "com.financeautomation.extra.CATEGORY"
        const val EXTRA_NOTIFICATION_ID: String = "com.financeautomation.extra.NOTIFICATION_ID"

        /**
         * Pure action handling logic for updating transaction categorization and confirmation.
         */
        suspend fun handleAction(
            action: String,
            txnId: String,
            category: String?,
            repository: TransactionRepository
        ): QuickActionResult {
            val existing = repository.getTransactionById(txnId)
                ?: return QuickActionResult.NotFound(txnId)

            val resolvedCategory = when {
                !category.isNullOrBlank() -> {
                    val trimmed = category.trim()
                    if (trimmed.equals("saving", ignoreCase = true) || trimmed.equals("savings", ignoreCase = true)) {
                        CategoryConstants.CATEGORY_SAVINGS
                    } else {
                        try {
                            CategoryConstants.normalizeCategory(trimmed)
                        } catch (e: Exception) {
                            Diagnostics.record("QuickActionReceiver", "${e.javaClass.simpleName}: normalizeCategory fallback")
                            trimmed
                        }
                    }
                }
                else -> existing.category
            }

            val updatedTransaction = existing.copy(
                category = resolvedCategory,
                reviewState = ReviewState.CONFIRMED
            )

            repository.updateTransaction(updatedTransaction)
            return QuickActionResult.Success(updatedTransaction, resolvedCategory)
        }
    }
}
