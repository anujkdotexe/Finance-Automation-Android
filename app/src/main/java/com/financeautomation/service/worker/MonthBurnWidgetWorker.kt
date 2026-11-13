package com.financeautomation.service.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financeautomation.service.MonthBurnWidgetProvider
import java.util.concurrent.TimeUnit

/**
 * Refreshes the month burn widget every 30 minutes. The figures come from the local database, so the job has no
 * network constraint and also runs while the phone is offline.
 */
class MonthBurnWidgetWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            MonthBurnWidgetProvider.refresh(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME: String = "month_burn_widget_refresh"
        const val REPEAT_MINUTES: Long = 30L

        fun schedule(context: Context) {
            // No constraints: nothing here needs the network
            val request = PeriodicWorkRequestBuilder<MonthBurnWidgetWorker>(REPEAT_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
