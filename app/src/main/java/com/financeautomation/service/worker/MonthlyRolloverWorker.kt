package com.financeautomation.service.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financeautomation.FinanceApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that triggers automatic monthly rollover.
 * Port of spec 3.5 step 6, changed by owner decision: once a month has started (Asia/Kolkata), moves the open
 * debts and payables of earlier months into it, idempotently, at the first chance on or after the 1st.
 */
class MonthlyRolloverWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? FinanceApplication
        val repository = app?.appContainer?.transactionRepository ?: return@withContext androidx.work.ListenableWorker.Result.retry()
        val syncUseCase: suspend () -> com.financeautomation.core.model.Result<*> = {
            app.appContainer.syncTransactionsUseCase.invoke()
        }

        val now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        executeRollover(now, repository, syncUseCase, runAttemptCount)
    }

    companion object {
        const val WORK_NAME: String = "monthly_rollover_periodic"
        const val MAX_RETRIES: Int = 5

        suspend fun executeRollover(
            now: ZonedDateTime,
            repository: com.financeautomation.domain.repository.TransactionRepository,
            syncUseCase: suspend () -> com.financeautomation.core.model.Result<*>,
            runAttemptCount: Int = 0
        ): androidx.work.ListenableWorker.Result {
            // After MAX_RETRIES failed attempts this run ends; the periodic schedule tries again in its next window
            // (the carry is idempotent), so a permanent error cannot retry forever with backoff.
            val failed = {
                if (runAttemptCount < MAX_RETRIES) {
                    androidx.work.ListenableWorker.Result.retry()
                } else {
                    androidx.work.ListenableWorker.Result.failure()
                }
            }
            // Debts move only AFTER the new month starts (Asia/Kolkata). If the phone was off on the 1st,
            // they move at the next chance on any later day. Once this month's carry reached the sheet the plan is
            // empty, so a daily run does not queue or read anything more.
            val steps = repository.planMonthlyCarry(now)
            if (steps.isEmpty()) return androidx.work.ListenableWorker.Result.success()
            for ((targetTab, sourceTab) in steps) {
                val cfRes = repository.carryForward(targetTab = targetTab, sourceTab = sourceTab)
                if (cfRes is com.financeautomation.core.model.Result.Error) {
                    return failed()
                }
            }
            val syncRes = syncUseCase()
            if (syncRes is com.financeautomation.core.model.Result.Error) {
                return failed()
            }
            return androidx.work.ListenableWorker.Result.success()
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<MonthlyRolloverWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

