package com.financeautomation.data.repository

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Implementation of [AccountRepository] backed by Room SQLite [AccountDao]
 * or a suspend DAO provider for main-thread safety.
 */
class AccountRepositoryImpl(
    private val accountDao: AccountDao? = null,
    private val accountDaoProvider: (suspend () -> AccountDao)? = null,
    private val configSyncCoordinatorProvider: (() -> ConfigSyncCoordinator)? = null,
    private val localGatewayProvider: (() -> ConfigSyncLocalGateway)? = null,
    private val transactionDaoProvider: (suspend () -> TransactionDao)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AccountRepository {

    constructor(
        accountDao: AccountDao,
        configSyncCoordinator: ConfigSyncCoordinator? = null,
        localGateway: ConfigSyncLocalGateway? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        accountDao = accountDao,
        accountDaoProvider = null,
        configSyncCoordinatorProvider = configSyncCoordinator?.let { { it } },
        localGatewayProvider = localGateway?.let { { it } },
        ioDispatcher = ioDispatcher
    )

    private val configSyncCoordinator: ConfigSyncCoordinator?
        get() = configSyncCoordinatorProvider?.invoke()

    private val localGateway: ConfigSyncLocalGateway?
        get() = localGatewayProvider?.invoke() ?: configSyncCoordinator?.localGateway

    private suspend fun resolveDao(): AccountDao =
        accountDao ?: accountDaoProvider?.invoke()
            ?: throw IllegalStateException("AccountDao or provider must be supplied")

    override fun getAccountTransactionCounts(from: Long, to: Long): Flow<Map<String, Int>> = flow {
        val dao = transactionDaoProvider?.invoke()
        if (dao == null) {
            emit(emptyMap())
        } else {
            emitAll(dao.getAccountTransactionCounts(from, to).map { rows -> rows.associate { it.account to it.count } })
        }
    }.flowOn(ioDispatcher)

    override fun getAccounts(includeArchived: Boolean): Flow<List<Account>> = flow {
        val dao = resolveDao()
        val baseFlow = if (includeArchived) {
            dao.getAccountsFlow()
        } else {
            dao.getActiveAccountsFlow()
        }
        emitAll(baseFlow.map { entities -> entities.map { it.toDomain() } })
    }.flowOn(ioDispatcher)

    override suspend fun getAllAccounts(includeArchived: Boolean): List<Account> = withContext(ioDispatcher) {
        val dao = resolveDao()
        val entities = if (includeArchived) {
            dao.getAllAccounts()
        } else {
            dao.getActiveAccounts()
        }
        entities.map { it.toDomain() }
    }

    override suspend fun getAccountById(id: String): Account? = withContext(ioDispatcher) {
        resolveDao().getById(id)?.toDomain()
    }

    override suspend fun getAccountByName(name: String): Account? = withContext(ioDispatcher) {
        resolveDao().getByName(name)?.toDomain()
    }

    override suspend fun getDefaultAccount(): Account? = withContext(ioDispatcher) {
        resolveDao().getDefaultAccount()?.toDomain()
    }

    override suspend fun insertAccount(account: Account): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        val isFirstUserAccount = dao.count() <= 2 // Only Cash and Other existed
        val effectiveAccount = if (isFirstUserAccount && !account.isDefault) {
            account.copy(isDefault = true)
        } else {
            account
        }
        if (effectiveAccount.isDefault) {
            dao.clearDefaultFlag()
        }
        dao.insert(AccountEntity.fromDomain(effectiveAccount))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun updateAccount(account: Account): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        if (account.isDefault) {
            dao.clearDefaultFlag()
        }
        dao.update(AccountEntity.fromDomain(account))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun setDefaultAccount(id: String): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        dao.clearDefaultFlag()
        dao.setDefaultFlag(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun archiveAccount(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().archiveAccount(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun unarchiveAccount(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().unarchiveAccount(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteAccount(id: String): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        val account = dao.getById(id)
        if (account != null && (account.name.equals(CategoryConstants.ACCOUNT_CASH, ignoreCase = true) ||
                account.name.equals(CategoryConstants.ACCOUNT_OTHER, ignoreCase = true))) {
            return@withContext
        }
        val gateway = localGateway
        val now = System.currentTimeMillis()
        if (account != null) {
            if (gateway != null) {
                gateway.recordTombstone(com.financeautomation.core.constants.ConfigConstants.TYPE_ACCOUNT, account.name, now)
            } else {
                Diagnostics.record("AccountRepositoryImpl", "tombstone_skipped: no gateway or coordinator")
            }
        }
        dao.deleteById(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun reorderAccounts(accountIds: List<String>): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        accountIds.forEachIndexed { index, id ->
            dao.updateDisplayOrder(id, index)
        }
        configSyncCoordinator?.schedulePush()
    }
}
