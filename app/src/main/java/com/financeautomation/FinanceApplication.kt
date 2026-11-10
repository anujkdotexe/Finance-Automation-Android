package com.financeautomation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.financeautomation.di.AppContainer
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

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(applicationContext)

        // 1. Pre-warm database on background thread before main thread touches it
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appContainer.database.openHelper.writableDatabase
            } catch (_: Exception) {}
        }

        // 2. Enqueue durable push if dirty rows exist, and pull latest config on startup
        CoroutineScope(Dispatchers.IO).launch {
            appContainer.configSyncCoordinator.checkAndEnqueueDirtyPushOnStartup()
            appContainer.configSyncCoordinator.pullOnForeground(force = false)
        }

        // 3. Schedule daily periodic monthly rollover check and 6-hour listener health worker
        com.financeautomation.service.worker.MonthlyRolloverWorker.schedule(applicationContext)
        com.financeautomation.service.worker.NotificationListenerHealthWorker.schedule(applicationContext)

        // 4. Resume foreground watcher for any pending WAITING captures
        appContainer.foregroundWatcher.checkAndResume(CoroutineScope(Dispatchers.IO))

        // 5. Register process lifecycle observer via ActivityLifecycleCallbacks for foreground pull
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    // App transitioned to foreground
                    CoroutineScope(Dispatchers.IO).launch {
                        appContainer.configSyncCoordinator.pullOnForeground(force = false)
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
}
