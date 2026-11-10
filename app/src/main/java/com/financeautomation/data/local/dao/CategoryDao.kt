package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financeautomation.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for categories table.
 */
@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY displayOrder ASC, name ASC")
    fun getCategoriesFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY displayOrder ASC, name ASC")
    fun getActiveCategoriesFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY displayOrder ASC, name ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY displayOrder ASC, name ASC")
    suspend fun getActiveCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE categories SET isExcludedFromLivingBurn = :excluded, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE categories SET isArchived = 1, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun archiveCategory(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE categories SET isArchived = 0, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun unarchiveCategory(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE categories SET name = :name, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateName(id: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE categories SET displayOrder = :displayOrder, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateDisplayOrder(id: String, displayOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM categories WHERE dirty = 1")
    suspend fun getDirtyCategories(): List<CategoryEntity>

    @Query("UPDATE categories SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>)
}
