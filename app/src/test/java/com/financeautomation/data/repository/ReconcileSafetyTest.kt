package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconcile copies the sheet onto local rows; it must never destroy a change the sheet has not received yet. */
class ReconcileSafetyTest {

    /** A snapshot with one debit row at sheet row 4. */
    private fun snapshot(tab: String, description: String, category: String, amount: Double, uuid: String): String =
        """{"valueRanges":[
            {"range":"'$tab'!A4:K","majorDimension":"ROWS","values":[[46000,0.5,"SBI","$description","","$category",$amount,"Personal",$amount,0,"Personal"]]},
            {"range":"'$tab'!AC4:AC","majorDimension":"ROWS","values":[["$uuid"]]},
            {"range":"'$tab'!M4:T","majorDimension":"ROWS","values":[]},
            {"range":"'$tab'!AD4:AD","majorDimension":"ROWS","values":[]},
            {"range":"'$tab'!V4:AB","majorDimension":"ROWS","values":[]},
            {"range":"'$tab'!AE4:AE","majorDimension":"ROWS","values":[]}
        ]}"""

    private fun SyncHarness.serveSnapshot(description: String, category: String, amount: Double, uuid: String) {
        val tab = ScriptedSheetsServer.currentTab()
        server.customRead = { call ->
            if (call.url.contains("values:batchGet")) snapshot(tab, description, category, amount, uuid) else null
        }
    }

    private suspend fun SyncHarness.saveSyncedRow(id: String, category: String) {
        val synced = expense(id).copy(category = category, syncState = SyncState.SYNCED, sheetRowIndex = 4)
        tDao.insert(com.financeautomation.data.local.entity.TransactionEntity.fromDomain(synced))
    }

    @Test
    fun aSyncedRowWithNothingQueuedStillTakesTheSheetValues() = runBlocking {
        val h = SyncHarness()
        h.saveSyncedRow("txn_a", category = "Travel")
        h.serveSnapshot("Tea", "Food & Drink", 40.0, uuid = "txn_a")
        assertTrue(h.repo.reconcileWithSheet() is Result.Success)
        assertEquals("Food & Drink", h.tDao.rows["txn_a"]!!.category)
    }

    @Test
    fun aRowWithAQueuedChangeKeepsItsLocalValues() = runBlocking {
        for (status in listOf(SyncQueueEntity.STATUS_PENDING, SyncQueueEntity.STATUS_FAILED)) {
            val h = SyncHarness()
            h.saveSyncedRow("txn_a", category = "Travel")
            val retries = if (status == SyncQueueEntity.STATUS_FAILED) 999 else 0
            h.qDao.insert(
                SyncQueueEntity(
                    transactionId = "txn_a", operationType = SyncQueueEntity.OP_UPDATE, payloadJson = "txn_a",
                    status = status, retryCount = retries
                )
            )
            h.serveSnapshot("Tea", "Food & Drink", 40.0, uuid = "txn_a")
            h.repo.reconcileWithSheet()
            assertEquals("$status: the local edit must survive", "Travel", h.tDao.rows["txn_a"]!!.category)
            assertEquals("$status: queue row kept", 1, h.qDao.queue.size)
        }
    }

    @Test
    fun aRowWithAnUnsyncedLocalEditKeepsItsLocalValues() = runBlocking {
        val h = SyncHarness()
        val pending = h.expense("txn_a").copy(category = "Travel", syncState = SyncState.PENDING, sheetRowIndex = 4)
        h.tDao.insert(com.financeautomation.data.local.entity.TransactionEntity.fromDomain(pending))
        h.serveSnapshot("Tea", "Food & Drink", 40.0, uuid = "txn_a")
        h.repo.reconcileWithSheet()
        assertEquals("Travel", h.tDao.rows["txn_a"]!!.category)
        assertEquals(SyncState.PENDING.name, h.tDao.rows["txn_a"]!!.syncState)
    }

    // S-5: a non-blank uuid that is unknown here is a different transaction, never a row number match

    @Test
    fun anUnknownSheetUuidIsNeverMergedIntoALocalRowByRowNumber() = runBlocking {
        val h = SyncHarness()
        h.saveSyncedRow("txn_local", category = "Travel")
        h.qDao.insert(SyncQueueEntity(transactionId = "txn_local", operationType = SyncQueueEntity.OP_UPDATE, payloadJson = "txn_local"))
        h.serveSnapshot("Bus", "Transport", 70.0, uuid = "txn_from_sheet")
        h.repo.reconcileWithSheet()
        assertNotNull("the local row keeps its id", h.tDao.rows["txn_local"])
        assertEquals("Travel", h.tDao.rows["txn_local"]!!.category)
        assertNotNull("the sheet row is imported as its own transaction", h.tDao.rows["txn_from_sheet"])
        assertEquals("the queue still points at the local row", "txn_local", h.qDao.queue.values.single().transactionId)
    }

    @Test
    fun aBlankSheetUuidStillMatchesTheLocalRowByRowNumber() = runBlocking {
        val h = SyncHarness()
        h.saveSyncedRow("txn_local", category = "Travel")
        val tab = ScriptedSheetsServer.currentTab()
        h.server.customRead = { call ->
            if (call.url.contains("values:batchGet")) snapshot(tab, "Tea", "Food & Drink", 40.0, uuid = "") else null
        }
        h.repo.reconcileWithSheet()
        assertEquals("Food & Drink", h.tDao.rows["txn_local"]!!.category)
        assertNull(h.tDao.rows["sheet_${tab}_4"])
    }
}
