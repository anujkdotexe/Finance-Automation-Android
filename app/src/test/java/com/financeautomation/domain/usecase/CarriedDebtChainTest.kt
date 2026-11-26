package com.financeautomation.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/** When a carried debt can be matched without doubt, and when it cannot. Invented data. */
class CarriedDebtChainTest {

    private fun row(tab: String, row: Int, text: String, amount: Double, status: String, party: String = "Riya") =
        ChainRow(
            uuid = "$tab-$row", tab = tab, row = row, text = text, party = party, dateSerial = 46300,
            timeSerial = 0.5, amount = amount, status = status
        )

    private val original = row("Sep26", 4, "Dinner", 500.0, "Carried Fwd")
    private val first = row("Oct26", 4, "[Carried from Sep26] Dinner", 500.0, "Carried Fwd")
    private val second = row("Nov26", 4, "[Carried from Oct26] Dinner", 500.0, "Pending")

    @Test
    fun twoHopsEndAtTheOpenRow() {
        val resolution = CarriedDebtChain.resolve(original, listOf(second, first), 500.0)

        assertEquals(CarriedResolution.Untouched(listOf(first, second)), resolution)
    }

    @Test
    fun aTwinCarriedRowInTheSameMonthMakesTheMatchUncertain() {
        val twin = original.copy(uuid = "twin", row = 5)

        val resolution = CarriedDebtChain.resolve(original, listOf(twin, first, second), 500.0)

        assertEquals(CarriedResolution.Unknown, resolution)
    }

    @Test
    fun twoRowsThatCouldBeTheCopyMakeTheMatchUncertain() {
        val other = second.copy(uuid = "other", row = 9)

        val resolution = CarriedDebtChain.resolve(original, listOf(first, second, other), 500.0)

        assertEquals(CarriedResolution.Unknown, resolution)
    }

    @Test
    fun aRepaymentAnywhereAlongTheChainCountsAsTouched() {
        val reduced = second.copy(amount = 300.0, status = "Partial")

        assertEquals(CarriedResolution.Touched, CarriedDebtChain.resolve(original, listOf(first, reduced), 500.0))
        assertEquals(
            CarriedResolution.Touched,
            CarriedDebtChain.resolve(original, listOf(first, second.copy(status = "Repaid", amount = 0.0)), 500.0)
        )
    }

    @Test
    fun aCarriedRowWithNothingAfterItIsUnknown() {
        assertEquals(CarriedResolution.Unknown, CarriedDebtChain.resolve(original, listOf(first), 500.0))
    }
}
