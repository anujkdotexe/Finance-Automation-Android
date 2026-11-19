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
import kotlinx.coroutines.CancellationException
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
        if (action != ACTION_QUICK_CATEGORIZE && action != ACTION_QUICK_CONFIRM && action != ACTION_DISMISS_CAPTURE) {
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

        if (action == ACTION_DISMISS_CAPTURE) {
            CoroutineScope(SupervisorJob() + dispatcher).launch {
                try {
                    val container = app?.appContainer
                    if (!captureId.isNullOrBlank() && container != null) {
                        val db = container.awaitDatabase()
                        db.pendingCaptureDao().updateState(captureId, com.financeautomation.data.local.entity.PendingCaptureEntity.STATE_DISMISSED)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        CoroutineScope(SupervisorJob() + dispatcher).launch {
            try {
                val container = app?.appContainer
                if (!captureId.isNullOrBlank() && container != null) {
                    val db = container.awaitDatabase()
                    val captureDao = db.pendingCaptureDao()
                    val activeCategories = db.categoryDao().getActiveCategories()
                    val matchedCategory = category?.trim()?.let { raw ->
                        activeCategories.firstOrNull { it.name.trim().equals(raw, ignoreCase = true) }?.name
                    }
                    val resolvedCategory = matchedCategory
                        ?: activeCategories.firstOrNull { it.name.trim().equals(CategoryConstants.CATEGORY_OTHER, ignoreCase = true) }?.name
                        ?: activeCategories.firstOrNull()?.name

                    var loggedCapture: com.financeautomation.data.local.entity.PendingCaptureEntity? = null
                    var loggedCategoryName: String? = null
                    val outcome = CaptureClaim.logOnce(captureDao, captureId) { capture ->
                        if (capture.kind.equals(com.financeautomation.data.local.entity.PendingCaptureEntity.KIND_CREDIT, ignoreCase = true)) {
                            // Credit captures must never be saved as a debit expense
                            return@logOnce false
                        }
                        if (resolvedCategory == null) {
                            captureDao.updateState(capture.id, com.financeautomation.data.local.entity.PendingCaptureEntity.STATE_NEEDS_REVIEW)
                            return@logOnce false
                        }
                        val loggedCategory = resolvedCategory
                        loggedCategoryName = loggedCategory
                        val amount = capture.amount ?: 0.0
                        val account = capture.inferredAccount?.takeIf { it.isNotBlank() } ?: CategoryConstants.ACCOUNT_OTHER
                        val subscriptionId = com.financeautomation.data.local.entity.PendingCaptureEntity.flagValue(
                            capture.flags,
                            com.financeautomation.data.local.entity.PendingCaptureEntity.FLAG_SUBSCRIPTION_PREFIX
                        )
                        val captureEpochDay = java.time.Instant.ofEpochMilli(capture.timestamp)
                            .atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().toEpochDay()
                        val logResult = container.logExpenseUseCase(
                            amount = amount,
                            merchant = capture.payee.ifBlank { "Expense" },
                            category = loggedCategory,
                            account = account,
                            sourcePackage = capture.sourcePackage,
                            timestamp = capture.timestamp,
                            reviewState = ReviewState.CONFIRMED,
                            transactionId = CaptureClaim.expenseIdFor(capture.id),
                            afterInsert = if (subscriptionId != null) {
                                { txn ->
                                    container.subscriptionRepository
                                        .markCyclePaidForSubscription(subscriptionId, captureEpochDay, txn.id)
                                }
                            } else null
                        )
                        val ok = logResult is com.financeautomation.core.model.Result.Success<*>
                        if (ok) loggedCapture = capture
                        ok
                    }
                    val capture = loggedCapture
                    if (outcome == CaptureClaim.Outcome.LOGGED && capture != null) {
                        try {
                            val statDao = db.categoryStatDao()
                            val key = container.predictCategoryUseCase.computePayeeKey(capture.payee)
                            val finalCat = loggedCategoryName ?: resolvedCategory ?: CategoryConstants.CATEGORY_OTHER
                            statDao.incrementStat(key, finalCat, System.currentTimeMillis())
                        } catch (statEx: Exception) {
                            Diagnostics.record("QuickActionReceiver", "${statEx.javaClass.simpleName}: incrementStat")
                        }
                        try {
                            SyncTransactionsWorker.enqueueExpedited(context.applicationContext)
                        } catch (e: Exception) {
                            Diagnostics.record("QuickActionReceiver", "${e.javaClass.simpleName}: enqueueExpedited")
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
        const val ACTION_DISMISS_CAPTURE: String = "com.financeautomation.ACTION_DISMISS_CAPTURE"

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
