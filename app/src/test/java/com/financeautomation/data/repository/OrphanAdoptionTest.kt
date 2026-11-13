package com.financeautomation.data.repository

import com.financeautomation.core.model.SyncState
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A half written row from an earlier attempt may be adopted, but a row someone typed by hand must not be. */
class OrphanAdoptionTest {

    private fun SyncHarness.sheetHasRow(account: String, description: String) {
        server.customRead = { call ->
            if (call.method == "GET" && (call.url.contains("A4:G") || call.url.contains("A4%3AG"))) {
                """{"range":"'Sheet'!A4:G","majorDimension":"ROWS","values":[[46000,0.5,"$account","$description","","Food & Drink",40.0]]}"""
            } else null
        }
    }

    private fun SyncHarness.adoptionWrites() =
        server.calls.count { it.method == "PUT" && it.url.contains("AC4") }

    @Test
    fun aHandTypedRowWithTheSameDateTimeAndAmountButAnotherDescriptionIsNotAdopted() = runBlocking {
        val h = SyncHarness()
        h.sheetHasRow(account = "SBI", description = "Something I typed myself")
        h.repo.insertTransaction(h.expense("txn_a", merchant = "Tea"))
        h.qDao.setStatusAndRetryCount(h.qDao.queue.values.single().id, com.financeautomation.data.local.entity.SyncQueueEntity.STATUS_PENDING, 1) // an earlier attempt
        h.repo.syncPendingTransactions()
        assertEquals("the manual row must not be claimed", 0, h.adoptionWrites())
        assertEquals(SyncState.SYNCED.name, h.tDao.rows["txn_a"]!!.syncState)
    }

    @Test
    fun aRowWithAnotherAccountIsNotAdopted() = runBlocking {
        val h = SyncHarness()
        h.sheetHasRow(account = "HDFC", description = "Tea")
        h.repo.insertTransaction(h.expense("txn_a", merchant = "Tea"))
        h.qDao.setStatusAndRetryCount(h.qDao.queue.values.single().id, com.financeautomation.data.local.entity.SyncQueueEntity.STATUS_PENDING, 1) // an earlier attempt
        h.repo.syncPendingTransactions()
        assertEquals(0, h.adoptionWrites())
    }

    @Test
    fun aRowThatMatchesEverythingFromAnEarlierAttemptIsStillAdopted() = runBlocking {
        val h = SyncHarness()
        h.sheetHasRow(account = "SBI", description = "tea")
        h.repo.insertTransaction(h.expense("txn_a", merchant = "Tea"))
        h.qDao.setStatusAndRetryCount(h.qDao.queue.values.single().id, com.financeautomation.data.local.entity.SyncQueueEntity.STATUS_PENDING, 1) // an earlier attempt
        h.repo.syncPendingTransactions()
        assertTrue(h.adoptionWrites() >= 1)
        assertEquals(4, h.tDao.rows["txn_a"]!!.sheetRowIndex)
    }
}
