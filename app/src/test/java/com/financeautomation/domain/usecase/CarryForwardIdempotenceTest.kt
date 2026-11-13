package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.LineageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carry forward must be idempotent per source row, not per tab. */
class CarryForwardIdempotenceTest {

    private val useCase = CarryForwardUseCase()

    private fun debit(tab: String, row: Int, uuid: String, item: String, owed: Double, status: String = "Pending") = LedgerDebit(
        tab = tab, row = row, uuid = uuid, dateSerial = 46000, timeSerial = 0.5, account = "SBI", item = item,
        tripTag = "", category = "Food & Drink", total = owed * 2, companions = "Asha", myShare = owed, owed = owed, status = status
    )

    private fun payable(tab: String, row: Int, uuid: String, whatFor: String, amount: Double, status: String = "Pending") = LedgerPayable(
        tab = tab, row = row, uuid = uuid, dateIncurredSerial = 46000, timeSerial = 0.5, paidBy = "Asha", whatFor = whatFor,
        amountOwed = amount, dateRepaidSerial = null, status = status
    )

    private fun carriedTarget(vararg sources: LedgerDebit) = sources.mapIndexed { i, d ->
        debit("Dec26", 4 + i, "carried_${d.uuid}", "[Carried from Nov26] ${d.item}", d.owed)
    }

    @Test
    fun aDebtLoggedOnDay28IsCarriedExactlyOnceAfterTheDay25Run() {
        val a = debit("Nov26", 4, "txn_a", "Dinner", 100.0)
        // Day 25 run
        val first = useCase("Dec26", "Nov26", emptyList(), emptyList(), listOf(a), emptyList())
        assertEquals(1, first.plan.appends.size)
        val lineageAfterFirst = first.lineageRecords

        // Day 28: a new debt appears; A is now Carried Fwd in the source tab and its copy sits in the target
        val aNow = a.copy(status = "Carried Fwd")
        val b = debit("Nov26", 5, "txn_b", "Taxi", 60.0)
        val targetNow = carriedTarget(a)
        val second = useCase("Dec26", "Nov26", targetNow, emptyList(), listOf(aNow, b), emptyList(), lineageAfterFirst)
        assertEquals("only the new debt is carried", 1, second.plan.appends.size)
        assertTrue(second.plan.appends.single().cells[3].toString().contains("Taxi"))

        // A later run finds nothing new
        val targetLater = targetNow + debit("Dec26", 5, "carried_txn_b", "[Carried from Nov26] Taxi", 60.0)
        val third = useCase("Dec26", "Nov26", targetLater, emptyList(), listOf(aNow, b.copy(status = "Carried Fwd")), emptyList(), lineageAfterFirst + second.lineageRecords)
        assertEquals(0, third.plan.appends.size)
    }

    @Test
    fun aStaleSourceSnapshotNeverDuplicatesARowAlreadyCarried() {
        val a = debit("Nov26", 4, "txn_a", "Dinner", 100.0) // still Pending in a stale read
        val b = debit("Nov26", 5, "txn_b", "Taxi", 60.0)
        val result = useCase("Dec26", "Nov26", carriedTarget(a), emptyList(), listOf(a, b), emptyList())
        assertEquals(1, result.plan.appends.size)
        assertTrue(result.plan.appends.single().cells[3].toString().contains("Taxi"))
    }

    @Test
    fun aRowWithALineageRecordIsSkippedEvenWhenTheTargetCopyWasEdited() {
        val a = debit("Nov26", 4, "txn_a", "Dinner", 100.0)
        val lineage = listOf(LineageRecord(uuid = "carried_txn_a", carriedFromUuid = "txn_a", rootUuid = "txn_a", createdAt = 1L))
        val result = useCase("Dec26", "Nov26", emptyList(), emptyList(), listOf(a), emptyList(), lineage)
        assertEquals(0, result.plan.appends.size)
    }

    @Test
    fun payablesAreIdempotentPerRowToo() {
        val p1 = payable("Nov26", 4, "pay_1", "Lunch", 80.0)
        val p2 = payable("Nov26", 5, "pay_2", "Cab", 40.0)
        val targetPayables = listOf(payable("Dec26", 4, "carried_pay_1", "[Carried from Nov26] Lunch", 80.0))
        val result = useCase("Dec26", "Nov26", emptyList(), targetPayables, emptyList(), listOf(p1, p2))
        assertEquals(1, result.plan.appends.size)
        assertTrue(result.plan.appends.single().cells[3].toString().contains("Cab"))
    }

    @Test
    fun theFirstRunStillCarriesEverythingOpen() {
        val rows = listOf(
            debit("Nov26", 4, "txn_a", "Dinner", 100.0),
            debit("Nov26", 5, "txn_b", "Taxi", 60.0),
            debit("Nov26", 6, "txn_c", "Done", 10.0, status = "Settled")
        )
        val result = useCase("Dec26", "Nov26", emptyList(), emptyList(), rows, emptyList())
        assertEquals(2, result.plan.appends.size)
        assertEquals(2, result.lineageRecords.size)
    }
}
