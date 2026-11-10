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

        val now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        if (now.dayOfMonth >= 25) {
            val nextMonth = now.plusMonths(1)
            val formatter = DateTimeFormatter.ofPattern("MMMyy", Locale.US)
            val nextMonthTab = nextMonth.format(formatter)

            repository.carryForward(targetTab = nextMonthTab)
            app.appContainer.syncTransactionsUseCase.invoke()
        }

        androidx.work.ListenableWorker.Result.success()
    }

    companion object {
        const val WORK_NAME: String = "monthly_rollover_periodic"

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

