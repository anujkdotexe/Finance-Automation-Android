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

    suspend fun updateAccountName(oldName: String, newName: String): Result<Int> = Result.Success(0)

    suspend fun updateCategoryName(oldName: String, newName: String): Result<Int> = Result.Success(0)

    suspend fun updateCompanionName(oldName: String, newName: String): Result<Int> = Result.Success(0)

    /**
     * Non-destructively voids a transaction by setting shares to 0.00 and status to Void.
     */
    suspend fun voidTransaction(id: String): Result<Unit>

    /**
     * Resolves a failed sync locally without writing to the sync queue.
     * When [voidIt] is true the transaction is marked Void (amounts kept) because it never reached the sheet;
     * otherwise it is marked synced and confirmed.
     */
    suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit>

    /**
     * Undoes a just-saved transaction. If it still waits in the sync queue as an insert and has no
     * sheet row, the queue item is removed and the transaction is voided locally with no sheet write.
     * Otherwise it falls back to [voidTransaction].
     */
    suspend fun undoRecentSave(id: String): Result<Unit>

    /**
     * Searches for transactions within [windowMillis] of the present with matching [amount].
     */
    suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction>

    /**
     * Triggers synchronization of pending transactions to the remote master Google Sheet.
     */
    suspend fun syncPendingTransactions(): Result<Int>

    /** Counts the voided rows that "Clear voided history" would delete, and those it would skip. Deletes nothing. */
    suspend fun previewVoidedHistory(): com.financeautomation.domain.model.VoidedHistory =
        com.financeautomation.domain.model.VoidedHistory(0, 0, 0)

    /**
     * Permanently deletes voided transactions and voided income from this phone, with the failed queue items that only
     * concern them. Voids still waiting to reach the sheet are skipped. Never changes the sheet or a row that is not void.
     */
    suspend fun clearVoidedHistory(): Result<com.financeautomation.domain.model.VoidedHistory> =
        Result.Error(UnsupportedOperationException("clearVoidedHistory is not supported"))

    /**
     * Throws a queue item away for good: removes it (and any copy of the same operation on the same row) and puts the
     * local row in a state nothing re-queues. A discarded insert voids the row here; a discarded void stays voided.
     */
    suspend fun discardQueueItem(queueItemId: Long): Result<Unit> =
        Result.Error(UnsupportedOperationException("discardQueueItem is not supported"))

    /**
     * Same as [syncPendingTransactions]; [userInitiated] marks a tap on Sync now or pull to refresh, which may bypass
     * the minimum interval between full ledger reads (at most once a minute).
     */
    suspend fun syncPendingTransactions(userInitiated: Boolean): Result<Int> = syncPendingTransactions()

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
     * Enqueues a payable operation for cloud sync and logs the user's share to Living Burn in the chosen category.
     */
    suspend fun logPayable(
        creditor: String,
        amount: Double,
        whatFor: String,
        timestamp: Long = System.currentTimeMillis(),
        category: String = com.financeautomation.core.constants.CategoryConstants.CATEGORY_OTHER
    ): Result<String>

    suspend fun logFriendPaid(
        creditor: String,
        amount: Double,
        whatFor: String,
        timestamp: Long = System.currentTimeMillis(),
        category: String = com.financeautomation.core.constants.CategoryConstants.CATEGORY_OTHER
    ): Result<String> = logPayable(creditor, amount, whatFor, timestamp, category)

    /**
     * Enqueues a repayment operation for cloud sync.
     */
    suspend fun logRepayment(
        creditor: String,
        amount: Double,
        explicitRef: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): Result<String> = logRepayment(creditor, amount, explicitRef, timestamp, null, null)

    suspend fun logRepayment(
        creditor: String,
        amount: Double,
        explicitRef: String?,
        timestamp: Long,
        account: String?,
        note: String?
    ): Result<String> = logRepayment(creditor, amount, explicitRef, timestamp)

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

    /**
     * Reconciles all tabs across the workbook, bypassing the two-month window,
     * subject to a 10-minute cooldown.
     */
    suspend fun reconcileAllMonths(): Result<Int>

    /**
     * Requeues all local non-void, non-review transactions, inflows, and payables for [year]
     * so they are appended to a newly provisioned or replaced workbook.
     */
    suspend fun requeueYearForNewWorkbook(year: Int)
}
