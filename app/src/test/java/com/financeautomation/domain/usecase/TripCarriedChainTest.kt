package com.financeautomation.domain.usecase

import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a trip is still owed follows each carried debt to the row that holds it now. Invented data. */
class TripCarriedChainTest {

    private fun row(
        id: String,
        merchant: String,
        owed: Double,
        status: TransactionStatus,
        dateSerial: Int = 46300,
        total: Double = 1000.0,
        myShare: Double = 100.0
    ) = Transaction(
        id = id, timestamp = 1_000L + dateSerial, dateSerial = dateSerial, timeSerial = 0.5, account = "SBI",
        merchant = merchant, tripTag = "Goa", category = "Travel", totalAmount = total, companions = "Asha",
        myShare = myShare, owedToMe = owed, status = status, reviewState = ReviewState.CONFIRMED
    )

    @Test
    fun aDebtCarriedTwiceIsJudgedByItsLastCopy() {
        val rows = listOf(
            row("orig", "Resort", 900.0, TransactionStatus.CARRIED_FWD),
            row("hop1", "[Carried from Oct26] Resort", 900.0, TransactionStatus.CARRIED_FWD, total = 900.0, myShare = 0.0),
            row("hop2", "[Carried from Nov26] Resort", 400.0, TransactionStatus.PENDING, total = 900.0, myShare = 0.0)
        )

        val summary = TripSummaryUseCase().forTrip("Goa", rows)

        assertEquals(400.0, summary.givenPending, 0.001)
        assertEquals(500.0, summary.returned, 0.001)
    }

    @Test
    fun twoExpensesWithTheSameNameEachKeepTheirOwnCopy() {
        val rows = listOf(
            row("first", "Cab", 300.0, TransactionStatus.CARRIED_FWD, dateSerial = 46300, total = 400.0),
            row("second", "Cab", 200.0, TransactionStatus.CARRIED_FWD, dateSerial = 46301, total = 300.0),
            row("copy_first", "[Carried from Oct26] Cab", 300.0, TransactionStatus.PENDING, dateSerial = 46300, myShare = 0.0),
            row("copy_second", "[Carried from Oct26] Cab", 0.0, TransactionStatus.SETTLED, dateSerial = 46301, myShare = 0.0)
        )

        val summary = TripSummaryUseCase().forTrip("Goa", rows)

        assertEquals(300.0, summary.givenPending, 0.001)
        assertEquals(200.0, summary.returned, 0.001)
    }

    @Test
    fun oneCopyIsClaimedByOnlyOneOfTwoIdenticalOriginals() {
        // Same name, date and time; only one of them has reached the next month so far
        val originals = listOf(
            row("a", "Tea", 50.0, TransactionStatus.CARRIED_FWD),
            row("b", "Tea", 50.0, TransactionStatus.CARRIED_FWD)
        )
        val copy = row("copy", "[Carried from Oct26] Tea", 50.0, TransactionStatus.PENDING, myShare = 0.0)

        val chains = CarriedCopies.chains(originals, listOf(copy))

        assertEquals(1, chains.values.sumOf { it.size })
    }
}
