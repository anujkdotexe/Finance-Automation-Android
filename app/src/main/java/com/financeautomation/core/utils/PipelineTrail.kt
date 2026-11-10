package com.financeautomation.core.utils

import com.financeautomation.data.local.dao.PipelineEventDao
import com.financeautomation.data.local.entity.PipelineEventEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class PipelineStage {
    LISTENER,
    ALERT,
    CAPTURE,
    WATCHER,
    PROMPT,
    SYNC,
    DEVICE
}

enum class PipelineCode {
    // LISTENER
    CONNECTED,
    DISCONNECTED,
    REBIND_REQUESTED,
    REBIND_OK,
    HEARTBEAT,

    // ALERT
    WHITELISTED_SEEN,
    DROPPED_BLACKLIST,
    DROPPED_NOT_WHITELISTED_COUNT,
    DROPPED_OTP,
    DROPPED_DUPLICATE,
    FAILURE_WORDING,
    PARSE_UNREADABLE,
    PARSE_OK_DEBIT,
    PARSE_OK_CREDIT,

    // CAPTURE
    CREATED_WAITING,
    AWAITING_SUCCESS,
    MERGED,
    FLAGGED_DUPLICATE,
    DISMISSED,
    DONE,

    // WATCHER
    USAGE_ACCESS_MISSING_SHOW_NOW,
    SOURCE_APP_FOREGROUND,
    TWO_POLLS_OK,
    SCREEN_LOCKED_WAIT,
    UNLOCKED_RESUME,
    BACKOFF,

    // PROMPT
    OVERLAY_PERMISSION_MISSING,
    OVERLAY_SHOWN,
    OVERLAY_ADD_FAILED,
    OVERLAY_DEFERRED_BUSY,
    HEADS_UP_POSTED,
    HEADS_UP_BLOCKED_NOTIFICATIONS_OFF,
    USER_SAVED,
    USER_DISMISSED,

    // SYNC
    QUEUED,
    SUCCESS,
    TRANSPORT_FAILURE,
    NEEDS_REVIEW,

    // DEVICE
    SNAPSHOT
}

/**
 * Capped pipeline event trail tracking system failure points (Phase 9.3b).
 * Strongly-typed enums prevent recording of raw alert text, PII, or financial amounts.
 * In-memory buffer flushes asynchronously in batches on Dispatchers.IO and prunes to 500 rows / 7 days.
 */
object PipelineTrail {

    private const val MAX_ROWS = 500
    private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    private const val BATCH_FLUSH_THRESHOLD = 5

    private val buffer = ConcurrentLinkedQueue<PipelineEventEntity>()
    private val isFlushing = AtomicBoolean(false)

    @Volatile
    var lastHeartbeatTime: Long = 0L
        private set

    val nonWhitelistedCount = AtomicLong(0L)

    private var daoProvider: (suspend () -> PipelineEventDao)? = null
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        daoSupplier: suspend () -> PipelineEventDao
    ) {
        this.ioDispatcher = dispatcher
        this.daoProvider = daoSupplier
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
        lastHeartbeatTime = System.currentTimeMillis()
        record(PipelineStage.LISTENER, PipelineCode.HEARTBEAT)
    }

    fun recordDroppedNotWhitelisted() {
        nonWhitelistedCount.incrementAndGet()
        record(PipelineStage.ALERT, PipelineCode.DROPPED_NOT_WHITELISTED_COUNT)
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

