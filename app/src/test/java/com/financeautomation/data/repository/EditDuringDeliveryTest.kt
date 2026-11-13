package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** An edit made while the first delivery of the same row is running must not be lost. */
class EditDuringDeliveryTest {

    @Test
    fun anEditWaitsForTheRunningSyncAndThenQueuesItsOwnUpdate() = runBlocking {
        val h = SyncHarness()
        h.repo.insertTransaction(h.expense("txn_a", merchant = "Tea"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blocked = false
        h.server.fail = { _, _ ->
            if (!blocked) {
                blocked = true
                started.countDown()
                release.await()
            }
            null
        }
        val sync = async(Dispatchers.IO) { h.repo.syncPendingTransactions() }
        started.await()
        val edit = async(Dispatchers.IO) { h.repo.updateTransaction(h.expense("txn_a", merchant = "Coffee")) }
        delay(400)
        val ranEarly = edit.isCompleted
        release.countDown()
        assertFalse("the edit must wait while the row is being delivered", ranEarly)
        sync.await()
        assertTrue(edit.await() is Result.Success)
        assertEquals(
            "the edit made during delivery reaches the sheet through its own update",
            listOf(SyncQueueEntity.OP_UPDATE), h.qDao.queue.values.map { it.operationType }
        )
        assertEquals("Coffee", h.tDao.rows["txn_a"]!!.merchant)
    }

    @Test
    fun anEditBeforeAnySyncStillNeedsNoSecondItem() = runBlocking {
        val h = SyncHarness()
        h.repo.insertTransaction(h.expense("txn_a", merchant = "Tea"))
        h.repo.updateTransaction(h.expense("txn_a", merchant = "Coffee"))
        assertEquals(listOf(SyncQueueEntity.OP_INSERT), h.qDao.queue.values.map { it.operationType })
    }
}
