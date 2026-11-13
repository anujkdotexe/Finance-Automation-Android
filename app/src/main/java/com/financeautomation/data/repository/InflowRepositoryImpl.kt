package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.domain.model.VoidInflowRequest
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.repository.InflowRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class InflowRepositoryImpl(
    private val inflowDao: InflowDao? = null,
    private val inflowDaoProvider: (suspend () -> InflowDao)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val syncQueueDao: SyncQueueDao? = null,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    /** True when the income settled debts on the sheet; such an income cannot be voided. */
    private val settledDebtsCheck: (suspend (String) -> Boolean)? = null
) : InflowRepository {

    constructor(
        inflowDao: InflowDao,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        inflowDao = inflowDao,
        inflowDaoProvider = null,
        ioDispatcher = ioDispatcher
    )

    constructor(
        inflowDaoProvider: suspend () -> InflowDao,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        inflowDao = null,
        inflowDaoProvider = inflowDaoProvider,
        ioDispatcher = ioDispatcher
    )

    private suspend fun resolveDao(): InflowDao =
        inflowDao ?: inflowDaoProvider?.invoke()
            ?: throw IllegalStateException("InflowDao or provider must be supplied")

    override fun getInflowsByRange(start: Long, end: Long): Flow<List<Inflow>> = flow {
        val dao = resolveDao()
        emitAll(dao.getInflowsByRange(start, end).map { list -> list.map { it.toDomain() } })
    }

    override fun getAllInflows(): Flow<List<Inflow>> = flow {
        val dao = resolveDao()
        emitAll(dao.getAllInflows().map { list -> list.map { it.toDomain() } })
    }

    override suspend fun getInflowById(id: String): Inflow? = withContext(ioDispatcher) {
        resolveDao().getById(id)?.toDomain()
    }

    override suspend fun insertInflow(inflow: Inflow) = withContext(ioDispatcher) {
        resolveDao().insert(InflowEntity.fromDomain(inflow))
    }

    override suspend fun updateStatus(id: String, status: String) = withContext(ioDispatcher) {
        resolveDao().updateStatus(id, status)
    }

    override suspend fun voidInflow(id: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val dao = resolveDao()
            val existing = dao.getById(id)
                ?: return@withContext Result.Error(IllegalArgumentException("Inflow not found: $id"))
            if (existing.status == VOID_STATUS) return@withContext Result.Success(Unit)
            if (settledDebtsCheck?.invoke(id) == true) {
                return@withContext Result.Error(IllegalStateException(SettledInflowMarker.BLOCKED_MESSAGE))
            }

            val queue = syncQueueDao ?: syncQueueDaoProvider?.invoke()
                ?: throw IllegalStateException("SyncQueueDao or provider must be supplied")
            val request = VoidInflowRequest(inflowId = id, timestamp = existing.timestamp)
            transactionRunner {
                dao.updateStatus(id, VOID_STATUS)
                queue.insert(
                    SyncQueueEntity(
                        transactionId = id,
                        operationType = SyncQueueEntity.OP_VOID_INFLOW,
                        payloadJson = AccountingRequestJsonAdapter.voidInflowRequestToJson(request)
                    )
                )
            }
            onTriggerSync?.invoke()
            Result.Success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.Error(e)
        }
    }

    /** Set by the app container to start a sync after a void is queued. */
    var onTriggerSync: (() -> Unit)? = null

    private companion object {
        const val VOID_STATUS = "VOID"
    }
}

