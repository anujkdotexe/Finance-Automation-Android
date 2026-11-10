package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.financeautomation.data.local.entity.PipelineEventEntity
import kotlinx.coroutines.flow.Flow

data class StageCodeCount(
    val stage: String,
    val code: String,
    val count: Int
)

/**
 * Data access object for the pipeline_events table supporting batched inserts and ring-buffer pruning.
 */
@Dao
interface PipelineEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(events: List<PipelineEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PipelineEventEntity)

    @Query("SELECT * FROM pipeline_events ORDER BY time DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int): List<PipelineEventEntity>

    @Query("SELECT * FROM pipeline_events ORDER BY time DESC LIMIT :limit")
    fun getRecentEventsFlow(limit: Int): Flow<List<PipelineEventEntity>>

    @Query("SELECT * FROM pipeline_events WHERE time >= :sinceTime ORDER BY time ASC")
    suspend fun getEventsSince(sinceTime: Long): List<PipelineEventEntity>

    @Query("SELECT stage, code, COUNT(*) as count FROM pipeline_events WHERE time >= :startTime AND time <= :endTime GROUP BY stage, code")
    suspend fun getFunnelCounts(startTime: Long, endTime: Long): List<StageCodeCount>

    @Query("DELETE FROM pipeline_events WHERE time < :cutoffTime")
    suspend fun pruneOlderThan(cutoffTime: Long): Int

    @Query("DELETE FROM pipeline_events WHERE id NOT IN (SELECT id FROM pipeline_events ORDER BY time DESC LIMIT :maxCount)")
    suspend fun pruneToMaxCount(maxCount: Int): Int

    @Query("SELECT COUNT(*) FROM pipeline_events")
    suspend fun getTotalCount(): Int
}

