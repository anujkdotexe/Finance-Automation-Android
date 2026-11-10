package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financeautomation.data.local.entity.TripTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for trip_tags table.
 */
@Dao
interface TripTagDao {

    @Query("SELECT * FROM trip_tags ORDER BY createdAt DESC")
    fun getTripTagsFlow(): Flow<List<TripTagEntity>>

    @Query("SELECT * FROM trip_tags WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun getActiveTripTagsFlow(): Flow<List<TripTagEntity>>

    @Query("SELECT * FROM trip_tags ORDER BY createdAt DESC")
    suspend fun getAllTripTags(): List<TripTagEntity>

    @Query("SELECT * FROM trip_tags WHERE isArchived = 0 ORDER BY createdAt DESC")
    suspend fun getActiveTripTags(): List<TripTagEntity>

    @Query("SELECT * FROM trip_tags WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TripTagEntity?

    @Query("SELECT * FROM trip_tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): TripTagEntity?

    @Query("SELECT * FROM trip_tags WHERE isActive = 1 AND isArchived = 0 LIMIT 1")
    suspend fun getActiveTag(): TripTagEntity?

    @Query("SELECT * FROM trip_tags WHERE isActive = 1 AND isArchived = 0 LIMIT 1")
    fun getActiveTagFlow(): Flow<TripTagEntity?>

    @Query("UPDATE trip_tags SET isActive = 0")
    suspend fun clearActiveFlags()

    @Query("UPDATE trip_tags SET isActive = 1 WHERE id = :id")
    suspend fun setActiveFlag(id: String)

    @Query("SELECT COUNT(*) FROM trip_tags")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TripTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TripTagEntity>)

    @Update
    suspend fun update(tag: TripTagEntity)

    @Delete
    suspend fun delete(tag: TripTagEntity)

    @Query("DELETE FROM trip_tags WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE trip_tags SET isArchived = 1, isActive = 0, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun archiveTag(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE trip_tags SET isArchived = 0, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun unarchiveTag(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM trip_tags WHERE dirty = 1")
    suspend fun getDirtyTripTags(): List<TripTagEntity>

    @Query("UPDATE trip_tags SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>)
}
