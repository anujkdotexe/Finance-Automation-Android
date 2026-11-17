package com.financeautomation.testutil

import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** An in-memory [AccountDao]. */
class InMemoryAccountDao : AccountDao {
    val accounts = mutableMapOf<String, AccountEntity>()
    override fun getAccountsFlow(): Flow<List<AccountEntity>> = flowOf(accounts.values.toList())
    override fun getActiveAccountsFlow(): Flow<List<AccountEntity>> = flowOf(accounts.values.filter { !it.isArchived })
    override suspend fun getAllAccounts(): List<AccountEntity> = accounts.values.toList()
    override suspend fun getActiveAccounts(): List<AccountEntity> = accounts.values.filter { !it.isArchived }
    override suspend fun getById(id: String): AccountEntity? = accounts[id]
    override suspend fun getByName(name: String): AccountEntity? = accounts.values.find { it.name.equals(name, ignoreCase = true) }
    override suspend fun getDefaultAccount(): AccountEntity? = accounts.values.find { it.isDefault }
    override suspend fun insert(account: AccountEntity) { accounts[account.id] = account }
    override suspend fun insertAll(accounts: List<AccountEntity>) { accounts.forEach { insert(it) } }
    override suspend fun update(account: AccountEntity) { accounts[account.id] = account }
    override suspend fun clearDefaultFlag() {
        accounts.replaceAll { _, a -> a.copy(isDefault = false) }
    }
    override suspend fun setDefaultFlag(id: String) {
        accounts[id]?.let { accounts[id] = it.copy(isDefault = true) }
    }
    override suspend fun archiveAccount(id: String, updatedAt: Long) {
        accounts[id]?.let { accounts[id] = it.copy(isArchived = true, updatedAt = updatedAt) }
    }
    override suspend fun unarchiveAccount(id: String, updatedAt: Long) {
        accounts[id]?.let { accounts[id] = it.copy(isArchived = false, updatedAt = updatedAt) }
    }
    override suspend fun delete(account: AccountEntity) { accounts.remove(account.id) }
    override suspend fun deleteById(id: String) { accounts.remove(id) }
    override suspend fun updateDisplayOrder(id: String, displayOrder: Int, updatedAt: Long) {
        accounts[id]?.let { accounts[id] = it.copy(displayOrder = displayOrder, updatedAt = updatedAt) }
    }
    override suspend fun updateName(id: String, name: String, updatedAt: Long) {
        accounts[id]?.let { accounts[id] = it.copy(name = name, updatedAt = updatedAt) }
    }
    override suspend fun getDirtyAccounts(): List<AccountEntity> = emptyList()
    override suspend fun markClean(ids: List<String>) {}
    override suspend fun makeOnlyDefault(id: String) {
        accounts.replaceAll { key, a -> a.copy(isDefault = key == id) }
    }
    override suspend fun count(): Int = accounts.size
}
