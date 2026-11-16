package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.financeautomation.data.local.entity.HandledNotificationEntity

/**
 * Room Data Access Object for handled_notifications table.
 */
@Dao
interface HandledNotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(handled: HandledNotificationEntity)

    @Query("SELECT * FROM handled_notifications WHERE `key` = :key AND postTime = :postTime LIMIT 1")
    suspend fun get(key: String, postTime: Long): HandledNotificationEntity?

    @Query("SELECT * FROM handled_notifications WHERE `key` = :key ORDER BY postTime DESC LIMIT 1")
    suspend fun getLatestByKey(key: String): HandledNotificationEntity?

    @Query("SELECT * FROM handled_notifications WHERE contentHash = :hash AND handledAt >= :since LIMIT 1")
    suspend fun getByContentHash(hash: String, since: Long): HandledNotificationEntity?

    @Query("DELETE FROM handled_notifications WHERE handledAt < :cutoffTime")
    suspend fun pruneOlderThan(cutoffTime: Long)
}
