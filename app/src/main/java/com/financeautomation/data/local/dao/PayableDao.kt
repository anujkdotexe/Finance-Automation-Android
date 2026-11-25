package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.financeautomation.data.local.entity.PayableEntity
import kotlinx.coroutines.flow.Flow

/**
 * A debt stays open while something is still owed on it: not yet repaid ("Pending") or repaid in part ("Partial").
 * The sheet formulas count both, so every screen must too.
 */
object PayableSql {
    const val SELECT_OPEN: String = "SELECT * FROM payables WHERE LOWER(repaymentStatus) IN ('pending', 'partial')"
    const val SELECT_OPEN_NEWEST_FIRST: String = "$SELECT_OPEN ORDER BY dateIncurredSerial DESC, timeSerial DESC"
}

/**
 * Data Access Object for local payables ("I Owe People") table.
 */
@Dao
interface PayableDao {

    @Query("SELECT * FROM payables ORDER BY dateIncurredSerial DESC, timeSerial DESC")
    fun observeAll(): Flow<List<PayableEntity>>

    /** Open debts, partly repaid ones included. */
    @Query(PayableSql.SELECT_OPEN_NEWEST_FIRST)
    fun observePending(): Flow<List<PayableEntity>>

    /** Open debts, partly repaid ones included. */
    @Query(PayableSql.SELECT_OPEN)
    suspend fun getPending(): List<PayableEntity>

    @Query("SELECT * FROM payables")
    suspend fun getAll(): List<PayableEntity>

    @Query("""
        SELECT * FROM payables 
        WHERE ((dateIncurredSerial >= :startSerial AND dateIncurredSerial < :endSerial) 
               OR (dateIncurredSerial IS NULL AND (updatedAt >= :startMillis AND updatedAt < :endMillis))) 
          AND UPPER(repaymentStatus) != 'VOID' 
        ORDER BY dateIncurredSerial ASC, timeSerial ASC
    """)
    suspend fun getAllForYear(
        startSerial: Int,
        endSerial: Int,
        startMillis: Long,
        endMillis: Long
    ): List<PayableEntity>

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

    @Query("UPDATE payables SET amountIOwe = :amountIOwe, repaymentStatus = :status, dateRepaidSerial = :dateRepaidSerial WHERE uuid = :uuid")
    suspend fun updateAfterNetting(uuid: String, amountIOwe: Double, status: String, dateRepaidSerial: Int?): Int = 0

    @Query("DELETE FROM payables")
    suspend fun deleteAll()
}

