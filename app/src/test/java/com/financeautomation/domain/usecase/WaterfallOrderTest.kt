package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.LedgerDebit
import org.junit.Assert.assertEquals
import org.junit.Test

/** The settlement waterfall settles the oldest debt first, whatever order the snapshot lists the tabs in. */
class WaterfallOrderTest {

    private fun debt(tab: String, row: Int, date: Int?, time: Double?, owed: Double) = LedgerDebit(
        tab = tab, row = row, uuid = "u_${tab}_$row", dateSerial = date, timeSerial = time, account = "SBI", item = "Meal",
        tripTag = "", category = "Food & Drink", total = owed * 2, companions = "Asha, Ben", myShare = owed, owed = owed, status = "Pending"
    )

    private fun settle(debts: List<LedgerDebit>, amount: Double) = SettlementWaterfallUseCase()(
        settler = "Asha", amount = amount, creditDateSerial = 46100, creditTimeSerial = 0.5, account = "SBI",
        inflowType = "Income", reason = "", tabName = "Dec26", allDebits = debts
    )

    @Test
    fun theOlderDebtIsSettledFirstEvenWhenListedAfterTheNewerOne() {
        val newer = debt("Dec26", 4, 46050, 0.5, 100.0)
        val older = debt("Nov26", 4, 46000, 0.5, 100.0)
        val plan = settle(listOf(newer, older), 50.0) // snapshot lists the newer tab first
        val touched = plan.settled.map { it.tab }
        assertEquals(listOf("Nov26"), touched)
    }

    @Test
    fun sameDayDebtsAreOrderedByTime() {
        val late = debt("Nov26", 4, 46000, 0.9, 100.0)
        val early = debt("Nov26", 5, 46000, 0.1, 100.0)
        val plan = settle(listOf(late, early), 50.0)
        assertEquals(listOf(5), plan.settled.map { it.row })
    }

    @Test
    fun rowsWithoutADateKeepTheirPlaceAfterDatedOnes() {
        val undated = debt("Nov26", 4, null, null, 100.0)
        val dated = debt("Nov26", 5, 46000, 0.5, 100.0)
        val plan = settle(listOf(undated, dated), 50.0)
        assertEquals(listOf(5), plan.settled.map { it.row })
    }
}
