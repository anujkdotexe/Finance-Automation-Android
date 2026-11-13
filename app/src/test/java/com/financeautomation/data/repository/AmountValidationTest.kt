package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The repository itself refuses a zero, negative or non-finite amount; it does not rely on each sheet doing so. */
class AmountValidationTest {

    private val bad = listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
    private val ts = System.currentTimeMillis() - 5_000L

    @Test
    fun creditPayableAndRepaymentRejectBadAmountsAndQueueNothing() = runBlocking {
        for (amount in bad) {
            val h = SyncHarness()
            assertTrue("credit $amount", h.repo.logCredit("Asha", amount, emptyList(), "SBI", "Income", "", ts) is Result.Error)
            assertTrue("payable $amount", h.repo.logPayable("Asha", amount, "lunch", ts) is Result.Error)
            assertTrue("repayment $amount", h.repo.logRepayment("Asha", amount, null, ts) is Result.Error)
            assertTrue("nothing queued for $amount", h.qDao.queue.isEmpty())
            assertTrue("no inflow stored for $amount", h.iDao.storage.isEmpty())
            assertEquals(0, h.syncRequests)
        }
    }

    @Test
    fun aNormalAmountStillWorks() = runBlocking {
        val h = SyncHarness()
        assertTrue(h.repo.logCredit("Asha", 10.5, emptyList(), "SBI", "Income", "", ts) is Result.Success)
        assertTrue(h.repo.logPayable("Asha", 0.01, "x", ts) is Result.Success)
        assertTrue(h.repo.logRepayment("Asha", 99999.0, null, ts) is Result.Success)
    }
}
