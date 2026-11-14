package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.financeautomation.data.local.entity.InflowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InflowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(inflow: InflowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(inflows: List<InflowEntity>)

    @Update
    suspend fun update(inflow: InflowEntity)

    @Query("SELECT * FROM inflows WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InflowEntity?

    @Query("SELECT * FROM inflows WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp DESC")
    fun getInflowsByRange(start: Long, end: Long): Flow<List<InflowEntity>>

    @Query("SELECT * FROM inflows ORDER BY timestamp DESC")
    fun getAllInflows(): Flow<List<InflowEntity>>

    @Query("UPDATE inflows SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM inflows WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Deletes the entry only if it is still void when the delete runs. */
    @Transaction
    suspend fun deleteIfVoid(id: String): Boolean {
        val row = getById(id) ?: return false
        if (!row.status.equals("VOID", ignoreCase = true)) return false
        deleteById(id)
        return true
    }
}

