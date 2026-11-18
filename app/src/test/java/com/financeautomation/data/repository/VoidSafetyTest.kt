package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.domain.model.VoidedHistory
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A void that never reached the sheet must not let the live sheet row come back as a second expense. */
class VoidSafetyTest {

    private fun tab(z: ZonedDateTime) = z.format(DateTimeFormatter.ofPattern("MMMyy", Locale.US))
    private val now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
    private val thisTab = tab(now)
    private val lastTab = tab(now.minusMonths(1))

    private suspend fun SyncHarness.voidedRow(id: String) {
        val row = expense(id, amount = 80.0).copy(syncState = SyncState.VOID, sheetRowIndex = 5)
        tDao.insert(TransactionEntity.fromDomain(row).copy(status = "Void", totalAmount = 0.0, myShare = 0.0, owedToMe = 0.0, merchant = "[Void] Tea"))
    }

    private fun failedVoid(id: String) = SyncQueueEntity(
        transactionId = id, operationType = SyncQueueEntity.OP_VOID, payloadJson = id,
        status = SyncQueueEntity.STATUS_FAILED, retryCount = 999
    )

    /** The sheet still shows the voided expense as a normal, live row. */
    private fun SyncHarness.sheetShowsLive(id: String) {
        server.customRead = { call ->
            if (call.url.contains("values:batchGet")) {
                """{"valueRanges":[
                    {"range":"'$thisTab'!A4:L","majorDimension":"ROWS","values":[[46000,0.5,"SBI","Tea","","Food & Drink",80,"Personal",80,0,"Personal",""]]},
                    {"range":"'$thisTab'!AD4:AD","majorDimension":"ROWS","values":[["$id"]]},
                    {"range":"'$thisTab'!N4:U","majorDimension":"ROWS","values":[]},
                    {"range":"'$thisTab'!AE4:AE","majorDimension":"ROWS","values":[]},
                    {"range":"'$thisTab'!W4:AC","majorDimension":"ROWS","values":[]},
                    {"range":"'$thisTab'!AF4:AF","majorDimension":"ROWS","values":[]}
                ]}"""
            } else null
        }
    }

    // clear

    @Test
    fun clearKeepsAVoidedRowWhoseVoidFailedAndCountsItAsSkipped() = runBlocking {
        val h = SyncHarness()
        h.voidedRow("v_failed"); h.voidedRow("v_done")
        h.qDao.insert(failedVoid("v_failed"))
        val res = h.repo.clearVoidedHistory() as Result.Success
        assertEquals(VoidedHistory(transactions = 1, inflows = 0, skippedPending = 1), res.data)
        assertNotNull(h.tDao.rows["v_failed"])
        assertTrue(h.tDao.rows["v_done"] == null)
        assertEquals("its queue item stays so the void is retried", 1, h.qDao.queue.size)
    }

    @Test
    fun clearWithAFailedVoidDoesNotLetTheLiveSheetRowRaiseTheTotal() = runBlocking {
        val h = SyncHarness()
        h.voidedRow("v_failed")
        h.qDao.insert(failedVoid("v_failed"))
        h.sheetShowsLive("v_failed")
        h.repo.clearVoidedHistory()
        h.repo.reconcileWithSheet()
        val rows = h.tDao.rows.values.filter { !it.status.equals("Void", true) }
        assertEquals("no live row appears from the sheet", 0, rows.size)
    }

    @Test
    fun clearDeletesOnlyRowsThatAreStillVoidAtDeleteTime() = runBlocking {
        val h = SyncHarness()
        h.voidedRow("v1")
        // the void is undone between the scan and the delete
        h.tDao.update(h.tDao.rows["v1"]!!.copy(status = "Pending", totalAmount = 80.0, myShare = 80.0))
        assertEquals(false, h.tDao.deleteIfVoid("v1"))
        assertNotNull(h.tDao.rows["v1"])
    }

    // discard

    @Test
    fun discardingAFailedVoidLetsTheLiveSheetRowComeBackInsteadOfLeavingAGhostVoid() = runBlocking {
        val h = SyncHarness()
        h.voidedRow("v_failed")
        val qid = h.qDao.insert(failedVoid("v_failed"))
        h.sheetShowsLive("v_failed")
        assertTrue(h.repo.discardQueueItem(qid) is Result.Success)
        h.repo.reconcileWithSheet()
        val row = h.tDao.rows["v_failed"]!!
        assertEquals("local row agrees with the sheet again", 80.0, row.totalAmount, 0.0)
        assertTrue(!row.status.equals("Void", true))
    }

    // finding the row

    @Test
    fun aVoidFindsTheRowInAnotherMonthTab() = runBlocking {
        val h = SyncHarness(tabs = listOf(lastTab, thisTab))
        h.voidedRow("v_old")
        h.qDao.insert(SyncQueueEntity(transactionId = "v_old", operationType = SyncQueueEntity.OP_VOID, payloadJson = "v_old"))
        h.server.customRead = { call ->
            // the uuid lives in the previous month tab only
            if (call.method == "GET" && call.url.contains("/values/") && call.url.contains("AC")) {
                val inLast = java.net.URLDecoder.decode(call.url, "UTF-8").contains("'$lastTab'")
                if (inLast) """{"range":"x","majorDimension":"ROWS","values":[["v_old"]]}""" else """{"range":"x","majorDimension":"ROWS","values":[]}"""
            } else null
        }
        h.repo.syncPendingTransactions()
        val writes = h.server.calls.filter { it.method != "GET" }
        assertTrue("the void edits are written to the tab the row is in", writes.any { it.body.contains("'$lastTab'") })
        assertTrue(h.qDao.queue.isEmpty())
    }

    @Test
    fun aVoidForARowThatIsNowhereIsDoneWithoutWriting() = runBlocking {
        val h = SyncHarness(tabs = listOf(lastTab, thisTab))
        h.voidedRow("v_gone")
        h.qDao.insert(SyncQueueEntity(transactionId = "v_gone", operationType = SyncQueueEntity.OP_VOID, payloadJson = "v_gone"))
        h.repo.syncPendingTransactions()
        assertTrue(h.server.calls.none { it.method != "GET" })
        assertTrue(h.qDao.queue.isEmpty())
    }
}
