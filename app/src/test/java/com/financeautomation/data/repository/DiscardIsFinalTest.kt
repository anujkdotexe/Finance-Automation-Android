package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A void for a row that is not on the sheet is done, and a discarded queue item never comes back. */
class DiscardIsFinalTest {

    /** An old test row: voided here, a void queued, but the new workbook has no such row. */
    private suspend fun SyncHarness.voidedOldRow(id: String, status: String = SyncQueueEntity.STATUS_PENDING, retries: Int = 0) {
        val row = expense(id).copy(syncState = SyncState.VOID, sheetRowIndex = 5)
        tDao.insert(TransactionEntity.fromDomain(row).copy(status = "Void", totalAmount = 0.0, myShare = 0.0, owedToMe = 0.0, merchant = "[Void] Tea"))
        qDao.insert(
            SyncQueueEntity(
                transactionId = id, operationType = SyncQueueEntity.OP_VOID, payloadJson = id,
                status = status, retryCount = retries
            )
        )
    }

    @Test
    fun aVoidAimedAtARowThatIsNotOnTheSheetCountsAsDone() = runBlocking {
        val h = SyncHarness()
        h.voidedOldRow("txn_old")
        val res = h.repo.syncPendingTransactions()
        assertTrue(res is Result.Success)
        assertTrue("nothing left to void, so nothing fails", h.qDao.queue.isEmpty())
        assertEquals(SyncState.VOID.name, h.tDao.rows["txn_old"]!!.syncState)
        assertEquals(ReviewState.CONFIRMED.name, h.tDao.rows["txn_old"]!!.reviewState)
    }

    @Test
    fun aFailedLookupIsNotMistakenForAMissingRow() = runBlocking {
        val h = SyncHarness()
        h.voidedOldRow("txn_old")
        h.server.fail = { call, _ -> if (call.method == "GET" && call.url.contains("/values/")) 500 to "boom" else null }
        h.repo.syncPendingTransactions()
        val item = h.qDao.queue.values.single()
        assertEquals("a server error is retried, not treated as done", 1, item.retryCount)
    }

    @Test
    fun discardingAFailedVoidRemovesItForGoodEvenThroughStartupAndASecondSync() = runBlocking {
        val h = SyncHarness()
        h.voidedOldRow("txn_old", status = SyncQueueEntity.STATUS_FAILED, retries = 999)
        // a duplicate left by tapping Void twice
        h.qDao.insert(SyncQueueEntity(transactionId = "txn_old", operationType = SyncQueueEntity.OP_VOID, payloadJson = "txn_old", status = SyncQueueEntity.STATUS_FAILED, retryCount = 999))
        val first = h.qDao.queue.values.first()
        assertTrue(h.repo.discardQueueItem(first.id) is Result.Success)
        assertTrue("both copies of the same void are gone", h.qDao.queue.isEmpty())
        assertEquals("a voided row stays voided", SyncState.VOID.name, h.tDao.rows["txn_old"]!!.syncState)

        // what the app does at start, then a second sync
        h.repo.syncPendingTransactions()
        h.repo.reconcileWithSheet()
        h.repo.syncPendingTransactions(userInitiated = true)
        assertTrue("nothing recreates it", h.qDao.queue.isEmpty())
        assertTrue(h.qDao.getFailedQueueFlow().first().isEmpty())
    }

    @Test
    fun discardingAFailedInsertVoidsTheRowAndDoesNotRequeueIt() = runBlocking {
        val h = SyncHarness()
        h.tDao.insert(TransactionEntity.fromDomain(h.expense("txn_new")))
        val id = h.qDao.insert(SyncQueueEntity(transactionId = "txn_new", operationType = SyncQueueEntity.OP_INSERT, payloadJson = "txn_new", status = SyncQueueEntity.STATUS_FAILED, retryCount = 999))
        assertTrue(h.repo.discardQueueItem(id) is Result.Success)
        assertTrue(h.qDao.queue.isEmpty())
        assertEquals("Void", h.tDao.rows["txn_new"]!!.status)
        h.repo.syncPendingTransactions()
        assertTrue(h.qDao.queue.isEmpty())
    }
}
