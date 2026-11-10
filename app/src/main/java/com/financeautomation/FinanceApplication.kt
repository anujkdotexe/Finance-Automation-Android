package com.financeautomation

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import com.financeautomation.core.utils.CrashlyticsManager
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
        LocalCrashRecorder.install(this, BuildConfig.VERSION_NAME)
        CrashlyticsManager.initAtAppStart(this)
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        appContainer = AppContainer(applicationContext)

        PipelineTrail.initialize(applicationContext, Dispatchers.IO) {
            appContainer.awaitDatabase().pipelineEventDao()
        }
        PipelineTrail.record(PipelineStage.DEVICE, PipelineCode.SNAPSHOT)

        // 1. Pre-warm database on background thread and resume foreground watcher
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = appContainer.awaitDatabase()
                db.openHelper.writableDatabase
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

        // 2c. Refresh launcher widgets on background dispatcher
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QuickLogWidgetProvider.updateAllWidgets(applicationContext)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("FinanceApplication", "${e.javaClass.simpleName}: updateAllWidgets startup")
            }
        }

        // 3. Schedule daily periodic monthly rollover check and 6-hour listener health worker
        com.financeautomation.service.worker.MonthlyRolloverWorker.schedule(applicationContext)
        com.financeautomation.service.worker.NotificationListenerHealthWorker.schedule(applicationContext)
        com.financeautomation.service.worker.CapturePipelineHealthWorker.schedule(applicationContext)

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
