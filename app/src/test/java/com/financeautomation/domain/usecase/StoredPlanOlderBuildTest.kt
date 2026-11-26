package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plan saved by an older build did not note what it replaced. Invented data. */
class StoredPlanOlderBuildTest {

    private val plan = AccountingPlan(
        edits = listOf(
            CellEdit("Oct26", 4, "AA", 400.0, "pay_oct"),
            CellEdit("Oct26", 4, "AC", "Partial", "pay_oct")
        )
    )

    private fun debt(amount: Double, status: String) = ChainRow(
        uuid = "pay_oct", tab = "Oct26", row = 4, text = "Tickets", party = "Riya", dateSerial = 46300,
        timeSerial = 0.5, amount = amount, status = status
    )

    @Test
    fun onALiveRowThatDiffersItIsSentAsBefore() {
        val outcome = StoredPlanReplay.check(plan, emptyList(), listOf(debt(500.0, "Pending")))

        assertEquals(plan, (outcome as StoredPlanReplay.Outcome.Send).plan)
    }

    @Test
    fun onACarriedRowItCannotBeProvenAndIsRefused() {
        val outcome = StoredPlanReplay.check(plan, emptyList(), listOf(debt(500.0, "Carried Fwd")))

        assertEquals(StoredPlanReplay.Outcome.Unprovable, outcome)
    }

    @Test
    fun whenTheCarriedRowAlreadyShowsTheNewAmountItsStatusIsLeftAlone() {
        val outcome = StoredPlanReplay.check(plan, emptyList(), listOf(debt(400.0, "Carried Fwd")))

        val local = (outcome as StoredPlanReplay.Outcome.AlreadyWritten).local
        assertTrue(local.edits.none { it.column == "AC" })
    }
}
