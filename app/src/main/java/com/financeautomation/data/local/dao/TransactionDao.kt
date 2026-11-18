package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.financeautomation.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Per-account count of non-void transactions in a time window.
 */
data class AccountTransactionCount(
    val account: String,
    val count: Int
)

/**
 * Room Data Access Object for transactions table.
 */
@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getTransactionsFlow(): Flow<List<TransactionEntity>>

    // A voided row is never waiting for review, even if it was voided while it still was
    @Query("SELECT * FROM transactions WHERE reviewState = 'NEEDS_REVIEW' AND UPPER(status) != 'VOID' ORDER BY timestamp DESC")
    fun getNeedsReviewFlow(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE reviewState = 'CONFIRMED' AND syncState = 'PENDING' ORDER BY timestamp ASC")
    fun getConfirmedUnsyncedFlow(): Flow<List<TransactionEntity>>

    // The status column stores "Void" (TransactionStatus.VOID.value), so compare case-insensitively
    @Query("SELECT account, COUNT(*) AS count FROM transactions WHERE timestamp >= :from AND timestamp < :to AND UPPER(status) != 'VOID' GROUP BY account")
    fun getAccountTransactionCounts(from: Long, to: Long): Flow<List<AccountTransactionCount>>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE sheetRowIndex = :rowIndex LIMIT 1")
    suspend fun getBySheetRowIndex(rowIndex: Int): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE sheetRowIndex = :rowIndex")
    suspend fun getAllBySheetRowIndex(rowIndex: Int): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :minTimestamp AND :maxTimestamp AND totalAmount BETWEEN :minAmount AND :maxAmount ORDER BY timestamp DESC")
    suspend fun getRecentSimilar(
        minTimestamp: Long,
        maxTimestamp: Long,
        minAmount: Double,
        maxAmount: Double
    ): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE transactions SET totalAmount = 0.0, myShare = 0.0, owedToMe = 0.0, status = 'Void', syncState = 'VOID', reviewState = 'CONFIRMED', merchant = :voidTitle WHERE id = :id")
    suspend fun voidTransaction(id: String, voidTitle: String)

    @Query("UPDATE transactions SET syncState = :syncState, sheetRowIndex = COALESCE(:sheetRowIndex, sheetRowIndex) WHERE id = :id")
    suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int? = null)

    @Query("UPDATE transactions SET reviewState = :reviewState, category = COALESCE(:category, category) WHERE id = :id")
    suspend fun updateReviewState(id: String, reviewState: String, category: String? = null)

    @Query("SELECT * FROM transactions WHERE account = :account")
    suspend fun getByAccount(account: String): List<TransactionEntity> = emptyList()

    @Query("UPDATE transactions SET account = :newName WHERE account = :oldName")
    suspend fun updateAccountName(oldName: String, newName: String): Int

    @Query("SELECT * FROM transactions WHERE category = :category")
    suspend fun getByCategory(category: String): List<TransactionEntity> = emptyList()

    @Query("UPDATE transactions SET category = :newName WHERE category = :oldName")
    suspend fun updateCategoryName(oldName: String, newName: String): Int

    @Query("UPDATE transactions SET companions = REPLACE(companions, :oldName, :newName) WHERE companions LIKE '%' || :oldName || '%'")
    suspend fun updateCompanionName(oldName: String, newName: String): Int

    @Query("SELECT * FROM transactions WHERE timestamp >= :startMillis AND timestamp < :endMillis AND UPPER(status) != 'VOID' AND reviewState != 'NEEDS_REVIEW' ORDER BY timestamp ASC")
    suspend fun getAllForYear(startMillis: Long, endMillis: Long): List<TransactionEntity>

    @Query("UPDATE transactions SET syncState = 'PENDING', sheetRowIndex = NULL WHERE id = :id")
    suspend fun resetSyncStateForRequeue(id: String)

    /** Deletes the row only if it is still void when the delete runs; false when it was not (for example undone). */
    @Transaction
    suspend fun deleteIfVoid(id: String): Boolean {
        val row = getById(id) ?: return false
        if (!row.status.equals("Void", ignoreCase = true)) return false
        deleteById(id)
        return true
    }
}

suspend fun TransactionDao.getByTabAndSheetRowIndex(tab: String, rowIndex: Int): com.financeautomation.data.local.entity.TransactionEntity? {
    if (tab.isBlank()) return getBySheetRowIndex(rowIndex)
    val candidates = getAllBySheetRowIndex(rowIndex)
    val zone = java.time.ZoneId.of("Asia/Kolkata")
    val formatter = java.time.format.DateTimeFormatter.ofPattern("MMMyy", java.util.Locale.US)
    return candidates.firstOrNull { txn ->
        java.time.Instant.ofEpochMilli(txn.timestamp).atZone(zone).format(formatter).equals(tab, ignoreCase = true)
    }
}
