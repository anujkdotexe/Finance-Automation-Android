package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.TripTagRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Implementation of [TripTagRepository] backed by Room SQLite [TripTagDao].
 */
class TripTagRepositoryImpl(
    private val tripTagDao: TripTagDao,
    private val configSyncCoordinator: ConfigSyncCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TripTagRepository {

    override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> {
        val flow = if (includeArchived) {
            tripTagDao.getTripTagsFlow()
        } else {
            tripTagDao.getActiveTripTagsFlow()
        }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> = withContext(ioDispatcher) {
        val entities = if (includeArchived) {
            tripTagDao.getAllTripTags()
        } else {
            tripTagDao.getActiveTripTags()
        }
        entities.map { it.toDomain() }
    }

    override suspend fun getActiveTripTags(): List<TripTag> = withContext(ioDispatcher) {
        tripTagDao.getActiveTripTags().map { it.toDomain() }
    }

    override fun getActiveTag(): Flow<TripTag?> {
        return tripTagDao.getActiveTagFlow().map { it?.toDomain() }
    }

    override suspend fun getActiveTagSync(): TripTag? = withContext(ioDispatcher) {
        tripTagDao.getActiveTag()?.toDomain()
    }

    override suspend fun getTagById(id: String): TripTag? = withContext(ioDispatcher) {
        tripTagDao.getById(id)?.toDomain()
    }

    override suspend fun getTagByName(name: String): TripTag? = withContext(ioDispatcher) {
        tripTagDao.getByName(name)?.toDomain()
    }

    override suspend fun insertTripTag(name: String): TripTag = withContext(ioDispatcher) {
        val trimmed = name.trim()
        val existing = tripTagDao.getByName(trimmed)
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
        tripTagDao.insert(entity)
        configSyncCoordinator?.schedulePush()
        entity.toDomain()
    }

    override suspend fun updateTripTag(tripTag: TripTag): Unit = withContext(ioDispatcher) {
        tripTagDao.update(TripTagEntity.fromDomain(tripTag))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun setActiveTag(id: String?): Unit = withContext(ioDispatcher) {
        tripTagDao.clearActiveFlags()
        if (id != null) {
            tripTagDao.setActiveFlag(id)
        }
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun archiveTripTag(id: String): Unit = withContext(ioDispatcher) {
        tripTagDao.archiveTag(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun unarchiveTripTag(id: String): Unit = withContext(ioDispatcher) {
        tripTagDao.unarchiveTag(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteTripTag(id: String): Unit = withContext(ioDispatcher) {
        tripTagDao.deleteById(id)
        configSyncCoordinator?.schedulePush()
    }
}
