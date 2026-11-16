package com.financeautomation.data.repository

import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.PreferencesRepository
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
    private val configSyncCoordinatorProvider: (() -> ConfigSyncCoordinator)? = null,
    private val localGatewayProvider: (() -> ConfigSyncLocalGateway)? = null,
    private val preferencesRepositoryProvider: (() -> PreferencesRepository)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TripTagRepository {

    constructor(
        tripTagDao: TripTagDao,
        configSyncCoordinator: ConfigSyncCoordinator? = null,
        localGateway: ConfigSyncLocalGateway? = null,
        preferencesRepository: PreferencesRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        tripTagDao = tripTagDao,
        tripTagDaoProvider = null,
        configSyncCoordinatorProvider = configSyncCoordinator?.let { { it } },
        localGatewayProvider = localGateway?.let { { it } },
        preferencesRepositoryProvider = preferencesRepository?.let { { it } },
        ioDispatcher = ioDispatcher
    )

    private val configSyncCoordinator: ConfigSyncCoordinator?
        get() = configSyncCoordinatorProvider?.invoke()

    private val localGateway: ConfigSyncLocalGateway?
        get() = localGatewayProvider?.invoke() ?: configSyncCoordinator?.localGateway

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
        val tagName = dao.setActiveTagAtomic(id)
        preferencesRepositoryProvider?.invoke()?.setActiveTrip(tagName)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun archiveTripTag(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().archiveTag(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun removeTripTag(name: String): com.financeautomation.core.model.Result<Unit> = withContext(ioDispatcher) {
        val dao = resolveDao()
        val tag = dao.getByName(name.trim())
            ?: return@withContext com.financeautomation.core.model.Result.Error(IllegalArgumentException("Trip not found: ${name.trim()}"))
        if (tag.isActive && !tag.isArchived) {
            return@withContext com.financeautomation.core.model.Result.Error(
                IllegalStateException("${tag.name} is the active trip. End the trip before removing it.")
            )
        }
        dao.archiveTag(tag.id)
        configSyncCoordinator?.schedulePush()
        com.financeautomation.core.model.Result.Success(Unit)
    }

    override suspend fun unarchiveTripTag(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().unarchiveTag(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteTripTag(id: String): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        val tag = dao.getById(id)
        val gateway = localGateway
        val now = System.currentTimeMillis()
        if (tag != null) {
            if (gateway != null) {
                gateway.recordTombstone(
                    com.financeautomation.core.constants.ConfigConstants.TYPE_TRIP,
                    tag.name,
                    now
                )
            } else {
                Diagnostics.record("TripTagRepositoryImpl", "tombstone_skipped: no gateway or coordinator")
            }
        }
        dao.deleteById(id)
        configSyncCoordinator?.schedulePush()
    }
}
