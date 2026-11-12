package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financeautomation.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for sync_queue table.
 */
@Dao
interface SyncQueueDao {

    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
    fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>>

    @Query("SELECT * FROM sync_queue ORDER BY createdAt DESC")
    fun getAllQueueFlow(): Flow<List<SyncQueueEntity>>

    @Query("SELECT * FROM sync_queue WHERE status = 'FAILED' OR retryCount >= 8 ORDER BY createdAt DESC")
    fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueEntity): Long

    @Update
    suspend fun update(item: SyncQueueEntity)

    @Delete
    suspend fun delete(item: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sync_queue WHERE transactionId = :transactionId")
    suspend fun deleteByTransactionId(transactionId: String)

    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' OR (status = 'FAILED' AND retryCount < :maxRetries) ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextPending(maxRetries: Int = 8): SyncQueueEntity?

    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' OR (status = 'FAILED' AND retryCount < :maxRetries) ORDER BY createdAt ASC")
    suspend fun getAllPending(maxRetries: Int = 8): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' OR (status = 'FAILED' AND retryCount < :maxRetries) ORDER BY createdAt ASC")
    suspend fun getPendingQueueFifo(maxRetries: Int = 8): List<SyncQueueEntity>

    @Query("UPDATE sync_queue SET status = :status, retryCount = retryCount + :retryIncrement WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, retryIncrement: Int = 0)

    @Query("UPDATE sync_queue SET status = :status, retryCount = :retryCount WHERE id = :id")
    suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int)

    @Query("SELECT COUNT(*) FROM sync_queue WHERE operationType = :opType AND (status = 'PENDING' OR status = 'IN_PROGRESS')")
    suspend fun countPendingOperations(opType: String): Int

    @Query("UPDATE sync_queue SET planJson = :planJson WHERE id = :id")
    suspend fun updatePlanJson(id: Long, planJson: String?)
}
