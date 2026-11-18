package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rows the owner confirmed or deleted must not come back to Needs Review. Each test follows one path through the
 * repository, the queue and the sync.
 */
class NeedsReviewLifecycleTest {

    private suspend fun SyncHarness.needsReviewIds() = tDao.getNeedsReviewFlow().first().map { it.id }

    private suspend fun SyncHarness.reviewRow(id: String, delivered: Boolean = false) {
        val base = expense(id).copy(
            reviewState = ReviewState.NEEDS_REVIEW,
            syncState = if (delivered) SyncState.SYNCED else SyncState.PENDING,
            sheetRowIndex = if (delivered) 5 else null
        )
        tDao.insert(TransactionEntity.fromDomain(base))
        if (!delivered) {
            qDao.insert(SyncQueueEntity(transactionId = id, operationType = SyncQueueEntity.OP_INSERT, payloadJson = id))
        }
    }

    private suspend fun SyncHarness.confirm(id: String): Result<Unit> {
        val row = tDao.getById(id)!!.toDomain()
        return repo.updateTransaction(row.copy(reviewState = ReviewState.CONFIRMED, syncState = SyncState.PENDING))
    }

    // delete or void

    @Test
    fun voidingARowInReviewRemovesItFromReviewAndSendsNothingToTheSheet() = runBlocking {
        val h = SyncHarness()
        h.reviewRow("txn_a")
        assertEquals(listOf("txn_a"), h.needsReviewIds())
        assertTrue(h.repo.voidTransaction("txn_a") is Result.Success)
        assertFalse("voided row must leave Needs Review", "txn_a" in h.needsReviewIds())
        assertTrue("a row that never reached the sheet needs no sheet change", h.qDao.queue.isEmpty())
        assertEquals(0.0, h.tDao.rows["txn_a"]!!.totalAmount, 0.0)
        h.repo.syncPendingTransactions()
        assertTrue("nothing is written for it", h.server.calls.none { it.method != "GET" })
        assertFalse("txn_a" in h.needsReviewIds())
    }

    @Test
    fun voidingADeliveredRowInReviewQueuesTheSheetVoidAndLeavesReview() = runBlocking {
        val h = SyncHarness()
        h.reviewRow("txn_a", delivered = true)
        assertTrue(h.repo.voidTransaction("txn_a") is Result.Success)
        assertFalse("txn_a" in h.needsReviewIds())
        assertEquals(listOf(SyncQueueEntity.OP_VOID), h.qDao.queue.values.map { it.operationType })
    }

    @Test
    fun theNeedsReviewQueriesIgnoreVoidRows() {
        val dao = File("src/main/java/com/financeautomation/data/local/dao/TransactionDao.kt").readText()
        val needsReview = dao.substringAfter("fun getNeedsReviewFlow").let { dao.substringBefore("fun getNeedsReviewFlow").substringAfterLast("@Query") }
        assertTrue(needsReview.contains("UPPER(status) != 'VOID'"))
        val void = dao.substringBefore("suspend fun voidTransaction").substringAfterLast("@Query")
        assertTrue("a voided row is no longer waiting for review", void.contains("reviewState = 'CONFIRMED'"))
    }

    // confirm

    @Test
    fun confirmingARowMovesItOutOfReviewAtOnce() = runBlocking {
        val h = SyncHarness()
        h.reviewRow("txn_a", delivered = true)
        assertTrue(h.confirm("txn_a") is Result.Success)
        assertFalse("txn_a" in h.needsReviewIds())
    }

    @Test
    fun confirmingARowThatIsStillWaitingToBeInsertedDoesNotQueueASecondChange() = runBlocking {
        val h = SyncHarness()
        h.reviewRow("txn_a")
        assertTrue(h.confirm("txn_a") is Result.Success)
        assertEquals(listOf(SyncQueueEntity.OP_INSERT), h.qDao.queue.values.map { it.operationType })
        val res = h.repo.syncPendingTransactions()
        assertTrue(res is Result.Success)
        assertEquals(SyncState.SYNCED.name, h.tDao.rows["txn_a"]!!.syncState)
        assertFalse("txn_a" in h.needsReviewIds())
        assertTrue(h.qDao.queue.isEmpty())
    }

    @Test
    fun aRetryableFailureKeepsTheItemPendingAndTheRowOutOfReview() = runBlocking {
        val h = SyncHarness()
        h.reviewRow("txn_a", delivered = true)
        h.confirm("txn_a")
        h.server.fail = { call, _ ->
            if (call.method == "GET" && !call.url.contains("/values/")) 429 to SyncHarness.errorBody(429, "rateLimitExceeded") else null
        }
        repeat(SyncHarnessCap) { h.repo.syncPendingTransactions() }
        val item = h.qDao.queue.values.single()
        assertEquals(ReviewState.CONFIRMED.name, h.tDao.rows["txn_a"]!!.reviewState)
        assertFalse("txn_a" in h.needsReviewIds())
        // Below the retry cap the item is plain PENDING, not a failed item
        val h2 = SyncHarness()
        h2.reviewRow("txn_b", delivered = true)
        h2.confirm("txn_b")
        h2.server.fail = h.server.fail
        h2.repo.syncPendingTransactions()
        assertEquals(SyncQueueEntity.STATUS_PENDING, h2.qDao.queue.values.single().status)
        assertNotNull(item)
    }

    @Test
    fun aMissingSheetRowIsShownAsAFailedItemWithAReasonNotSentBackToReview() = runBlocking {
        val h = SyncHarness()
        h.reviewRow("txn_a", delivered = true)
        h.confirm("txn_a") // the sheet is empty, so the row it points at does not exist
        h.repo.syncPendingTransactions()
        val item = h.qDao.queue.values.single()
        assertEquals(SyncQueueEntity.STATUS_FAILED, item.status)
        assertNotNull("the reason is recorded", item.lastErrorKind)
        assertTrue(!item.lastErrorDetail.isNullOrBlank())
        assertEquals(ReviewState.CONFIRMED.name, h.tDao.rows["txn_a"]!!.reviewState)
        assertFalse("txn_a" in h.needsReviewIds())
    }

    // reconcile

    @Test
    fun reconcileNeverBringsBackARowTheUserVoided() = runBlocking {
        val h = SyncHarness()
        val tab = ScriptedSheetsServer.currentTab()
        h.reviewRow("txn_a", delivered = true)
        h.repo.voidTransaction("txn_a") // queues OP_VOID; the sheet still shows the old active row
        h.server.customRead = { call ->
            if (call.url.contains("values:batchGet")) {
                """{"valueRanges":[
                    {"range":"'$tab'!A4:L","majorDimension":"ROWS","values":[[46000,0.5,"SBI","Tea","","Food & Drink",40.0,"Personal",40.0,0,"Personal",""]]},
                    {"range":"'$tab'!AD4:AD","majorDimension":"ROWS","values":[["txn_a"]]},
                    {"range":"'$tab'!N4:U","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AE4:AE","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!W4:AC","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AF4:AF","majorDimension":"ROWS","values":[]}
                ]}"""
            } else null
        }
        h.repo.reconcileWithSheet()
        val row = h.tDao.rows["txn_a"]!!
        assertEquals("Void", row.status)
        assertEquals(0.0, row.totalAmount, 0.0)
        assertFalse("txn_a" in h.needsReviewIds())
    }
}

private const val SyncHarnessCap = 9
