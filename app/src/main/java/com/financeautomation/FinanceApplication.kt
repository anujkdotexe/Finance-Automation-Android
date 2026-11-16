package com.financeautomation

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import com.financeautomation.core.utils.CrashlyticsManager
import com.financeautomation.core.utils.DeviceSnapshot
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.LocalCrashRecorder
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.di.AppContainer
import com.financeautomation.service.QuickLogWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Custom Application class maintaining application-level state, lifecycle observation,
 * background database pre-warming, and configuration sync scheduling.
 */
class FinanceApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    private var startedActivityCount = 0
    private var lastNightMode: Int = 0

    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
        LocalCrashRecorder.install(this, BuildConfig.VERSION_NAME)
        CrashlyticsManager.initAtAppStart(this)
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        appContainer = AppContainer(applicationContext)
        // Periodic sync must not depend on a reboot or an app update; KEEP makes this a no-op when already scheduled
        try {
            com.financeautomation.service.worker.SyncTransactionsWorker.enqueuePeriodic(applicationContext)
        } catch (e: Exception) {
            Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: schedule periodic sync")
        }

        PipelineTrail.initialize(applicationContext, Dispatchers.IO) {
            appContainer.awaitDatabase().pipelineEventDao()
        }
        // 1. Pre-warm database on background thread, record device snapshot, and resume foreground watcher
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (snapshotA, snapshotB) = DeviceSnapshot.capture(applicationContext)
                PipelineTrail.record(PipelineStage.DEVICE, PipelineCode.SNAPSHOT, snapshotA, snapshotB)
                val db = appContainer.awaitDatabase()
                try {
                    com.financeautomation.service.CaptureClaim.recoverClaimed(db.pendingCaptureDao()) { id ->
                        db.transactionDao().getById(id) != null
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: recoverClaimed")
                }
                try {
                    // Idempotent: seeds categories and accounts only when none exist (for example after an interrupted wipe)
                    com.financeautomation.data.local.FinanceDatabase.prepopulateDefaults(db)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: prepopulateDefaults")
                }
                try {
                    appContainer.accountSwitchGuard.adoptSignedInAccount()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: adoptSignedInAccount")
                }
                db.openHelper.writableDatabase
                DeviceSnapshot.updateCrashlyticsSummaries(applicationContext, snapshotA, db.pipelineEventDao())
                // Reset incomplete SHOWN captures to WAITING
                try {
                    db.pendingCaptureDao().resetShownToWaiting()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: resetShownToWaiting")
                }
                appContainer.foregroundWatcher.checkAndResume(this)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: db pre-warm error")
            }
        }

        // 2. Enqueue durable push if dirty rows exist, and pull latest config on startup
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appContainer.configSyncCoordinator.checkAndEnqueueDirtyPushOnStartup()
                appContainer.configSyncCoordinator.pullOnForeground(force = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: startup config sync")
            }
        }

        // 2b. Rebuild people ranking from ledger snapshot in background (Phase 5.7)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appContainer.peopleListRebuilder.refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: startup people refresh")
            }
        }

        // 2b2. Remove stale CSV exports left in the cache
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.financeautomation.data.export.CsvExportWriter.purgeStaleExports(applicationContext.cacheDir)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        // 2c. Refresh launcher widgets on background dispatcher
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QuickLogWidgetProvider.updateAllWidgets(applicationContext)
                com.financeautomation.service.MonthBurnWidgetProvider.ensureScheduled(applicationContext)
                com.financeautomation.service.MonthBurnWidgetProvider.refresh(applicationContext)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: updateAllWidgets startup")
            }
        }

        // 3. Schedule daily periodic monthly rollover check and 6-hour listener health worker
        com.financeautomation.service.worker.MonthlyRolloverWorker.schedule(applicationContext)
        com.financeautomation.service.worker.NotificationListenerHealthWorker.schedule(applicationContext)
        com.financeautomation.service.worker.CapturePipelineHealthWorker.schedule(applicationContext)

        // 3a. Schedule the daily subscription reminder; a changed reminder time reschedules it
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appContainer.preferencesRepository.getSubscriptionReminderTime()
                    .distinctUntilChanged()
                    .collect { time ->
                        com.financeautomation.service.worker.SubscriptionReminderWorker.schedule(applicationContext, time)
                    }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: subscription reminder schedule")
            }
        }

        // 3b. Verify notification listener health and update persistent alert if access missing (Phase 7.4)
        com.financeautomation.service.NotificationAccessAlertManager.checkAndSyncAlert(applicationContext)

        // 4. Register process lifecycle observer via ActivityLifecycleCallbacks for foreground pull
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    // App transitioned to foreground
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            appContainer.configSyncCoordinator.pullOnForeground(force = false)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: pullOnForeground")
                        }
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = maxOf(0, startedActivityCount - 1)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (currentNightMode != lastNightMode) {
            lastNightMode = currentNightMode
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    QuickLogWidgetProvider.updateAllWidgets(applicationContext)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: updateAllWidgets theme change")
                }
            }
        }
    }
}
