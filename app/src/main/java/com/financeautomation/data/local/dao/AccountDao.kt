package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financeautomation.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for accounts table.
 */
@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY displayOrder ASC, name ASC")
    fun getAccountsFlow(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY displayOrder ASC, name ASC")
    fun getActiveAccountsFlow(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY displayOrder ASC, name ASC")
    suspend fun getAllAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY displayOrder ASC, name ASC")
    suspend fun getActiveAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE isDefault = 1 AND isArchived = 0 LIMIT 1")
    suspend fun getDefaultAccount(): AccountEntity?

    @Query("UPDATE accounts SET isDefault = 0")
    suspend fun clearDefaultFlag()

    /** Makes [id] the only default account in one statement, so there is never a moment with two or none, and marks changed rows dirty for Config sync. */
    @Query("UPDATE accounts SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END, dirty = 1, updatedAt = :updatedAt")
    suspend fun makeOnlyDefault(id: String, updatedAt: Long)

    suspend fun makeOnlyDefault(id: String) {
        makeOnlyDefault(id, System.currentTimeMillis())
    }

    @Query("UPDATE accounts SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultFlag(id: String)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE accounts SET isArchived = 1, isDefault = 0, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun archiveAccount(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE accounts SET isArchived = 0, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun unarchiveAccount(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE accounts SET displayOrder = :displayOrder, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateDisplayOrder(id: String, displayOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE accounts SET name = :name, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateName(id: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM accounts WHERE dirty = 1")
    suspend fun getDirtyAccounts(): List<AccountEntity>

    @Query("UPDATE accounts SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>)
}
