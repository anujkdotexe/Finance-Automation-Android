package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financeautomation.data.local.entity.SubscriptionCycleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionCycleDao {

    @Query("SELECT * FROM subscription_cycles WHERE state IN ('DUE', 'NEEDS_REVIEW') ORDER BY dueEpochDay ASC")
    fun getOpenCyclesFlow(): Flow<List<SubscriptionCycleEntity>>

    @Query("SELECT * FROM subscription_cycles WHERE state = 'NEEDS_REVIEW' ORDER BY dueEpochDay ASC")
    fun getNeedsReviewCyclesFlow(): Flow<List<SubscriptionCycleEntity>>

    @Query("SELECT * FROM subscription_cycles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SubscriptionCycleEntity?

    @Query("SELECT * FROM subscription_cycles WHERE dueEpochDay >= :from AND dueEpochDay <= :to ORDER BY dueEpochDay ASC")
    suspend fun getInWindow(from: Long, to: Long): List<SubscriptionCycleEntity>

    @Query("SELECT * FROM subscription_cycles WHERE state = 'DUE' AND dueEpochDay <= :epochDay ORDER BY dueEpochDay ASC")
    suspend fun getDueUpTo(epochDay: Long): List<SubscriptionCycleEntity>

    @Query("SELECT * FROM subscription_cycles WHERE subscriptionId = :subscriptionId AND state IN ('DUE', 'NEEDS_REVIEW')")
    suspend fun getOpenForSubscription(subscriptionId: String): List<SubscriptionCycleEntity>

    /** Returns the new row id, or -1 when the (subscriptionId, dueEpochDay) pair already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(cycle: SubscriptionCycleEntity): Long

    @Update
    suspend fun update(cycle: SubscriptionCycleEntity)

    @Query("UPDATE subscription_cycles SET state = :state, remindCount = :remindCount, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setState(id: String, state: String, remindCount: Int, updatedAt: Long)

    @Query("UPDATE subscription_cycles SET state = 'PAID', transactionId = :transactionId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markPaid(id: String, transactionId: String, updatedAt: Long)
}
