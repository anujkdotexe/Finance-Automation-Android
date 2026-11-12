package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.TableType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Reproduction test for BUG-009:
 * Verifies the accounting behaviour when an unintended soft-keyboard phantom input
 * turns 50.0 into 5099.0 versus the expected 50.0 settlement.
 */
class SettlementWaterfallReproductionTest {

    private val useCase = SettlementWaterfallUseCase()

    @Test
    fun testBug009_excessCreditWaterfall_5099Versus50() {
        // Arrange: Lunch debit with Charlie where remaining owed is 37.5
        val lunchDebit = LedgerDebit(
            tab = "Nov26",
            row = 4,
            uuid = "txn_3e23297a7e84",
            total = 75.0,
            owed = 37.5,
            status = "Pending",
            companions = "Charlie",
            dateSerial = 46340,
            timeSerial = 0.5,
            account = "SBI",
            item = "Lunch",
            category = "Food & Drink",
            myShare = 37.5,
            tripTag = ""
        )

        // Case A: Buggy payload 5099.0 (caused by soft keyboard phantom keypresses appending 99)
        val plan5099 = useCase(
            settler = "Charlie",
            amount = 5099.0,
            creditDateSerial = 46340,
            creditTimeSerial = 0.5,
            account = "SBI",
            inflowType = "Friend Settlement",
            reason = "Settlement from Charlie",
            tabName = "Nov26",
            allDebits = listOf(lunchDebit)
        )

        // Lunch is completely settled
        assertEquals(1, plan5099.settled.size)
        assertEquals(0.0, plan5099.settled[0].remaining, 0.001)
        assertEquals(37.5, plan5099.settled[0].applied, 0.001)

        // Excess credit payable of 5061.5 is created (5099 - 37.5 = 5061.5)
        assertEquals(5061.5, plan5099.excess, 0.001)
        val payableAppend = plan5099.appends.find { it.table == TableType.PAYABLE }
        assertNotNull("Expected excess credit payable append", payableAppend)
        assertEquals("Charlie", payableAppend!!.cells[2])
        assertEquals("Excess settlement credit", payableAppend.cells[3])
        assertEquals(5061.5, payableAppend.cells[4] as Double, 0.001)

        // Case B: Correct payload 50.0 (clean input without soft keyboard leakage)
        val plan50 = useCase(
            settler = "Charlie",
            amount = 50.0,
            creditDateSerial = 46340,
            creditTimeSerial = 0.5,
            account = "SBI",
            inflowType = "Friend Settlement",
            reason = "Settlement from Charlie",
            tabName = "Nov26",
            allDebits = listOf(lunchDebit)
        )

        assertEquals(1, plan50.settled.size)
        assertEquals(0.0, plan50.settled[0].remaining, 0.001)
        assertEquals(37.5, plan50.settled[0].applied, 0.001)

        // Excess credit payable of 12.5 is created (50 - 37.5 = 12.5)
        assertEquals(12.5, plan50.excess, 0.001)
        val payableAppend50 = plan50.appends.find { it.table == TableType.PAYABLE }
        assertNotNull(payableAppend50)
        assertEquals("Charlie", payableAppend50!!.cells[2])
        assertEquals("Excess settlement credit", payableAppend50.cells[3])
        assertEquals(12.5, payableAppend50.cells[4] as Double, 0.001)
    }
}
