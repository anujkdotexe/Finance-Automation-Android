package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A debt delivered to a month that is already over must be carried into the months after it. */
class LateDeliveryCarryTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun tab(z: ZonedDateTime) = z.format(DateTimeFormatter.ofPattern("MMMyy", Locale.US))

    private fun carries(h: SyncHarness) = h.qDao.queue.values
        .filter { it.operationType == SyncQueueEntity.OP_CARRY_FORWARD }
        .map { AccountingRequestJsonAdapter.carryForwardRequestFromJson(it.payloadJson) }

    @Test
    fun deliveringAnOldMonthQueuesACarryIntoTheCurrentMonth() = runBlocking {
        val now = ZonedDateTime.now(zone)
        val last = now.minusMonths(1)
        val h = SyncHarness(tabs = listOf(tab(last), tab(now)))
        h.repo.insertTransaction(h.expense("txn_old", ageMillis = now.toInstant().toEpochMilli() - last.withDayOfMonth(10).toInstant().toEpochMilli()))
        assertTrue(h.repo.syncPendingTransactions() is Result.Success)
        val queued = carries(h)
        assertEquals(1, queued.size)
        assertEquals(tab(now), queued.single().targetTab)
        assertEquals(tab(last), queued.single().sourceTab)
    }

    @Test
    fun deliveringThisMonthQueuesNoCarry() = runBlocking {
        val h = SyncHarness()
        h.repo.insertTransaction(h.expense("txn_now"))
        h.repo.syncPendingTransactions()
        assertEquals(0, carries(h).size)
    }
}
