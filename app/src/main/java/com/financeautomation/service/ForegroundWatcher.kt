package com.financeautomation.service

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.presentation.ui.ModalBottomSheetActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors foreground application state using UsageStatsManager.
 * Enforces the 2-consecutive-poll trigger rule before displaying transaction prompt.
 */
class ForegroundWatcher(
    private val context: Context,
    private val pendingCaptureDao: PendingCaptureDao,
    private val onTriggerPrompt: ((PendingCaptureEntity) -> Unit)? = null
) {

    private val mutex = Mutex()
    private var watcherJob: Job? = null
    private val pollCounters = ConcurrentHashMap<String, Int>()

    @Volatile
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                // Wake up loop check
            }
        }
    }

    /**
     * Checks if the app has been granted PACKAGE_USAGE_STATS permission.
     */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Inspects UsageEvents to determine if the target application is currently in the foreground.
     */
    fun isInForeground(targetPackage: String): Boolean {
        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()
        val events = usageStats.queryEvents(now - 10 * 60_000L, now) ?: return false

        var lastResumeTime = 0L
        var lastPauseTime = 0L
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName == targetPackage) {
                val isResume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                val isPause = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_PAUSED
                } else {
                    @Suppress("DEPRECATION")
                    event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
                }

                if (isResume) {
                    lastResumeTime = event.timeStamp
                } else if (isPause) {
                    lastPauseTime = event.timeStamp
                }
            }
        }

        if (lastResumeTime == 0L && lastPauseTime == 0L) {
            return false
        }
        return lastResumeTime > lastPauseTime
    }

    /**
     * Returns true when the device screen is on and the keyguard is unlocked.
     */
    fun isScreenInteractiveAndUnlocked(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isInteractive = powerManager?.isInteractive ?: true
        val isLocked = keyguardManager?.isKeyguardLocked ?: false
        return isInteractive && !isLocked
    }

    /**
     * Resumes monitoring for all pending WAITING captures.
     * Called on process start, listener connection, or boot.
     */
    fun checkAndResume(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val waiting = pendingCaptureDao.getWaitingCaptures()
                if (waiting.isNotEmpty() && watcherJob?.isActive != true) {
                    startWatcherLoop(scope)
                }
            }
        }
    }

    /**
     * Notifies watcher that a new capture has transitioned into WAITING state.
     */
    fun notifyCaptureWaiting(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (watcherJob?.isActive != true) {
                    startWatcherLoop(scope)
                }
            }
        }
    }

    private fun startWatcherLoop(scope: CoroutineScope) {
        registerScreenReceiver()
        watcherJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val waitingList = pendingCaptureDao.getWaitingCaptures()
                    if (waitingList.isEmpty()) {
                        pollCounters.clear()
                        break
                    }

                    if (!isScreenInteractiveAndUnlocked()) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    val hasAccess = hasUsageAccess()

                    for (capture in waitingList) {
                        if (!hasAccess) {
                            // Spec 4.3 Fallback: trigger immediately if usage access is absent
                            triggerPrompt(capture)
                            pollCounters.remove(capture.id)
                        } else {
                            val inForeground = isInForeground(capture.sourcePackage)
                            if (!inForeground) {
                                val currentCount = (pollCounters[capture.id] ?: 0) + 1
                                pollCounters[capture.id] = currentCount
                                if (currentCount >= REQUIRED_CONSECUTIVE_POLLS) {
                                    triggerPrompt(capture)
                                    pollCounters.remove(capture.id)
                                }
                            } else {
                                // Reset poll count if the app re-entered foreground
                                pollCounters[capture.id] = 0
                            }
                        }
                    }

                    delay(POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.record("ForegroundWatcher", "${e.javaClass.simpleName}: loop error")
            } finally {
                unregisterScreenReceiver()
            }
        }
    }

    private suspend fun triggerPrompt(capture: PendingCaptureEntity) {
        try {
            pendingCaptureDao.updateState(capture.id, PendingCaptureEntity.STATE_SHOWN)
            if (onTriggerPrompt != null) {
                onTriggerPrompt.invoke(capture)
            } else {
                // Default launcher: start ModalBottomSheetActivity with capture ID
                val intent = Intent(context, ModalBottomSheetActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("capture_id", capture.id)
                    putExtra(ModalBottomSheetActivity.EXTRA_TRANSACTION_ID, capture.id)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Diagnostics.record("ForegroundWatcher", "${e.javaClass.simpleName}: triggerPrompt failed")
        }
    }

    private fun registerScreenReceiver() {
        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                context.registerReceiver(screenReceiver, filter)
                screenReceiverRegistered = true
            } catch (e: Exception) {
                Diagnostics.record("ForegroundWatcher", "${e.javaClass.simpleName}: registerReceiver")
            }
        }
    }

    private fun unregisterScreenReceiver() {
        if (screenReceiverRegistered) {
            try {
                context.unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            } finally {
                screenReceiverRegistered = false
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MS: Long = 700L
        const val REQUIRED_CONSECUTIVE_POLLS: Int = 2
    }
}
