package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.financeautomation.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    /** Live budgets, tombstones left out. */
    @Query("SELECT * FROM budgets WHERE deleted = 0 ORDER BY category COLLATE NOCASE ASC")
    fun getBudgetsFlow(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE deleted = 0 ORDER BY category COLLATE NOCASE ASC")
    suspend fun getAll(): List<BudgetEntity>

    /** Every row including tombstones, used when marking all items dirty. */
    @Query("SELECT * FROM budgets")
    suspend fun getAllIncludingDeleted(): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE category = :category LIMIT 1")
    suspend fun getByCategory(category: String): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    /** Soft delete: keeps a tombstone so the removal can be pushed to the Config tab. */
    @Query("UPDATE budgets SET deleted = 1, dirty = 1, updatedAt = :updatedAt WHERE category = :category")
    suspend fun markDeleted(category: String, updatedAt: Long)

    @Query("SELECT * FROM budgets WHERE dirty = 1")
    suspend fun getDirty(): List<BudgetEntity>

    @Query("UPDATE budgets SET dirty = 0 WHERE category IN (:categories)")
    suspend fun markClean(categories: List<String>)
}
