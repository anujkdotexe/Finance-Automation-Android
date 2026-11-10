package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financeautomation.data.local.entity.CompanionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for companions table.
 */
@Dao
interface CompanionDao {

    @Query("SELECT * FROM companions ORDER BY name ASC")
    fun getCompanionsFlow(): Flow<List<CompanionEntity>>

    @Query("SELECT * FROM companions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CompanionEntity?

    @Query("SELECT * FROM companions WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): CompanionEntity?

    @Query("SELECT * FROM companions WHERE isSelected = 1")
    suspend fun getSelectedCompanions(): List<CompanionEntity>

    @Query("SELECT COUNT(*) FROM companions")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(companion: CompanionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(companions: List<CompanionEntity>)

    @Update
    suspend fun update(companion: CompanionEntity)

    @Delete
    suspend fun delete(companion: CompanionEntity)

    @Query("DELETE FROM companions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM companions WHERE isArchived = 0 ORDER BY name ASC")
    fun getActiveCompanionsFlow(): Flow<List<CompanionEntity>>

    @Query("SELECT * FROM companions WHERE isArchived = 0 ORDER BY name ASC")
    suspend fun getActiveCompanions(): List<CompanionEntity>

    @Query("UPDATE companions SET isArchived = 1, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun archiveCompanion(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE companions SET isArchived = 0, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun unarchiveCompanion(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE companions SET name = :name, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateName(id: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM companions WHERE dirty = 1")
    suspend fun getDirtyCompanions(): List<CompanionEntity>

    @Query("UPDATE companions SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>)
}
