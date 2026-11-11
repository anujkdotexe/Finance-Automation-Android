package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.CompanionDao
import com.financeautomation.data.local.entity.CompanionEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.repository.PeopleRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Implementation of [PeopleRepository] backed by Room SQLite [CompanionDao]
 * or a suspend DAO provider for main-thread safety.
 */
class PeopleRepositoryImpl(
    private val companionDao: CompanionDao? = null,
    private val companionDaoProvider: (suspend () -> CompanionDao)? = null,
    private val configSyncCoordinatorProvider: (() -> ConfigSyncCoordinator)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PeopleRepository {

    constructor(
        companionDao: CompanionDao,
        configSyncCoordinator: ConfigSyncCoordinator? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        companionDao = companionDao,
        companionDaoProvider = null,
        configSyncCoordinatorProvider = configSyncCoordinator?.let { { it } },
        ioDispatcher = ioDispatcher
    )

    private val configSyncCoordinator: ConfigSyncCoordinator?
        get() = configSyncCoordinatorProvider?.invoke()

    private suspend fun resolveDao(): CompanionDao =
        companionDao ?: companionDaoProvider?.invoke()
            ?: throw IllegalStateException("CompanionDao or provider must be supplied")

    override fun getCompanions(): Flow<List<Companion>> = flow {
        val dao = resolveDao()
        emitAll(dao.getCompanionsFlow().map { entities ->
            entities.map { it.toDomain() }
        })
    }.flowOn(ioDispatcher)

    override suspend fun getAllCompanions(): List<Companion> = withContext(ioDispatcher) {
        resolveDao().getCompanionsFlow().first().map { it.toDomain() }
    }

    override suspend fun getCompanionById(id: String): Companion? = withContext(ioDispatcher) {
        resolveDao().getById(id)?.toDomain()
    }

    override suspend fun getCompanionByName(name: String): Companion? = withContext(ioDispatcher) {
        resolveDao().getByName(name)?.toDomain()
    }

    override suspend fun insertCompanion(companion: Companion): Unit = withContext(ioDispatcher) {
        resolveDao().insert(CompanionEntity.fromDomain(companion))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun updateCompanion(companion: Companion): Unit = withContext(ioDispatcher) {
        resolveDao().update(CompanionEntity.fromDomain(companion))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun updateCompanionName(id: String, newName: String): Unit = withContext(ioDispatcher) {
        resolveDao().updateName(id, newName.trim())
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteCompanion(companion: Companion): Unit = withContext(ioDispatcher) {
        resolveDao().delete(CompanionEntity.fromDomain(companion))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteCompanionById(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().deleteById(id)
        configSyncCoordinator?.schedulePush()
    }
}
