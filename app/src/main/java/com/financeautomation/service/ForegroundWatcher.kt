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
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.overlay.OverlayRequest
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
    private val directTransactionDao: TransactionDao? = null,
    private val transactionDaoProvider: (suspend () -> TransactionDao)? = null,
    private val categoryStatDaoProvider: (suspend () -> CategoryStatDao)? = null,
    private val predictCategoryUseCase: PredictCategoryUseCase? = null,
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
        directTransactionDao = null,
        transactionDaoProvider = null,
        categoryStatDaoProvider = null,
        predictCategoryUseCase = null,
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
        categoryStatDaoProvider = null,
        predictCategoryUseCase = null,
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
    private val failedRetryDelays = ConcurrentHashMap<String, Long>()
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                PipelineTrail.record(PipelineStage.WATCHER, PipelineCode.UNLOCKED_RESUME)
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
        val events = usageStats.queryEvents(now - 60 * 60_000L, now) ?: return false

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
                expireStaleCaptures(resolveDao())
                while (isActive) {
                    val waitingList = resolveDao().getWaitingCaptures()
                    if (waitingList.isEmpty()) {
                        pollCounters.clear()
                        break
                    }

                    if (!isScreenInteractiveAndUnlocked()) {
                        PipelineTrail.record(PipelineStage.WATCHER, PipelineCode.SCREEN_LOCKED_WAIT)
                        withTimeoutOrNull(POLL_INTERVAL_MS) {
                            wakeChannel.receive()
                        }
                        continue
                    }

                    val isOverlayOpen = overlayControllerProvider?.invoke()?.isShowing == true
                    if (isOverlayOpen) {
                        withTimeoutOrNull(POLL_INTERVAL_MS) {
                            wakeChannel.receive()
                        }
                        continue
                    }

                    val hasAccess = hasUsageAccess()
                    val now = System.currentTimeMillis()

                    for (capture in waitingList) {
                        val nextAllowedTime = failedRetryDelays[capture.id] ?: 0L
                        if (now < nextAllowedTime) {
                            continue
                        }

                        if (overlayControllerProvider?.invoke()?.isShowing == true) {
                            break
                        }

                        if (!hasAccess) {
                            // Spec 4.3 Fallback: trigger immediately if usage access is absent
                            PipelineTrail.record(PipelineStage.WATCHER, PipelineCode.USAGE_ACCESS_MISSING_SHOW_NOW)
                            val shown = triggerPrompt(capture)
                            if (shown) {
                                pollCounters.remove(capture.id)
                                failedRetryDelays.remove(capture.id)
                                break
                            } else {
                                failedRetryDelays[capture.id] = now + FAILURE_BACKOFF_MS
                                PipelineTrail.record(PipelineStage.WATCHER, PipelineCode.BACKOFF)
                                Diagnostics.record("ForegroundWatcher", "triggerPrompt deferred: backoff capture ${capture.id}")
                            }
                        } else {
                            val inForeground = isInForeground(capture.sourcePackage)
                            if (!inForeground) {
                                val currentCount = (pollCounters[capture.id] ?: 0) + 1
                                pollCounters[capture.id] = currentCount
                                if (currentCount >= REQUIRED_CONSECUTIVE_POLLS) {
                                    PipelineTrail.record(PipelineStage.WATCHER, PipelineCode.TWO_POLLS_OK)
                                    val shown = triggerPrompt(capture)
                                    if (shown) {
                                        pollCounters.remove(capture.id)
                                        failedRetryDelays.remove(capture.id)
                                        break
                                    } else {
                                        failedRetryDelays[capture.id] = now + FAILURE_BACKOFF_MS
                                        PipelineTrail.record(PipelineStage.WATCHER, PipelineCode.BACKOFF)
                                        Diagnostics.record("ForegroundWatcher", "triggerPrompt deferred: backoff capture ${capture.id}")
                                    }
                                }
                            } else {
                                // Reset poll count if the app re-entered foreground
                                pollCounters[capture.id] = 0
                                PipelineTrail.record(PipelineStage.WATCHER, PipelineCode.SOURCE_APP_FOREGROUND)
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

    private suspend fun triggerPrompt(capture: PendingCaptureEntity): Boolean {
        val dao = resolveDao()
        try {
            dao.updateState(capture.id, PendingCaptureEntity.STATE_SHOWN)
            if (onTriggerPrompt != null) {
                onTriggerPrompt.invoke(capture)
                return true
            } else {
                val controller = if (Settings.canDrawOverlays(context)) overlayControllerProvider?.invoke() else null
                if (controller != null) {
                    val shown = controller.show(OverlayRequest.Capture(capture.id))
                    if (!shown) {
                        dao.updateState(capture.id, PendingCaptureEntity.STATE_WAITING)
                        return false
                    }
                    return true
                } else {
                    // Spec 5.5 point 2: Heads-up notification fallback when overlay permission or controller is absent
                    postHeadsUpFallback(capture)
                    return true
                }
            }
        } catch (e: Exception) {
            try {
                dao.updateState(capture.id, PendingCaptureEntity.STATE_WAITING)
            } catch (revertEx: Exception) {
                Diagnostics.record("ForegroundWatcher", "${revertEx.javaClass.simpleName}: revertStateToWaiting")
            }
            if (e is CancellationException) throw e
            Diagnostics.record("ForegroundWatcher", "${e.javaClass.simpleName}: triggerPrompt failed")
            return false
        }
    }

    private suspend fun expireStaleCaptures(pDao: PendingCaptureDao) {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val expired = pDao.getExpiredWaitingCaptures(cutoff)
        if (expired.isEmpty()) return
        val tDao = directTransactionDao ?: transactionDaoProvider?.invoke()
        for (cap in expired) {
            if (tDao != null) {
                val txn = TransactionEntity(
                    id = "txn_${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}",
                    timestamp = cap.timestamp,
                    dateSerial = DateSerialUtils.toDateSerial(cap.timestamp),
                    timeSerial = DateSerialUtils.toTimeSerial(cap.timestamp),
                    merchant = cap.payee.ifBlank { "Expense" },
                    category = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
                    account = cap.inferredAccount?.takeIf { it.isNotBlank() } ?: "Other",
                    totalAmount = cap.amount ?: 0.0,
                    myShare = cap.amount ?: 0.0,
                    owedToMe = 0.0,
                    status = "Personal",
                    companions = "Personal",
                    reviewState = ReviewState.NEEDS_REVIEW.name,
                    syncState = SyncState.PENDING.name,
                    sourcePackage = cap.sourcePackage
                )
                tDao.insert(txn)
            }
            pDao.updateState(cap.id, PendingCaptureEntity.STATE_DONE)
        }
    }

    private suspend fun postHeadsUpFallback(capture: PendingCaptureEntity) {
        ForegroundHeadsUpHelper.postHeadsUpNotification(
            context = context,
            capture = capture,
            categoryStatDaoProvider = categoryStatDaoProvider,
            predictCategoryUseCase = predictCategoryUseCase
        )
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
        const val FAILURE_BACKOFF_MS: Long = 5000L
    }
}
