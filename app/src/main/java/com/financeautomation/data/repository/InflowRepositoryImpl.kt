package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.InflowDao
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
}

