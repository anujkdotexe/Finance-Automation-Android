package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** Undo for every kind of id the result pill can offer: expense, income, payable and repayment. */
class UndoRecentSaveTest {

    private val ts = System.currentTimeMillis() - 5_000L

    private fun SyncHarness.ops() = qDao.queue.values.map { it.operationType }

    // expense

    @Test
    fun undoOfAnUnsyncedExpenseDropsItWithoutAnySheetWrite() = runBlocking {
        val h = SyncHarness()
        h.repo.insertTransaction(h.expense("txn_a"))
        assertTrue(h.repo.undoRecentSave("txn_a") is Result.Success)
        assertTrue(h.qDao.queue.isEmpty())
        assertEquals(SyncState.VOID.name, h.tDao.rows["txn_a"]!!.syncState)
    }

    @Test
    fun undoOfASyncedExpenseQueuesAVoidAndRequestsASync() = runBlocking {
        val h = SyncHarness()
        h.tDao.insert(TransactionEntity.fromDomain(h.expense("txn_a").copy(syncState = SyncState.SYNCED, sheetRowIndex = 6)))
        assertTrue(h.repo.undoRecentSave("txn_a") is Result.Success)
        assertEquals(listOf(SyncQueueEntity.OP_VOID), h.ops())
        assertEquals(1, h.syncRequests)
    }

    // payable and repayment

    @Test
    fun undoOfAPayableThatHasNotSyncedYetRemovesItFromTheQueue() = runBlocking {
        val h = SyncHarness()
        val id = (h.repo.logPayable("Asha", 100.0, "lunch", ts) as Result.Success).data
        assertTrue(id.startsWith("pay_"))
        assertTrue(h.repo.undoRecentSave(id) is Result.Success)
        assertTrue("nothing left to sync", h.qDao.queue.isEmpty())
    }

    @Test
    fun undoOfARepaymentThatHasNotSyncedYetRemovesItFromTheQueue() = runBlocking {
        val h = SyncHarness()
        val id = (h.repo.logRepayment("Asha", 50.0, null, ts) as Result.Success).data
        assertTrue(id.startsWith("rep_"))
        assertTrue(h.repo.undoRecentSave(id) is Result.Success)
        assertTrue(h.qDao.queue.isEmpty())
    }

    @Test
    fun undoOfAPayableAlreadyOnTheSheetFailsClearlyAndChangesNothing() = runBlocking {
        val h = SyncHarness()
        val id = (h.repo.logPayable("Asha", 100.0, "lunch", ts) as Result.Success).data
        h.qDao.deleteByTransactionId(id) // the sync delivered it
        val res = h.repo.undoRecentSave(id)
        assertTrue(res is Result.Error)
        assertTrue((res as Result.Error).exception.message!!.contains("already", ignoreCase = true))
        assertTrue(h.qDao.queue.isEmpty())
    }

    @Test
    fun theResultPillOffersNoUndoForPayablesAndRepayments() {
        val src = java.io.File("src/main/java/com/financeautomation/presentation/overlay/OverlayActionDispatcher.kt").readText()
        val payable = src.substringAfter("fun confirmPayable").substringBefore("fun confirmRepaid")
        val repaid = src.substringAfter("fun confirmRepaid")
        assertFalse(payable.contains("undoRecentSave"))
        assertFalse(repaid.contains("undoRecentSave"))
    }

    // income

    private suspend fun SyncHarness.income(): String {
        val id = (repo.logCredit("Asha", 500.0, emptyList(), "SBI", "Income", "gift", ts) as Result.Success).data
        syncRequests = 0
        return id
    }

    @Test
    fun undoOfAnIncomeThatHasNotSyncedYetRemovesItFromTheQueue() = runBlocking {
        val h = SyncHarness()
        val id = h.income()
        assertTrue(h.repo.undoRecentSave(id) is Result.Success)
        assertTrue(h.qDao.queue.isEmpty())
        assertEquals("VOID", h.iDao.storage[id]!!.status)
    }

    @Test
    fun undoOfAnIncomeAfterItSyncedQueuesAVoidSoTheSheetIsCorrected() = runBlocking {
        val h = SyncHarness()
        val id = h.income()
        h.qDao.deleteByTransactionId(id) // the sync delivered the credit
        assertTrue(h.repo.undoRecentSave(id) is Result.Success)
        assertEquals("VOID", h.iDao.storage[id]!!.status)
        assertEquals(listOf(SyncQueueEntity.OP_VOID_INFLOW), h.ops())
        assertEquals(id, h.qDao.queue.values.single().transactionId)
        assertEquals(1, h.syncRequests)
    }

    @Test
    fun voidingAnIncomeFromTheListAlsoQueuesTheSheetCorrection() = runBlocking {
        val h = SyncHarness()
        val id = h.income()
        h.qDao.deleteByTransactionId(id)
        assertTrue(h.repo.voidTransaction(id) is Result.Success)
        assertEquals(listOf(SyncQueueEntity.OP_VOID_INFLOW), h.ops())
    }

    // concurrency

    @Test
    fun undoWaitsForASyncThatIsAlreadyRunning() = runBlocking {
        val h = SyncHarness()
        h.repo.insertTransaction(h.expense("txn_a"))
        val syncStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blocked = false
        h.server.fail = { _, _ ->
            if (!blocked) {
                blocked = true
                syncStarted.countDown()
                release.await()
            }
            null
        }
        val sync = async(Dispatchers.IO) { h.repo.syncPendingTransactions() }
        syncStarted.await()
        val undo = async(Dispatchers.IO) { h.repo.undoRecentSave("txn_a") }
        delay(400)
        val ranEarly = undo.isCompleted
        release.countDown() // always release, or a failing assertion would leave the sync thread blocked forever
        assertFalse("undo must not run while a sync is in flight", ranEarly)
        sync.await()
        assertTrue(undo.await() is Result.Success)
        // The sync delivered the row first, so the undo had to queue a void for it
        assertNotNull(h.tDao.rows["txn_a"])
        assertEquals(listOf(SyncQueueEntity.OP_VOID), h.ops())
    }
}
