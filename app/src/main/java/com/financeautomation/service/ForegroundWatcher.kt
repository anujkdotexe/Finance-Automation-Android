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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.overlay.OverlayRequest
import com.financeautomation.presentation.ui.EntryActivity
import com.financeautomation.presentation.ui.ModalBottomSheetActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors foreground application state using UsageStatsManager.
 * Enforces the 2-consecutive-poll trigger rule before displaying transaction prompt.
 */
class ForegroundWatcher(
    private val context: Context,
    private val directPendingCaptureDao: PendingCaptureDao? = null,
    private val pendingCaptureDaoProvider: (suspend () -> PendingCaptureDao)? = null,
    private val overlayControllerProvider: (() -> OverlayController)? = null,
    private val onTriggerPrompt: ((PendingCaptureEntity) -> Unit)? = null
) {

    constructor(
        context: Context,
        pendingCaptureDao: PendingCaptureDao,
        onTriggerPrompt: ((PendingCaptureEntity) -> Unit)? = null
    ) : this(
        context = context,
        directPendingCaptureDao = pendingCaptureDao,
        pendingCaptureDaoProvider = null,
        overlayControllerProvider = null,
        onTriggerPrompt = onTriggerPrompt
    )

    constructor(
        context: Context,
        pendingCaptureDaoProvider: suspend () -> PendingCaptureDao,
        overlayControllerProvider: (() -> OverlayController)? = null,
        onTriggerPrompt: ((PendingCaptureEntity) -> Unit)? = null
    ) : this(
        context = context,
        directPendingCaptureDao = null,
        pendingCaptureDaoProvider = pendingCaptureDaoProvider,
        overlayControllerProvider = overlayControllerProvider,
        onTriggerPrompt = onTriggerPrompt
    )

    private suspend fun resolveDao(): PendingCaptureDao {
        return directPendingCaptureDao ?: pendingCaptureDaoProvider?.invoke()
            ?: throw IllegalStateException("PendingCaptureDao not available")
    }

    private val mutex = Mutex()
    private var watcherJob: Job? = null
    private val pollCounters = ConcurrentHashMap<String, Int>()
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                wakeChannel.trySend(Unit)
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
                val waiting = resolveDao().getWaitingCaptures()
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
                    val waitingList = resolveDao().getWaitingCaptures()
                    if (waitingList.isEmpty()) {
                        pollCounters.clear()
                        break
                    }

                    if (!isScreenInteractiveAndUnlocked()) {
                        withTimeoutOrNull(POLL_INTERVAL_MS) {
                            wakeChannel.receive()
                        }
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

                    withTimeoutOrNull(POLL_INTERVAL_MS) {
                        wakeChannel.receive()
                    }
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
        val dao = resolveDao()
        try {
            dao.updateState(capture.id, PendingCaptureEntity.STATE_SHOWN)
            if (onTriggerPrompt != null) {
                onTriggerPrompt.invoke(capture)
            } else {
                if (Settings.canDrawOverlays(context)) {
                    val controller = overlayControllerProvider?.invoke()
                    if (controller != null) {
                        controller.show(OverlayRequest.Capture(capture.id))
                    } else {
                        val intent = Intent(context, EntryActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(EntryActivity.EXTRA_CAPTURE_ID, capture.id)
                        }
                        context.startActivity(intent)
                    }
                } else {
                    // Spec 5.5 point 2: Heads-up notification fallback when overlay permission missing
                    postHeadsUpFallback(capture)
                }
            }
        } catch (e: Exception) {
            try {
                dao.updateState(capture.id, PendingCaptureEntity.STATE_WAITING)
            } catch (revertEx: Exception) {
                Diagnostics.record("ForegroundWatcher", "${revertEx.javaClass.simpleName}: revertStateToWaiting")
            }
            Diagnostics.record("ForegroundWatcher", "${e.javaClass.simpleName}: triggerPrompt failed")
        }
    }

    private fun postHeadsUpFallback(capture: PendingCaptureEntity) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NotificationListenerServiceImpl.CHANNEL_FINANCIAL_ALERTS,
                "Financial Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Expense review and categorization alerts"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationId = capture.id.hashCode()

        // Details button opens EntryActivity
        val detailsIntent = Intent(context, EntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EntryActivity.EXTRA_CAPTURE_ID, capture.id)
        }
        val pendingDetails = PendingIntent.getActivity(
            context,
            notificationId,
            detailsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Top two category buttons (spec 5.5 point 2)
        val cat1 = CategoryConstants.CATEGORY_FOOD_AND_DRINK
        val cat2 = CategoryConstants.CATEGORY_GROCERIES

        val intentCat1 = Intent(context, QuickActionReceiver::class.java).apply {
            action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE
            putExtra(QuickActionReceiver.EXTRA_CAPTURE_ID, capture.id)
            putExtra(QuickActionReceiver.EXTRA_CATEGORY, cat1)
            putExtra(QuickActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingCat1 = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 1,
            intentCat1,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentCat2 = Intent(context, QuickActionReceiver::class.java).apply {
            action = QuickActionReceiver.ACTION_QUICK_CATEGORIZE
            putExtra(QuickActionReceiver.EXTRA_CAPTURE_ID, capture.id)
            putExtra(QuickActionReceiver.EXTRA_CATEGORY, cat2)
            putExtra(QuickActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingCat2 = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 2,
            intentCat2,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val amountStr = capture.amount?.let { "₹" + String.format(java.util.Locale.US, "%.2f", it) } ?: "Pending review"
        val titleText = if (capture.payee.isNotBlank()) capture.payee else "Payment Detected"

        val builder = NotificationCompat.Builder(context, NotificationListenerServiceImpl.CHANNEL_FINANCIAL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(titleText)
            .setContentText(amountStr)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingDetails)
            .addAction(0, cat1, pendingCat1)
            .addAction(0, cat2, pendingCat2)
            .addAction(0, "Details", pendingDetails)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun registerScreenReceiver() {
        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(screenReceiver, filter)
                }
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
            } catch (e: Exception) {
                Diagnostics.record("ForegroundWatcher", "${e.javaClass.simpleName}: unregisterReceiver")
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
