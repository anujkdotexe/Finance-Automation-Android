package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.domain.model.VoidedHistory
import com.financeautomation.domain.usecase.LivingBurn
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Clear voided history" deletes only voided rows on this phone, skips voids still on their way to the sheet. */
class ClearVoidedHistoryTest {

    private val ts = System.currentTimeMillis() - 60_000L

    private suspend fun SyncHarness.voided(id: String) {
        val row = expense(id).copy(syncState = SyncState.VOID, sheetRowIndex = 5)
        tDao.insert(TransactionEntity.fromDomain(row).copy(status = "Void", totalAmount = 0.0, myShare = 0.0, owedToMe = 0.0, merchant = "[Void] Tea"))
    }

    private suspend fun SyncHarness.active(id: String) = tDao.insert(TransactionEntity.fromDomain(expense(id, amount = 70.0)))

    private suspend fun SyncHarness.voidedIncome(id: String, status: String = "VOID") =
        iDao.insert(InflowEntity(id = id, timestamp = ts, amount = 0.0, account = "SBI", inflowType = "Income", source = "x", note = "", status = status))

    private fun item(id: String, op: String, status: String) =
        SyncQueueEntity(transactionId = id, operationType = op, payloadJson = id, status = status, retryCount = if (status == SyncQueueEntity.STATUS_FAILED) 999 else 0)

    @Test
    fun theCountsAreShownBeforeAnythingIsDeleted() = runBlocking {
        val h = SyncHarness()
        h.voided("v1"); h.voided("v2"); h.active("a1")
        h.voidedIncome("i1"); h.voidedIncome("i2", status = "CONFIRMED")
        assertEquals(VoidedHistory(transactions = 2, inflows = 1, skippedPending = 0), h.repo.previewVoidedHistory())
        assertEquals("a preview deletes nothing", 3, h.tDao.rows.size)
    }

    @Test
    fun clearingDeletesVoidedRowsAndTheirFailedItemsAndNothingElse() = runBlocking {
        val h = SyncHarness()
        h.voided("v1"); h.voided("v2"); h.active("a1"); h.voidedIncome("i1")
        h.qDao.insert(item("a1", SyncQueueEntity.OP_INSERT, SyncQueueEntity.STATUS_PENDING))
        val res = h.repo.clearVoidedHistory()
        assertEquals(Result.Success(VoidedHistory(2, 1, 0)), res)
        assertEquals(setOf("a1"), h.tDao.rows.keys)
        assertTrue(h.iDao.storage.isEmpty())
        assertEquals("only the active row's item is left", listOf("a1"), h.qDao.queue.values.map { it.transactionId })
        assertEquals(70.0, h.tDao.rows["a1"]!!.totalAmount, 0.0)
    }

    @Test
    fun aVoidStillWaitingToReachTheSheetIsSkippedAndCounted() = runBlocking {
        val h = SyncHarness()
        h.voided("v1"); h.voided("v2")
        h.qDao.insert(item("v1", SyncQueueEntity.OP_VOID, SyncQueueEntity.STATUS_PENDING))
        h.voidedIncome("i1")
        h.qDao.insert(item("i1", SyncQueueEntity.OP_VOID_INFLOW, SyncQueueEntity.STATUS_PENDING))
        val res = h.repo.clearVoidedHistory() as Result.Success
        assertEquals(VoidedHistory(transactions = 1, inflows = 0, skippedPending = 2), res.data)
        assertNotNull("the pending void stays so the sheet is corrected", h.tDao.rows["v1"])
        assertNotNull(h.iDao.storage["i1"])
        assertNull(h.tDao.rows["v2"])
        assertEquals(2, h.qDao.queue.size)
    }

    @Test
    fun nothingToClearDoesNothing() = runBlocking {
        val h = SyncHarness()
        h.active("a1")
        val preview = h.repo.previewVoidedHistory()
        assertEquals(0, preview.deletable)
        assertEquals(Result.Success(VoidedHistory(0, 0, 0)), h.repo.clearVoidedHistory())
        assertEquals(1, h.tDao.rows.size)
    }

    @Test
    fun theSheetIsNeverTouched() = runBlocking {
        val h = SyncHarness()
        h.voided("v1"); h.voidedIncome("i1")
        h.repo.clearVoidedHistory()
        assertTrue(h.server.calls.isEmpty())
    }

    @Test
    fun totalsDoNotChange() = runBlocking {
        val h = SyncHarness()
        h.voided("v1"); h.active("a1")
        val window = LivingBurn.monthWindow(java.time.YearMonth.now().year, java.time.YearMonth.now().monthValue)
        fun burn() = LivingBurn.total(h.tDao.rows.values.map { it.toDomain() }.map { it.copy(reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED) }, window, emptySet())
        val before = burn()
        h.repo.clearVoidedHistory()
        assertEquals(before, burn(), 0.0)
        assertEquals(70.0, burn(), 0.0)
    }

    @Test
    fun reconcileDoesNotBringBackAVoidedSheetRow() = runBlocking {
        val h = SyncHarness()
        h.voided("v1")
        h.repo.clearVoidedHistory()
        val tab = ScriptedSheetsServer.currentTab()
        h.server.customRead = { call ->
            if (call.url.contains("values:batchGet")) {
                """{"valueRanges":[
                    {"range":"'$tab'!A4:K","majorDimension":"ROWS","values":[
                        [46000,0.5,"SBI","[Void] Tea","","Food & Drink",0,"Personal",0,0,"Void"],
                        [46000,0.6,"SBI","Lunch","","Food & Drink",0,"Personal",0,0,"Void"],
                        [46000,0.7,"SBI","Bus","","Transport",30,"Personal",30,0,"Personal"]
                    ]},
                    {"range":"'$tab'!AC4:AC","majorDimension":"ROWS","values":[["v1"],["v2"],["b1"]]},
                    {"range":"'$tab'!M4:T","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AD4:AD","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!V4:AB","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AE4:AE","majorDimension":"ROWS","values":[]}
                ]}"""
            } else null
        }
        h.repo.reconcileWithSheet()
        assertNull("a voided row is not imported", h.tDao.rows["v1"])
        assertNull("a zero amount void row is not imported", h.tDao.rows["v2"])
        assertNotNull("a live row still is", h.tDao.rows["b1"])
    }

    @Test
    fun theButtonIsDisabledWhenThereIsNothingToClearAndLivesInBothPlaces() {
        val dialog = File("src/main/java/com/financeautomation/presentation/components/ClearVoidedHistory.kt").readText()
        assertTrue(dialog.contains("enabled = counts.deletable > 0"))
        assertTrue(dialog.contains("R.string.clear_voided_warning"))
        val txns = File("src/main/java/com/financeautomation/presentation/screens/TransactionsScreen.kt").readText()
        val prefs = File("src/main/java/com/financeautomation/presentation/screens/drawer/PreferencesScreen.kt").readText()
        assertTrue(txns.contains("ClearVoidedHistoryHost("))
        assertTrue(prefs.contains("ClearVoidedHistoryHost("))
        val strings = File("src/main/res/values/strings.xml").readText()
        val warning = strings.substringAfter("name=\"clear_voided_warning\">").substringBefore("</string>")
        assertTrue(warning.contains("cannot be undone"))
        assertTrue(warning.contains("Google Sheet is not changed"))
        assertFalse(warning.any { it.code in 0x1F300..0x1FAFF })
    }
}
