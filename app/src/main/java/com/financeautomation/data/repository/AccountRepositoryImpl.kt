package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.repository.AccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Implementation of [AccountRepository] backed by Room SQLite [AccountDao].
 */
class AccountRepositoryImpl(
    private val accountDao: AccountDao,
    private val configSyncCoordinator: ConfigSyncCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AccountRepository {

    override fun getAccounts(includeArchived: Boolean): Flow<List<Account>> {
        val flow = if (includeArchived) {
            accountDao.getAccountsFlow()
        } else {
            accountDao.getActiveAccountsFlow()
        }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getAllAccounts(includeArchived: Boolean): List<Account> = withContext(ioDispatcher) {
        val entities = if (includeArchived) {
            accountDao.getAllAccounts()
        } else {
            accountDao.getActiveAccounts()
        }
        entities.map { it.toDomain() }
    }

    override suspend fun getAccountById(id: String): Account? = withContext(ioDispatcher) {
        accountDao.getById(id)?.toDomain()
    }

    override suspend fun getAccountByName(name: String): Account? = withContext(ioDispatcher) {
        accountDao.getByName(name)?.toDomain()
    }

    override suspend fun getDefaultAccount(): Account? = withContext(ioDispatcher) {
        accountDao.getDefaultAccount()?.toDomain()
    }

    override suspend fun insertAccount(account: Account): Unit = withContext(ioDispatcher) {
        if (account.isDefault) {
            accountDao.clearDefaultFlag()
        }
        accountDao.insert(AccountEntity.fromDomain(account))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun updateAccount(account: Account): Unit = withContext(ioDispatcher) {
        if (account.isDefault) {
            accountDao.clearDefaultFlag()
        }
        accountDao.update(AccountEntity.fromDomain(account))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun setDefaultAccount(id: String): Unit = withContext(ioDispatcher) {
        accountDao.clearDefaultFlag()
        accountDao.setDefaultFlag(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun archiveAccount(id: String): Unit = withContext(ioDispatcher) {
        accountDao.archiveAccount(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun unarchiveAccount(id: String): Unit = withContext(ioDispatcher) {
        accountDao.unarchiveAccount(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteAccount(id: String): Unit = withContext(ioDispatcher) {
        accountDao.deleteById(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun reorderAccounts(accountIds: List<String>): Unit = withContext(ioDispatcher) {
        accountIds.forEachIndexed { index, id ->
            accountDao.updateDisplayOrder(id, index)
        }
        configSyncCoordinator?.schedulePush()
    }
}
