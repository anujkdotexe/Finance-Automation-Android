package com.financeautomation.service.worker

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.financeautomation.FinanceApplication
import com.financeautomation.core.model.Result as DomainResult
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Jetpack CoroutineWorker that synchronizes pending transactions to the remote Google Sheet.
 * Enforces network connectivity constraints and handles exponential backoff retries.
 */
class SyncTransactionsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @SuppressLint("RestrictedApi")
    override suspend fun doWork(): androidx.work.ListenableWorker.Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? FinanceApplication
        val repository = testRepository ?: app?.appContainer?.transactionRepository
        if (repository == null) {
            return@withContext androidx.work.ListenableWorker.Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to "TransactionRepository is not available")
            )
        }

        val syncResult = executeSync(repository, runAttemptCount)
        if (syncResult is androidx.work.ListenableWorker.Result.Success) {
            try {
                app?.appContainer?.peopleListRebuilder?.refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        syncResult
    }

    companion object {
        const val WORK_NAME_EXPEDITED: String = "sync_transactions_expedited"
        const val WORK_NAME_PERIODIC: String = "sync_transactions_periodic"
        const val KEY_SYNCED_COUNT: String = "synced_count"
        const val KEY_ERROR_MESSAGE: String = "error_message"
        const val MAX_RETRIES: Int = 3

        internal var testRepository: TransactionRepository? = null

        /**
         * Pure business logic for executing pending synchronization.
         */
        suspend fun executeSync(
            repository: TransactionRepository,
            runAttemptCount: Int
        ): androidx.work.ListenableWorker.Result {
            PipelineTrail.record(PipelineStage.SYNC, PipelineCode.QUEUED)
            return try {
                when (val result = repository.syncPendingTransactions()) {
                    is DomainResult.Success -> {
                        PipelineTrail.record(PipelineStage.SYNC, PipelineCode.SUCCESS, a = result.data.toLong())
                        androidx.work.ListenableWorker.Result.success(
                            workDataOf(KEY_SYNCED_COUNT to result.data)
                        )
                    }
                    is DomainResult.Error -> {
                        if (runAttemptCount < MAX_RETRIES) {
                            PipelineTrail.record(PipelineStage.SYNC, PipelineCode.TRANSPORT_FAILURE)
                            androidx.work.ListenableWorker.Result.retry()
                        } else {
                            PipelineTrail.record(PipelineStage.SYNC, PipelineCode.NEEDS_REVIEW)
                            androidx.work.ListenableWorker.Result.failure(
                                workDataOf(KEY_ERROR_MESSAGE to (result.exception.message ?: "Unknown sync error"))
                            )
                        }
                    }
                    is DomainResult.Loading -> {
                        androidx.work.ListenableWorker.Result.retry()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (runAttemptCount < MAX_RETRIES) {
                    androidx.work.ListenableWorker.Result.retry()
                } else {
                    androidx.work.ListenableWorker.Result.failure(
                        workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Sync execution failed"))
                    )
                }
            }
        }

        fun createNetworkConstraints(): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        }

        fun enqueueExpedited(context: Context) {
            val constraints = createNetworkConstraints()
            val request = OneTimeWorkRequestBuilder<SyncTransactionsWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_EXPEDITED,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        fun enqueuePeriodic(context: Context) {
            val constraints = createNetworkConstraints()
            val request = PeriodicWorkRequestBuilder<SyncTransactionsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
