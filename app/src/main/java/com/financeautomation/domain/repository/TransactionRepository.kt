package com.financeautomation.domain.repository

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing financial transactions.
 * Pure Kotlin contract with zero Android framework dependencies.
 */
interface TransactionRepository {

    /**
     * Observes all recorded transactions ordered by timestamp descending.
     */
    fun getTransactions(): Flow<List<Transaction>>

    /**
     * Observes transactions flagged as [com.financeautomation.core.model.ReviewState.NEEDS_REVIEW].
     */
    fun getNeedsReview(): Flow<List<Transaction>>

    /**
     * Retrieves a single transaction by its unique client UUID.
     */
    suspend fun getTransactionById(id: String): Transaction?

    /**
     * Persists a new transaction into local storage with zero loss.
     */
    suspend fun insertTransaction(transaction: Transaction): Result<Unit>

    /**
     * Persists a new transaction and runs [inSameTransaction] inside the same database
     * transaction as the insert, so both succeed or both roll back. The default is a
     * sequential fallback for implementations without transactional storage.
     */
    suspend fun insertTransactionWith(
        transaction: Transaction,
        inSameTransaction: suspend () -> Unit
    ): Result<Unit> {
        val result = insertTransaction(transaction)
        if (result is Result.Success) inSameTransaction()
        return result
    }

    /**
     * Updates an existing transaction in local storage.
     */
    suspend fun updateTransaction(transaction: Transaction): Result<Unit>

    /**
     * Non-destructively voids a transaction by setting shares to 0.00 and status to Void.
     */
    suspend fun voidTransaction(id: String): Result<Unit>


    /**
     * Searches for transactions within [windowMillis] of the present with matching [amount].
     */
    suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction>

    /**
     * Triggers synchronization of pending transactions to the remote master Google Sheet.
     */
    suspend fun syncPendingTransactions(): Result<Int>

    /**
     * Enqueues a credit / friend settlement operation for cloud sync.
     */
    suspend fun logCredit(
        settler: String,
        amount: Double,
        explicitRefs: List<String> = emptyList(),
        account: String,
        inflowType: String,
        reason: String,
        timestamp: Long = System.currentTimeMillis()
    ): Result<String>

    /**
     * Enqueues a payable operation for cloud sync.
     */
    suspend fun logPayable(
        creditor: String,
        amount: Double,
        whatFor: String,
        timestamp: Long = System.currentTimeMillis()
    ): Result<String>

    /**
     * Enqueues a repayment operation for cloud sync.
     */
    suspend fun logRepayment(
        creditor: String,
        amount: Double,
        explicitRef: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): Result<String>

    /**
     * Enqueues monthly debt rollover from sourceTab into targetTab.
     */
    suspend fun carryForward(
        targetTab: String,
        sourceTab: String? = null
    ): Result<Unit>

    /**
     * Enqueues year-end closing statement and JSON backup creation.
     */
    suspend fun closeYear(year: Int): Result<Unit>
}
