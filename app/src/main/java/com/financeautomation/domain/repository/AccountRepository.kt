package com.financeautomation.domain.repository

import com.financeautomation.domain.model.Account
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing bank and funding accounts.
 * Pure Kotlin contract with zero Android framework dependencies.
 */
interface AccountRepository {

    /**
     * Observes accounts ordered by display order.
     */
    fun getAccounts(includeArchived: Boolean = false): Flow<List<Account>>

    /**
     * Retrieves all accounts as a snapshot.
     */
    suspend fun getAllAccounts(includeArchived: Boolean = false): List<Account>

    /**
     * Retrieves an account by its unique identifier.
     */
    suspend fun getAccountById(id: String): Account?

    /**
     * Retrieves an account by its unique name.
     */
    suspend fun getAccountByName(name: String): Account?

    /**
     * Retrieves the current default funding account.
     */
    suspend fun getDefaultAccount(): Account?

    /**
     * Inserts a new account.
     */
    suspend fun insertAccount(account: Account)

    /**
     * Updates an existing account.
     */
    suspend fun updateAccount(account: Account)

    /**
     * Sets an account as the default funding account, clearing default on all others.
     */
    suspend fun setDefaultAccount(id: String)

    /**
     * Archives an account.
     */
    suspend fun archiveAccount(id: String)

    /**
     * Restores an archived account.
     */
    suspend fun unarchiveAccount(id: String)

    /**
     * Permanently deletes an account by identifier.
     */
    suspend fun deleteAccount(id: String)

    /**
     * Reorders accounts according to the provided list of IDs.
     */
    suspend fun reorderAccounts(accountIds: List<String>)
}
