package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financeautomation.data.local.entity.PendingCaptureEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for pending_captures table.
 */
@Dao
interface PendingCaptureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(capture: PendingCaptureEntity)

    @Update
    suspend fun update(capture: PendingCaptureEntity)

    @Query("SELECT * FROM pending_captures WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PendingCaptureEntity?

    @Query("SELECT * FROM pending_captures WHERE notificationKey = :key ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getByNotificationKey(key: String): PendingCaptureEntity?

    @Query("SELECT * FROM pending_captures WHERE state = :state ORDER BY timestamp ASC")
    suspend fun getByState(state: String): List<PendingCaptureEntity>

    @Query("SELECT * FROM pending_captures WHERE state = 'WAITING' ORDER BY timestamp ASC")
    fun getWaitingCapturesFlow(): Flow<List<PendingCaptureEntity>>

    @Query("SELECT * FROM pending_captures WHERE state = 'WAITING' ORDER BY timestamp ASC")
    suspend fun getWaitingCaptures(): List<PendingCaptureEntity>

    @Query("SELECT * FROM pending_captures WHERE state = 'WAITING' AND timestamp < :cutoffTime ORDER BY timestamp ASC")
    suspend fun getExpiredWaitingCaptures(cutoffTime: Long): List<PendingCaptureEntity>

    @Query("SELECT * FROM pending_captures WHERE state = 'AWAITING_SUCCESS' ORDER BY timestamp ASC")
    suspend fun getAwaitingSuccessCaptures(): List<PendingCaptureEntity>

    @Query("SELECT * FROM pending_captures WHERE amount = :amount AND timestamp >= :minTime AND timestamp <= :maxTime")
    suspend fun findSimilarByAmountAndTime(amount: Double, minTime: Long, maxTime: Long): List<PendingCaptureEntity>

    @Query("UPDATE pending_captures SET state = :state, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pending_captures SET state = 'WAITING', updatedAt = :updatedAt WHERE state = 'SHOWN'")
    suspend fun resetShownToWaiting(updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM pending_captures WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_captures WHERE timestamp < :cutoffTime AND state IN ('DONE', 'DISMISSED')")
    suspend fun pruneOldCompletedCaptures(cutoffTime: Long)
}
