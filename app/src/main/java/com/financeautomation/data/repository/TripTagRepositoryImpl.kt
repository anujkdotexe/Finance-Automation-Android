package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.TripTagRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Implementation of [TripTagRepository] backed by Room SQLite [TripTagDao]
 * or a suspend DAO provider for main-thread safety.
 */
class TripTagRepositoryImpl(
    private val tripTagDao: TripTagDao? = null,
    private val tripTagDaoProvider: (suspend () -> TripTagDao)? = null,
    private val configSyncCoordinator: ConfigSyncCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TripTagRepository {

    constructor(
        tripTagDao: TripTagDao,
        configSyncCoordinator: ConfigSyncCoordinator? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        tripTagDao = tripTagDao,
        tripTagDaoProvider = null,
        configSyncCoordinator = configSyncCoordinator,
        ioDispatcher = ioDispatcher
    )

    private suspend fun resolveDao(): TripTagDao =
        tripTagDao ?: tripTagDaoProvider?.invoke()
            ?: throw IllegalStateException("TripTagDao or provider must be supplied")

    override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> = flow {
        val dao = resolveDao()
        val baseFlow = if (includeArchived) {
            dao.getTripTagsFlow()
        } else {
            dao.getActiveTripTagsFlow()
        }
        emitAll(baseFlow.map { entities -> entities.map { it.toDomain() } })
    }.flowOn(ioDispatcher)

    override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> = withContext(ioDispatcher) {
        val dao = resolveDao()
        val entities = if (includeArchived) {
            dao.getAllTripTags()
        } else {
            dao.getActiveTripTags()
        }
        entities.map { it.toDomain() }
    }

    override suspend fun getActiveTripTags(): List<TripTag> = withContext(ioDispatcher) {
        resolveDao().getActiveTripTags().map { it.toDomain() }
    }

    override fun getActiveTag(): Flow<TripTag?> = flow {
        val dao = resolveDao()
        emitAll(dao.getActiveTagFlow().map { it?.toDomain() })
    }.flowOn(ioDispatcher)

    override suspend fun getActiveTagSync(): TripTag? = withContext(ioDispatcher) {
        resolveDao().getActiveTag()?.toDomain()
    }

    override suspend fun getTagById(id: String): TripTag? = withContext(ioDispatcher) {
        resolveDao().getById(id)?.toDomain()
    }

    override suspend fun getTagByName(name: String): TripTag? = withContext(ioDispatcher) {
        resolveDao().getByName(name)?.toDomain()
    }

    override suspend fun insertTripTag(name: String): TripTag = withContext(ioDispatcher) {
        val dao = resolveDao()
        val trimmed = name.trim()
        val existing = dao.getByName(trimmed)
        if (existing != null) {
            return@withContext existing.toDomain()
        }
        val id = "tag_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        val entity = TripTagEntity(
            id = id,
            name = trimmed,
            isActive = false,
            isArchived = false,
            createdAt = now,
            updatedAt = now,
            dirty = 1
        )
        dao.insert(entity)
        configSyncCoordinator?.schedulePush()
        entity.toDomain()
    }

    override suspend fun updateTripTag(tripTag: TripTag): Unit = withContext(ioDispatcher) {
        resolveDao().update(TripTagEntity.fromDomain(tripTag))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun setActiveTag(id: String?): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        dao.clearActiveFlags()
        if (id != null) {
            dao.setActiveFlag(id)
        }
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun archiveTripTag(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().archiveTag(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun unarchiveTripTag(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().unarchiveTag(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteTripTag(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().deleteById(id)
        configSyncCoordinator?.schedulePush()
    }
}
