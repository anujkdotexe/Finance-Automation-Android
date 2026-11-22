package com.financeautomation.testutil

import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An in-memory [AccountDao]. */
class InMemoryAccountDao : AccountDao {
    val accounts = mutableMapOf<String, AccountEntity>()
    private val _flow = MutableStateFlow<List<AccountEntity>>(emptyList())
    private fun sync() { _flow.value = accounts.values.toList() }

    override fun getAccountsFlow(): Flow<List<AccountEntity>> = _flow
    override fun getActiveAccountsFlow(): Flow<List<AccountEntity>> = _flow.map { list -> list.filter { !it.isArchived } }
    override suspend fun getAllAccounts(): List<AccountEntity> = accounts.values.toList()
    override suspend fun getActiveAccounts(): List<AccountEntity> = accounts.values.filter { !it.isArchived }
    override suspend fun getById(id: String): AccountEntity? = accounts[id]
    override suspend fun getByName(name: String): AccountEntity? = accounts.values.find { it.name.equals(name, ignoreCase = true) }
    override suspend fun getDefaultAccount(): AccountEntity? = accounts.values.find { it.isDefault }
    override suspend fun insert(account: AccountEntity) {
        accounts[account.id] = account
        sync()
    }
    override suspend fun insertAll(accounts: List<AccountEntity>) {
        accounts.forEach { this.accounts[it.id] = it }
        sync()
    }
    override suspend fun update(account: AccountEntity) {
        accounts[account.id] = account
        sync()
    }
    override suspend fun clearDefaultFlag() {
        accounts.replaceAll { _, a -> a.copy(isDefault = false) }
        sync()
    }
    override suspend fun setDefaultFlag(id: String) {
        accounts[id]?.let { accounts[id] = it.copy(isDefault = true) }
        sync()
    }
    override suspend fun archiveAccount(id: String, updatedAt: Long) {
        accounts[id]?.let { accounts[id] = it.copy(isArchived = true, isDefault = false, updatedAt = updatedAt) }
        sync()
    }
    override suspend fun unarchiveAccount(id: String, updatedAt: Long) {
        accounts[id]?.let { accounts[id] = it.copy(isArchived = false, updatedAt = updatedAt) }
        sync()
    }
    override suspend fun delete(account: AccountEntity) {
        accounts.remove(account.id)
        sync()
    }
    override suspend fun deleteById(id: String) {
        accounts.remove(id)
        sync()
    }
    override suspend fun updateDisplayOrder(id: String, displayOrder: Int, updatedAt: Long) {
        accounts[id]?.let { accounts[id] = it.copy(displayOrder = displayOrder, updatedAt = updatedAt) }
        sync()
    }
    override suspend fun updateName(id: String, name: String, updatedAt: Long) {
        accounts[id]?.let { accounts[id] = it.copy(name = name, updatedAt = updatedAt) }
        sync()
    }
    override suspend fun getDirtyAccounts(): List<AccountEntity> = emptyList()
    override suspend fun markClean(ids: List<String>) {}
    override suspend fun makeOnlyDefault(id: String, updatedAt: Long) {
        accounts.replaceAll { key, a ->
            if ((key == id && !a.isDefault) || (key != id && a.isDefault)) {
                a.copy(isDefault = key == id, dirty = 1, updatedAt = updatedAt)
            } else {
                a
            }
        }
        sync()
    }
    override suspend fun makeOnlyDefault(id: String) {
        makeOnlyDefault(id, System.currentTimeMillis())
    }
    override suspend fun count(): Int = accounts.size
}
