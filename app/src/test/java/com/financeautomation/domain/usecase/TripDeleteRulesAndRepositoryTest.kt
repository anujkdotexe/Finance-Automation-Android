package com.financeautomation.domain.usecase

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.data.repository.TripTagRepositoryImpl
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Proves trip deletion rules through production wiring:
 * - Active trip deletion is refused with a clear message ("End the trip before removing it").
 * - Ended trip deletion succeeds and archives the trip.
 * - Crucially, transactions tagged with the trip remain completely untouched.
 */
class TripDeleteRulesAndRepositoryTest {

    private class MemoryTripTagDao : TripTagDao {
        val rows = ConcurrentHashMap<String, TripTagEntity>()
        override fun getTripTagsFlow(): Flow<List<TripTagEntity>> = flowOf(rows.values.toList())
        override fun getActiveTripTagsFlow(): Flow<List<TripTagEntity>> = flowOf(rows.values.filter { !it.isArchived })
        override suspend fun getAllTripTags() = rows.values.toList()
        override suspend fun getActiveTripTags() = rows.values.filter { !it.isArchived }
        override suspend fun getById(id: String) = rows[id]
        override suspend fun getByName(name: String) = rows.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun getActiveTag() = rows.values.firstOrNull { it.isActive && !it.isArchived }
        override fun getActiveTagFlow(): Flow<TripTagEntity?> = flowOf(rows.values.firstOrNull { it.isActive && !it.isArchived })
        override suspend fun clearActiveFlags(updatedAt: Long) { rows.values.forEach { rows[it.id] = it.copy(isActive = false) } }
        override suspend fun setActiveFlag(id: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(isActive = true) } }
        override suspend fun count() = rows.size
        override suspend fun insert(tag: TripTagEntity) { rows[tag.id] = tag }
        override suspend fun insertAll(tags: List<TripTagEntity>) { tags.forEach { insert(it) } }
        override suspend fun update(tag: TripTagEntity) { rows[tag.id] = tag }
        override suspend fun delete(tag: TripTagEntity) { rows.remove(tag.id) }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun archiveTag(id: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(isArchived = true, isActive = false) } }
        override suspend fun unarchiveTag(id: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(isArchived = false) } }
        override suspend fun getDirtyTripTags() = rows.values.filter { it.dirty == 1 }
        override suspend fun markClean(ids: List<String>) {}
    }

    private class MemoryTransactionRepository(
        val transactions: MutableList<Transaction> = mutableListOf()
    ) : TransactionRepository {
        override fun getTransactions(): Flow<List<Transaction>> = flowOf(transactions.toList())
        override fun getTransactionsByTrip(tripTag: String): Flow<List<Transaction>> =
            flowOf(transactions.filter { it.tripTag.equals(tripTag, ignoreCase = true) })
        override suspend fun getTransactionById(id: String): Transaction? = transactions.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction) { transactions.add(transaction) }
        override suspend fun updateTransaction(transaction: Transaction) {
            val idx = transactions.indexOfFirst { it.id == transaction.id }
            if (idx != -1) transactions[idx] = transaction
        }
        override suspend fun deleteTransaction(id: String) { transactions.removeIf { it.id == id } }
        override suspend fun markReviewed(id: String) {}
        override suspend fun markVoid(id: String) {}
        override suspend fun settleDebtsForPerson(name: String) {}
        override suspend fun clearTripTag(tripTag: String) {
            // Note: removeTripTag must NOT call clearTripTag!
            for (i in transactions.indices) {
                if (transactions[i].tripTag.equals(tripTag, ignoreCase = true)) {
                    transactions[i] = transactions[i].copy(tripTag = "")
                }
            }
        }
        override suspend fun getUnsyncedTransactions(): List<Transaction> = emptyList()
        override suspend fun updateSyncState(id: String, syncState: SyncState, sheetRowIndex: Int?) {}
    }

    @Test
    fun activeTripDeletionIsRefusedByProductionRepository() = runBlocking {
        val dao = MemoryTripTagDao()
        dao.insert(TripTagEntity(id = "trip_1", name = "Goa", isActive = true, isArchived = false))
        val repo = TripTagRepositoryImpl(tripTagDao = dao, ioDispatcher = Dispatchers.Unconfined)

        val result = repo.removeTripTag("Goa")
        assertTrue("Active trip removal must fail", result is Result.Error)
        val errorMsg = (result as Result.Error).exception.message.orEmpty()
        assertTrue("Error message must advise ending the trip", errorMsg.contains("active trip") && errorMsg.contains("End the trip"))
        assertFalse("Active trip must NOT be archived in DAO", dao.rows["trip_1"]!!.isArchived)
        assertTrue("Trip must remain active", dao.rows["trip_1"]!!.isActive)
    }

    @Test
    fun endedTripDeletionSucceedsAndLeavesTransactionsUntouched() = runBlocking {
        val dao = MemoryTripTagDao()
        dao.insert(TripTagEntity(id = "trip_2", name = "Manali", isActive = false, isArchived = false))
        val repo = TripTagRepositoryImpl(tripTagDao = dao, ioDispatcher = Dispatchers.Unconfined)

        val txn1 = Transaction(
            id = "txn_101", timestamp = 1000L, dateSerial = 46330, timeSerial = 0.5, account = "UPI",
            merchant = "Hotel Snow", tripTag = "Manali", category = "Travel", totalAmount = 5000.0,
            companions = "Asha", myShare = 2500.0, owedToMe = 2500.0, status = TransactionStatus.PENDING,
            reviewState = ReviewState.CONFIRMED
        )
        val txn2 = Transaction(
            id = "txn_102", timestamp = 2000L, dateSerial = 46331, timeSerial = 0.5, account = "UPI",
            merchant = "Dinner", tripTag = "Manali", category = "Food", totalAmount = 1200.0,
            companions = "Personal", myShare = 1200.0, owedToMe = 0.0, status = TransactionStatus.PERSONAL,
            reviewState = ReviewState.CONFIRMED
        )
        val txnRepo = MemoryTransactionRepository(mutableListOf(txn1, txn2))

        val result = repo.removeTripTag("Manali")
        assertTrue("Ended trip removal must succeed", result is Result.Success)
        assertTrue("Trip must be marked archived in DAO", dao.rows["trip_2"]!!.isArchived)

        // Transactions must stay untouched: same count, same tripTag, same amounts
        val remainingTxns = txnRepo.transactions
        assertEquals(2, remainingTxns.size)
        assertEquals("Manali", remainingTxns[0].tripTag)
        assertEquals("Manali", remainingTxns[1].tripTag)
        assertEquals(5000.0, remainingTxns[0].totalAmount, 0.001)
        assertEquals(1200.0, remainingTxns[1].totalAmount, 0.001)
    }
}
