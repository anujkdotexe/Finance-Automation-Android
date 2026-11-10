package com.financeautomation.service.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financeautomation.FinanceApplication
import com.financeautomation.core.utils.DeviceSnapshot
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.LocalCrashRecorder
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Periodic health worker executing every 6 hours to record device status snapshots,
 * read process exit reasons, and flush pipeline trail events (Phase 9.3b).
 */
class CapturePipelineHealthWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val (snapshotA, snapshotB) = DeviceSnapshot.capture(applicationContext)
            PipelineTrail.record(PipelineStage.DEVICE, PipelineCode.SNAPSHOT, snapshotA, snapshotB)
            val app = applicationContext as? FinanceApplication
            val db = app?.appContainer?.awaitDatabase()
            val dao = db?.pipelineEventDao()
            DeviceSnapshot.updateCrashlyticsSummaries(applicationContext, snapshotA, dao)
            LocalCrashRecorder.readAndStoreExitReasons(applicationContext)
            PipelineTrail.triggerFlush()
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("CapturePipelineHealthWorker", "doWork: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "capture_pipeline_health_worker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .build()

            val request = PeriodicWorkRequestBuilder<CapturePipelineHealthWorker>(6, TimeUnit.HOURS)
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

