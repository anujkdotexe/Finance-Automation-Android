package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.financeautomation.data.local.entity.PayableEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for local payables ("I Owe People") table.
 */
@Dao
interface PayableDao {

    @Query("SELECT * FROM payables ORDER BY dateIncurredSerial DESC, timeSerial DESC")
    fun observeAll(): Flow<List<PayableEntity>>

    @Query("SELECT * FROM payables WHERE LOWER(repaymentStatus) = 'pending' ORDER BY dateIncurredSerial DESC, timeSerial DESC")
    fun observePending(): Flow<List<PayableEntity>>

    @Query("SELECT * FROM payables WHERE LOWER(repaymentStatus) = 'pending'")
    suspend fun getPending(): List<PayableEntity>

    @Query("SELECT * FROM payables")
    suspend fun getAll(): List<PayableEntity>

    @Query("SELECT * FROM payables WHERE (dateIncurredSerial >= :startSerial AND dateIncurredSerial < :endSerial) ORDER BY dateIncurredSerial ASC, timeSerial ASC")
    suspend fun getAllForYear(startSerial: Int, endSerial: Int): List<PayableEntity>

    @Query("SELECT * FROM payables WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): PayableEntity?

    @Upsert
    suspend fun upsert(payable: PayableEntity)

    @Upsert
    suspend fun upsertAll(payables: List<PayableEntity>)

    @Query("DELETE FROM payables WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("UPDATE payables SET paidBy = :newName WHERE paidBy = :oldName")
    suspend fun updatePaidByName(oldName: String, newName: String): Int

    @Query("DELETE FROM payables")
    suspend fun deleteAll()
}

