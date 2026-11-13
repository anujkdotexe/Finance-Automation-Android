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
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that triggers automatic monthly rollover.
 * Port of spec 3.5 step 6: On or after day 25 of each month, provisions next month's tab
 * and carries forward unsettled debts and payables idempotently.
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
        executeRollover(now, repository, syncUseCase)
    }

    companion object {
        const val WORK_NAME: String = "monthly_rollover_periodic"

        suspend fun executeRollover(
            now: ZonedDateTime,
            repository: com.financeautomation.domain.repository.TransactionRepository,
            syncUseCase: suspend () -> com.financeautomation.core.model.Result<*>
        ): androidx.work.ListenableWorker.Result {
            val formatter = DateTimeFormatter.ofPattern("MMMyy", Locale.US)
            // Days 1 to 3: catch debts logged after the last run of the month before. Carry is idempotent per row.
            val (targetTab, sourceTab) = when {
                now.dayOfMonth >= 25 -> now.plusMonths(1).format(formatter) to null
                now.dayOfMonth <= 3 -> now.format(formatter) to now.minusMonths(1).format(formatter)
                else -> null to null
            }
            if (targetTab != null) {
                val nextMonthTab = targetTab

                val cfRes = repository.carryForward(targetTab = nextMonthTab, sourceTab = sourceTab)
                if (cfRes is com.financeautomation.core.model.Result.Error) {
                    return androidx.work.ListenableWorker.Result.retry()
                }
                val syncRes = syncUseCase()
                if (syncRes is com.financeautomation.core.model.Result.Error) {
                    return androidx.work.ListenableWorker.Result.retry()
                }
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

