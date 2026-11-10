package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.financeautomation.data.local.entity.LineageEntity

/**
 * Room Data Access Object for lineage table.
 */
@Dao
interface LineageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LineageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<LineageEntity>)

    @Query("SELECT * FROM lineage WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): LineageEntity?

    @Query("SELECT * FROM lineage WHERE carried_from_uuid = :carriedFromUuid LIMIT 1")
    suspend fun getByCarriedFrom(carriedFromUuid: String): LineageEntity?

    @Query("SELECT * FROM lineage")
    suspend fun getAll(): List<LineageEntity>
}

