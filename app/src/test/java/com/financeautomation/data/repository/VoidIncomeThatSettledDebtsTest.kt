package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.testutil.InMemoryInflowDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voiding an income cannot undo the debts it settled (the previous companion, owed and status cells are not kept),
 * so once such an income has reached the sheet the void is refused with a clear message. This pins that choice.
 */
class VoidIncomeThatSettledDebtsTest {

    private val ts = System.currentTimeMillis() - 5_000L

    private fun SyncHarness.sheetHasOpenDebtFor(person: String) {
        val tab = ScriptedSheetsServer.currentTab()
        server.customRead = { call ->
            if (call.url.contains("values:batchGet")) {
                """{"valueRanges":[
                    {"range":"'$tab'!A4:L","majorDimension":"ROWS","values":[[46000,0.5,"SBI","Dinner","","Food & Drink",200.0,"$person, Ben",100.0,100.0,"Pending",""]]},
                    {"range":"'$tab'!AD4:AD","majorDimension":"ROWS","values":[["txn_dinner"]]},
                    {"range":"'$tab'!N4:U","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AE4:AE","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!W4:AC","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AF4:AF","majorDimension":"ROWS","values":[]}
                ]}"""
            } else null
        }
    }

    private suspend fun SyncHarness.creditAndSync(settler: String): String {
        val id = (repo.logCredit(settler, 50.0, emptyList(), "SBI", "Income", "", ts) as Result.Success).data
        val res = repo.syncPendingTransactions()
        assertTrue("credit must sync: $res", res is Result.Success)
        assertTrue("credit left the queue", qDao.queue.values.none { it.transactionId == id })
        syncRequests = 0
        return id
    }

    @Test
    fun anIncomeThatSettledADebtCannotBeVoidedAfterItSynced() = runBlocking {
        val h = SyncHarness()
        h.sheetHasOpenDebtFor("Asha")
        val id = h.creditAndSync("Asha")
        val res = h.repo.voidTransaction(id)
        assertTrue(res is Result.Error)
        assertTrue((res as Result.Error).exception.message!!.contains("settled", ignoreCase = true))
        assertEquals("CONFIRMED", h.iDao.storage[id]!!.status)
        assertTrue("no void is queued", h.qDao.queue.isEmpty())
        // Undo is refused the same way
        assertTrue(h.repo.undoRecentSave(id) is Result.Error)
        assertEquals("CONFIRMED", h.iDao.storage[id]!!.status)
    }

    @Test
    fun anIncomeThatSettledNothingCanStillBeVoided() = runBlocking {
        val h = SyncHarness() // empty sheet: nothing to settle
        val id = h.creditAndSync("Asha")
        assertTrue(h.repo.voidTransaction(id) is Result.Success)
        assertEquals("VOID", h.iDao.storage[id]!!.status)
        assertEquals(listOf(SyncQueueEntity.OP_VOID_INFLOW), h.qDao.queue.values.map { it.operationType })
    }

    @Test
    fun anIncomeThatSettledADebtCanStillBeDroppedBeforeItSyncs() = runBlocking {
        val h = SyncHarness()
        h.sheetHasOpenDebtFor("Asha")
        val id = (h.repo.logCredit("Asha", 50.0, emptyList(), "SBI", "Income", "", ts) as Result.Success).data
        assertTrue(h.repo.undoRecentSave(id) is Result.Success)
        assertTrue(h.qDao.queue.isEmpty())
    }

    @Test
    fun theTransactionsListPathIsGuardedToo() = runBlocking {
        val dao = InMemoryInflowDao()
        val q = InMemoryQueueDao()
        dao.insert(InflowEntity(id = "cred_1", timestamp = ts, amount = 50.0, account = "SBI", inflowType = "Income", source = "Asha", note = ""))
        val repo = InflowRepositoryImpl(
            inflowDao = dao, ioDispatcher = Dispatchers.Unconfined, syncQueueDao = q,
            settledDebtsCheck = { it == "cred_1" }
        )
        val res = repo.voidInflow("cred_1")
        assertTrue(res is Result.Error)
        assertEquals("CONFIRMED", dao.storage["cred_1"]!!.status)
        assertTrue(q.queue.isEmpty())
    }
}
