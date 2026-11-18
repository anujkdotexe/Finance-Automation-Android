package com.financeautomation.core.utils

import android.content.Context
import com.financeautomation.data.local.dao.PipelineEventDao
import com.financeautomation.data.local.entity.PipelineEventEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class PipelineStage {
    LISTENER,
    ALERT,
    WATCHER,
    PROMPT,
    CAPTURE,
    SYNC,
    DEVICE
}

enum class PipelineCode {
    CONNECTED,
    DISCONNECTED,
    REBIND_REQUESTED,
    REBIND_OK,
    HEARTBEAT,
    WHITELISTED_SEEN,
    DROPPED_BLACKLIST,
    DROPPED_OTP,
    DROPPED_DUPLICATE,
    DROPPED_NOT_WHITELISTED_COUNT,
    FAILURE_WORDING,
    PARSE_OK_DEBIT,
    PARSE_OK_CREDIT,
    PARSE_UNREADABLE,
    SCREEN_LOCKED_WAIT,
    UNLOCKED_RESUME,
    SOURCE_APP_FOREGROUND,
    TWO_POLLS_OK,
    BACKOFF,
    USAGE_ACCESS_MISSING_SHOW_NOW,
    OVERLAY_SHOWN,
    OVERLAY_ADD_FAILED,
    OVERLAY_PERMISSION_MISSING,
    OVERLAY_DEFERRED_BUSY,
    HEADS_UP_POSTED,
    HEADS_UP_BLOCKED_NOTIFICATIONS_OFF,
    USER_SAVED,
    USER_DISMISSED,
    CREATED_WAITING,
    AWAITING_SUCCESS,
    DISMISSED,
    DONE,
    MERGED,
    FLAGGED_DUPLICATE,
    QUEUED,
    SUCCESS,
    TRANSPORT_FAILURE,
    NEEDS_REVIEW,
    SNAPSHOT,
    DROPPED_PROMO,
    DROPPED_NO_SUCCESS_WORDING,
    DROPPED_UNKNOWN_DIRECTION,
    DROPPED_PAYMENT_REQUEST,
    DROPPED_MANDATE_NOTICE,
    DROPPED_BALANCE_HOLD
}

object PipelineTrail {

    private const val MAX_ROWS = 500
    private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    private const val BATCH_FLUSH_THRESHOLD = 5
    private const val PREFS_NAME = "pipeline_heartbeat_prefs"
    private const val KEY_HEARTBEAT_TIME = "last_heartbeat_time"
    private const val KEY_DROPPED_COUNT = "dropped_not_whitelisted_count"
    private const val KEY_DAY = "current_day_key"
    private const val PERSIST_INTERVAL_MS = 60_000L

    private val buffer = ConcurrentLinkedQueue<PipelineEventEntity>()
    private val isFlushing = AtomicBoolean(false)

    private val heartbeatTime = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)
    private val lastPersistedAt = AtomicLong(0L)
    private val currentDayKey = AtomicReference<String>("")

    internal var todayProvider: () -> String = { LocalDate.now().toString() }

    private var appContext: Context? = null
    private var daoProvider: (suspend () -> PipelineEventDao)? = null
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        daoSupplier: suspend () -> PipelineEventDao
    ) {
        this.appContext = context.applicationContext
        this.ioDispatcher = dispatcher
        this.daoProvider = daoSupplier

        try {
            val prefs = this.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs != null) {
                initializeWithPrefs(prefs, getTodayKey())
            }
        } catch (e: Exception) {
            Diagnostics.record("PipelineTrail", "initialize prefs: ${e.javaClass.simpleName}")
        }
    }

    internal fun initializeWithPrefs(
        prefs: android.content.SharedPreferences,
        today: String = getTodayKey()
    ) {
        heartbeatTime.set(prefs.getLong(KEY_HEARTBEAT_TIME, 0L))
        val savedDay = prefs.getString(KEY_DAY, "") ?: ""
        currentDayKey.set(today)
        if (savedDay == today) {
            droppedCount.set(prefs.getLong(KEY_DROPPED_COUNT, 0L))
        } else {
            droppedCount.set(0L)
        }
    }

    fun record(
        stage: PipelineStage,
        code: PipelineCode,
        a: Long? = null,
        b: Long? = null
    ) {
        val event = PipelineEventEntity(
            time = System.currentTimeMillis(),
            stage = stage.name,
            code = code.name,
            a = a,
            b = b
        )
        buffer.offer(event)

        if (buffer.size >= BATCH_FLUSH_THRESHOLD) {
            triggerFlush()
        }
    }

    fun recordHeartbeat() {
        heartbeatTime.set(System.currentTimeMillis())
        maybePersistHeartbeatAsync()
    }

    fun recordDroppedNotWhitelisted() {
        ensureDayRollover()
        droppedCount.incrementAndGet()
        maybePersistHeartbeatAsync()
    }

    fun getLastHeartbeatTime(): Long = heartbeatTime.get()

    fun getTodayDroppedNonWhitelistedCount(): Long {
        ensureDayRollover()
        return droppedCount.get()
    }

    private fun getTodayKey(): String = todayProvider()

    /** Snapshot of events not yet flushed to the database. For tests. */
    internal fun bufferedEventsForTesting(): List<PipelineEventEntity> = buffer.toList()

    internal fun resetForTesting() {
        buffer.clear()
        heartbeatTime.set(0L)
        droppedCount.set(0L)
        lastPersistedAt.set(0L)
        currentDayKey.set("")
        todayProvider = { LocalDate.now().toString() }
    }

    private fun ensureDayRollover(today: String = getTodayKey()) {
        val current = currentDayKey.get()
        if (current != today) {
            if (currentDayKey.compareAndSet(current, today)) {
                droppedCount.set(0L)
            }
        }
    }

    private fun maybePersistHeartbeatAsync() {
        val now = System.currentTimeMillis()
        val lastPersist = lastPersistedAt.get()
        if (now - lastPersist < PERSIST_INTERVAL_MS) return
        if (!lastPersistedAt.compareAndSet(lastPersist, now)) return

        scope.launch(ioDispatcher) {
            try {
                val ctx = appContext ?: return@launch
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong(KEY_HEARTBEAT_TIME, heartbeatTime.get())
                    .putLong(KEY_DROPPED_COUNT, droppedCount.get())
                    .putString(KEY_DAY, currentDayKey.get())
                    .apply()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("PipelineTrail", "persistHeartbeat: ${e.javaClass.simpleName}")
            }
        }
    }

    fun triggerFlush() {
        if (!isFlushing.compareAndSet(false, true)) return

        scope.launch(ioDispatcher) {
            try {
                val dao = daoProvider?.invoke() ?: return@launch
                val eventsToInsert = mutableListOf<PipelineEventEntity>()
                while (true) {
                    val item = buffer.poll() ?: break
                    eventsToInsert.add(item)
                }

                if (eventsToInsert.isNotEmpty()) {
                    dao.insertBatch(eventsToInsert)
                    val cutoff = System.currentTimeMillis() - SEVEN_DAYS_MS
                    dao.pruneOlderThan(cutoff)
                    dao.pruneToMaxCount(MAX_ROWS)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("PipelineTrail", "flush error: ${e.javaClass.simpleName}")
            } finally {
                isFlushing.set(false)
            }
        }
    }
}