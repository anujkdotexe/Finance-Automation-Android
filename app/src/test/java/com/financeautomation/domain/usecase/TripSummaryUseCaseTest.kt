package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSummaryUseCaseTest {

    private val useCase = TripSummaryUseCase()

    private fun txn(
        id: String,
        trip: String,
        total: Double,
        share: Double,
        owed: Double,
        status: TransactionStatus,
        companions: String = "Personal",
        ts: Long = 1000L
    ) = Transaction(
        id = id, timestamp = ts, dateSerial = 46330, timeSerial = 0.5, account = "UPI", merchant = "M$id",
        tripTag = trip, category = "Travel", totalAmount = total, companions = companions,
        myShare = share, owedToMe = owed, status = status
    )

    @Test
    fun netExpenditureIsPaidMinusGivenMinusReturned() {
        val rows = listOf(
            txn("1", "Goa", 4000.0, 1000.0, 3000.0, TransactionStatus.PENDING, "A, B, C", ts = 3),
            txn("2", "Goa", 2000.0, 1000.0, 1000.0, TransactionStatus.SETTLED, "A", ts = 2),
            txn("3", "Goa", 500.0, 500.0, 0.0, TransactionStatus.PERSONAL, ts = 1)
        )
        val s = useCase.forTrip("Goa", rows)
        assertEquals(6500.0, s.totalPaid, 0.001)
        assertEquals(3000.0, s.givenPending, 0.001)
        assertEquals(1000.0, s.returned, 0.001)
        assertEquals(2500.0, s.netExpenditure, 0.001)
        assertEquals(s.totalPaid - s.givenPending - s.returned, s.netExpenditure, 0.001)
        assertEquals(3, s.transactionCount)
        assertEquals(listOf("1", "2", "3"), s.transactions.map { it.id })
    }

    @Test
    fun peopleCountIsDistinctFriendsPlusYou() {
        val rows = listOf(
            txn("1", "Goa", 100.0, 25.0, 75.0, TransactionStatus.PENDING, "Asha, Ravi, Meera"),
            txn("2", "Goa", 100.0, 50.0, 50.0, TransactionStatus.PENDING, "asha"),
            txn("3", "Goa", 100.0, 100.0, 0.0, TransactionStatus.PERSONAL)
        )
        assertEquals(4, useCase.forTrip("Goa", rows).peopleCount)
        assertEquals(1, useCase.forTrip("Goa", listOf(rows[2])).peopleCount)
    }

    @Test
    fun voidRowsAndOtherTripsAreLeftOutAndNamesMatchIgnoringCase() {
        val rows = listOf(
            txn("1", "goa ", 100.0, 100.0, 0.0, TransactionStatus.PERSONAL),
            txn("2", "Goa", 900.0, 900.0, 0.0, TransactionStatus.VOID),
            txn("3", "Manali", 700.0, 700.0, 0.0, TransactionStatus.PERSONAL),
            txn("4", "", 50.0, 50.0, 0.0, TransactionStatus.PERSONAL)
        )
        val s = useCase.forTrip("GOA", rows)
        assertEquals(1, s.transactionCount)
        assertEquals(100.0, s.totalPaid, 0.001)
    }

    @Test
    fun emptyTripHasZeroesAndOnePerson() {
        val s = useCase.forTrip("Nowhere", emptyList())
        assertEquals(0, s.transactionCount)
        assertEquals(0.0, s.totalPaid, 0.001)
        assertEquals(1, s.peopleCount)
        assertTrue(s.transactions.isEmpty())
    }

    @Test
    fun byTripGroupsDistinctNamesKeyedLowerCase() {
        val rows = listOf(
            txn("1", "Goa", 100.0, 100.0, 0.0, TransactionStatus.PERSONAL),
            txn("2", "goa", 50.0, 50.0, 0.0, TransactionStatus.PERSONAL),
            txn("3", "Manali", 70.0, 70.0, 0.0, TransactionStatus.PERSONAL)
        )
        val map = useCase.byTrip(rows)
        assertEquals(setOf("goa", "manali"), map.keys)
        assertEquals(150.0, map.getValue("goa").totalPaid, 0.001)
    }

    @Test
    fun noPlaceholderTripFiguresRemainOnThePeopleAndTripsScreen() {
        val source = java.io.File("src/main/java/com/financeautomation/presentation/screens/drawer/PeopleTripsScreen.kt").readText()
        assertTrue(!source.contains("18,400"))
        assertTrue(!source.contains("peopleCount = 4"))
        assertTrue(source.contains("TripDetailSheet(summary = summaryFor(name)"))
    }

    @Test
    fun theTripModeSheetShowsRealFiguresAndNoFakeZeros() {
        val sheet = java.io.File("src/main/java/com/financeautomation/presentation/flows/TripModeFlowSheet.kt").readText()
        assertTrue(!sheet.contains("text = \"₹0.00\""))
        assertTrue(sheet.contains("tripSummary?.totalPaid"))
        assertTrue(sheet.contains("tripSummary?.netExpenditure"))
        assertTrue(sheet.contains("if (tripSummary != null) Surface("))
    }

    @Test
    fun bothOverlayHostsPassTheSummaryAndWithholdItWhileLocked() {
        val controller = java.io.File("src/main/java/com/financeautomation/presentation/overlay/OverlayController.kt").readText()
        val activity = java.io.File("src/main/java/com/financeautomation/presentation/ui/ModalBottomSheetActivity.kt").readText()
        val host = java.io.File("src/main/java/com/financeautomation/presentation/overlay/OverlayContentHost.kt").readText()
        assertTrue(host.contains("tripSummary = tripSummary"))
        assertTrue(controller.contains("tripSummary = tripSummary"))
        assertTrue(controller.contains("if (locked || activeTripTag == null) null"))
        assertTrue(activity.contains("tripSummary = tripSummary"))
        assertTrue(activity.contains("if (lockedNow || tag == null) null"))
    }
}
