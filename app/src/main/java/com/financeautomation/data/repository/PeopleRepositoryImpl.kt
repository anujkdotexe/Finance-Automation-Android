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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** True when the named person still has an open balance. Null means balances are not checked. */
    private val openBalanceChecker: (suspend (String) -> Boolean)? = null
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

    // Removed (archived) people are not part of the list the app shows or offers
    override fun getCompanions(): Flow<List<Companion>> = flow {
        val dao = resolveDao()
        emitAll(dao.getCompanionsFlow().map { entities ->
            entities.filter { !it.isArchived }.map { it.toDomain() }
        })
    }.flowOn(ioDispatcher)

    override suspend fun getAllCompanions(): List<Companion> = withContext(ioDispatcher) {
        resolveDao().getCompanionsFlow().first().filter { !it.isArchived }.map { it.toDomain() }
    }

    override suspend fun getCompanionById(id: String): Companion? = withContext(ioDispatcher) {
        resolveDao().getById(id)?.toDomain()
    }

    override suspend fun getCompanionByName(name: String): Companion? = withContext(ioDispatcher) {
        resolveDao().getByName(name)?.toDomain()
    }

    override suspend fun insertCompanion(companion: Companion): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        val removed = dao.getByName(companion.name.trim())
        if (removed != null && removed.isArchived) {
            // A new transaction names a removed person again: bring the same entry back
            dao.unarchiveCompanion(removed.id)
        } else {
            dao.insert(CompanionEntity.fromDomain(companion))
        }
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun removePerson(name: String): com.financeautomation.core.model.Result<Unit> = withContext(ioDispatcher) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return@withContext com.financeautomation.core.model.Result.Error(IllegalArgumentException("Choose a person to remove"))
        }
        if (openBalanceChecker?.invoke(trimmed) == true) {
            return@withContext com.financeautomation.core.model.Result.Error(
                IllegalStateException("$trimmed still has an open balance. Settle it before removing them.")
            )
        }
        val dao = resolveDao()
        val existing = dao.getByName(trimmed)
        if (existing != null) {
            dao.archiveCompanion(existing.id)
        } else {
            // Not in the list yet (known only from transactions): record the removal so a rebuild does not add them
            dao.insert(
                CompanionEntity(id = "removed_${java.util.UUID.randomUUID().toString().take(8)}", name = trimmed, isArchived = true,
                    updatedAt = System.currentTimeMillis(), dirty = 1)
            )
        }
        configSyncCoordinator?.schedulePush()
        com.financeautomation.core.model.Result.Success(Unit)
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
        val coordinator = configSyncCoordinator
        val now = System.currentTimeMillis()
        coordinator?.localGateway?.recordTombstone(
            com.financeautomation.core.constants.ConfigConstants.TYPE_PERSON,
            companion.name,
            now
        )
        resolveDao().delete(CompanionEntity.fromDomain(companion))
        coordinator?.schedulePush()
    }

    override suspend fun deleteCompanionById(id: String): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        val comp = dao.getById(id)
        val coordinator = configSyncCoordinator
        val now = System.currentTimeMillis()
        if (comp != null) {
            coordinator?.localGateway?.recordTombstone(
                com.financeautomation.core.constants.ConfigConstants.TYPE_PERSON,
                comp.name,
                now
            )
        }
        dao.deleteById(id)
        coordinator?.schedulePush()
    }
}
