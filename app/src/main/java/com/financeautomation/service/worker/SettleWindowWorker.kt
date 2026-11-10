package com.financeautomation.service.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Worker buffering incoming notification transactions with a settle delay
 * before triggering background synchronization.
 */
class SettleWindowWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        SyncTransactionsWorker.enqueueExpedited(applicationContext)
        return Result.success()
    }

    companion object {
        const val DEFAULT_SETTLE_WINDOW_SECONDS: Long = 90L
        const val WORK_NAME_PREFIX: String = "settle_window_"
        const val KEY_TRANSACTION_ID: String = "key_transaction_id"

        fun scheduleSettleWindow(
            context: Context,
            txnId: String,
            delaySeconds: Long = DEFAULT_SETTLE_WINDOW_SECONDS
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SettleWindowWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_TRANSACTION_ID to txnId))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME_PREFIX$txnId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

