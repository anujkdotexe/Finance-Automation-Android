package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.CompanionDao
import com.financeautomation.data.local.entity.CompanionEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.repository.PeopleRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Implementation of [PeopleRepository] backed by Room SQLite [CompanionDao].
 */
class PeopleRepositoryImpl(
    private val companionDao: CompanionDao,
    private val configSyncCoordinator: ConfigSyncCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PeopleRepository {

    override fun getCompanions(): Flow<List<Companion>> {
        return companionDao.getCompanionsFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getAllCompanions(): List<Companion> = withContext(ioDispatcher) {
        companionDao.getCompanionsFlow().first().map { it.toDomain() }
    }

    override suspend fun getCompanionById(id: String): Companion? = withContext(ioDispatcher) {
        companionDao.getById(id)?.toDomain()
    }

    override suspend fun getCompanionByName(name: String): Companion? = withContext(ioDispatcher) {
        companionDao.getByName(name)?.toDomain()
    }

    override suspend fun insertCompanion(companion: Companion): Unit = withContext(ioDispatcher) {
        companionDao.insert(CompanionEntity.fromDomain(companion))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun updateCompanion(companion: Companion): Unit = withContext(ioDispatcher) {
        companionDao.update(CompanionEntity.fromDomain(companion))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun updateCompanionName(id: String, newName: String): Unit = withContext(ioDispatcher) {
        companionDao.updateName(id, newName.trim())
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteCompanion(companion: Companion): Unit = withContext(ioDispatcher) {
        companionDao.delete(CompanionEntity.fromDomain(companion))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteCompanionById(id: String): Unit = withContext(ioDispatcher) {
        companionDao.deleteById(id)
        configSyncCoordinator?.schedulePush()
    }
}
