package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.safeCall
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Application-scoped coordinator that debounces settings edits by ~3 seconds
 * and enqueues a durable OP_CONFIG_PUSH into sync_queue so that settings pushes
 * are offline-resilient, retried on transport failure up to the cap, and never lost.
 */
class ConfigSyncCoordinator(
    private val syncConfigUseCase: SyncConfigUseCase,
    private val syncQueueDao: SyncQueueDao? = null,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    val localGateway: ConfigSyncLocalGateway,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(
        syncConfigUseCase: SyncConfigUseCase,
        syncQueueDao: SyncQueueDao,
        localGateway: ConfigSyncLocalGateway,
        applicationScope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        syncConfigUseCase = syncConfigUseCase,
        syncQueueDao = syncQueueDao,
        syncQueueDaoProvider = null,
        localGateway = localGateway,
        applicationScope = applicationScope,
        ioDispatcher = ioDispatcher
    )

    private suspend fun resolveSyncQueueDao(): SyncQueueDao =
        syncQueueDao ?: syncQueueDaoProvider?.invoke()
            ?: throw IllegalStateException("SyncQueueDao or provider must be supplied")

    private val pushMutex = Mutex()
    private var pendingPushJob: Job? = null
    var onTriggerQueueSync: (() -> Unit)? = null

    /**
     * Schedules a debounced configuration push. Subsequent calls within [debounceMs]
     * cancel the earlier pending timer. When the timer fires, one durable OP_CONFIG_PUSH
     * item is enqueued into sync_queue (coalesced) and queue processing is triggered.
     */
    fun schedulePush(debounceMs: Long = 3000L) {
        synchronized(this) {
            pendingPushJob?.cancel()
            pendingPushJob = applicationScope.launch(ioDispatcher) {
                try {
                    delay(debounceMs)
                    enqueueDurablePush()
                    onTriggerQueueSync?.invoke()
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }
    }

    /**
     * Enqueues one OP_CONFIG_PUSH operation into sync_queue if not already pending.
     */
    suspend fun enqueueDurablePush(): Result<Unit> = withContext(ioDispatcher) {
        safeCall {
            val dao = resolveSyncQueueDao()
            val pendingCount = dao.countPendingOperations(SyncQueueEntity.OP_CONFIG_PUSH)
            if (pendingCount == 0) {
                dao.insert(
                    SyncQueueEntity(
                        transactionId = "config_push_${System.currentTimeMillis()}",
                        operationType = SyncQueueEntity.OP_CONFIG_PUSH,
                        payloadJson = "{}",
                        retryCount = 0,
                        createdAt = System.currentTimeMillis(),
                        status = SyncQueueEntity.STATUS_PENDING
                    )
                )
            }
        }
    }

    /**
     * Checks if any local rows are dirty on app startup; if so, enqueues an OP_CONFIG_PUSH
     * and triggers queue processing.
     */
    suspend fun checkAndEnqueueDirtyPushOnStartup(): Result<Unit> = withContext(ioDispatcher) {
        safeCall {
            val dirtySnapshot = localGateway.getDirtySnapshot()
            if (!dirtySnapshot.isEmpty) {
                enqueueDurablePush()
                onTriggerQueueSync?.invoke()
            }
        }
    }

    /**
     * Executes an immediate, serialized push to the remote Config tab.
     */
    suspend fun pushNow(): Result<Unit> = withContext(ioDispatcher) {
        safeCall {
            pushMutex.withLock {
                val res = syncConfigUseCase.push()
                if (res is Result.Error) {
                    com.financeautomation.core.utils.CrashlyticsManager.recordNonFatal(
                        throwable = res.exception,
                        locationTag = "ConfigSyncCoordinator.pushNow"
                    )
                    throw res.exception
                }
            }
        }
    }

    /**
     * Triggers a pull on application start or foreground, throttled to at most once every 10 minutes.
     */
    suspend fun pullOnForeground(force: Boolean = false): Result<Unit> = withContext(ioDispatcher) {
        safeCall {
            if (force || syncConfigUseCase.shouldPull()) {
                val res = syncConfigUseCase.pull()
                if (res is Result.Error) {
                    com.financeautomation.core.utils.CrashlyticsManager.recordNonFatal(
                        throwable = res.exception,
                        locationTag = "ConfigSyncCoordinator.pullOnForeground"
                    )
                    throw res.exception
                }
            }
        }
    }
}
