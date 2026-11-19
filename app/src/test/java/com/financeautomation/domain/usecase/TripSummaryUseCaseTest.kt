package com.financeautomation.domain.usecase

import com.financeautomation.core.model.ReviewState
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
        ts: Long = 1000L,
        reviewState: ReviewState = ReviewState.CONFIRMED
    ) = Transaction(
        id = id, timestamp = ts, dateSerial = 46330, timeSerial = 0.5, account = "UPI", merchant = "M$id",
        tripTag = trip, category = "Travel", totalAmount = total, companions = companions,
        myShare = share, owedToMe = owed, status = status, reviewState = reviewState
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

    @Test
    fun tripInsightsComputedCorrectly() {
        val dayMs = 24 * 60 * 60 * 1000L
        val baseTs = 1700000000000L
        val rows = listOf(
            Transaction(
                id = "1", timestamp = baseTs, dateSerial = 46330, timeSerial = 0.5, account = "UPI", merchant = "Hotel",
                tripTag = "Goa", category = "Travel", totalAmount = 6000.0, companions = "Asha, Ravi",
                myShare = 2000.0, owedToMe = 4000.0, status = TransactionStatus.PENDING,
                reviewState = ReviewState.CONFIRMED
            ),
            Transaction(
                id = "2", timestamp = baseTs + 2 * dayMs, dateSerial = 46332, timeSerial = 0.5, account = "UPI", merchant = "Dinner",
                tripTag = "Goa", category = "Food", totalAmount = 1500.0, companions = "Asha",
                myShare = 750.0, owedToMe = 750.0, status = TransactionStatus.PENDING,
                reviewState = ReviewState.CONFIRMED
            )
        )
        val s = useCase.forTrip("Goa", rows)
        assertEquals(3, s.daysCount)
        assertEquals(2750.0, s.netExpenditure, 0.001)
        assertEquals(2750.0 / 3, s.averagePerDay, 0.001)
        assertEquals("Hotel", s.biggestSingleExpense?.merchant)
        assertEquals(6000.0, s.biggestSingleExpense?.totalAmount ?: 0.0, 0.001)

        assertEquals(2, s.categoryBreakdown.size)
        val travelCat = s.categoryBreakdown.first { it.category == "Travel" }
        assertEquals(2000.0, travelCat.amount, 0.001)
        assertEquals((2000.0 / 2750.0) * 100.0, travelCat.percentage, 0.01)

        val asha = s.perFriendBreakdown.first { it.name.equals("Asha", ignoreCase = true) }
        assertEquals(2750.0, asha.owedToMe, 0.001)
        assertEquals(0.0, asha.repaid, 0.001)
        val ravi = s.perFriendBreakdown.first { it.name.equals("Ravi", ignoreCase = true) }
        assertEquals(2000.0, ravi.owedToMe, 0.001)
        assertEquals(0.0, ravi.repaid, 0.001)
    }

    @Test
    fun refundReducesTripNetExpenditureLivingBurn() {
        val rows = listOf(
            Transaction(
                id = "1", timestamp = 1000L, dateSerial = 46330, timeSerial = 0.5, account = "UPI", merchant = "Flight",
                tripTag = "Goa", category = "Travel", totalAmount = 5000.0, companions = "Personal",
                myShare = 5000.0, owedToMe = 0.0, status = TransactionStatus.PERSONAL,
                reviewState = ReviewState.CONFIRMED
            ),
            Transaction(
                id = "2", timestamp = 2000L, dateSerial = 46331, timeSerial = 0.5, account = "UPI", merchant = "Airline Refund",
                tripTag = "Goa", category = "Refund", totalAmount = 2000.0, companions = "Personal",
                myShare = 2000.0, owedToMe = 0.0, status = TransactionStatus.PERSONAL,
                reviewState = ReviewState.CONFIRMED
            )
        )
        val s = useCase.forTrip("Goa", rows)
        assertEquals(3000.0, s.netExpenditure, 0.001)
        assertEquals("Flight", s.biggestSingleExpense?.merchant)
        assertEquals(1, s.categoryBreakdown.size)
        assertEquals("Travel", s.categoryBreakdown[0].category)
        assertEquals(5000.0, s.categoryBreakdown[0].amount, 0.001)
    }

    @Test
    fun carriedForwardCopiesAreCountedOnceAndNotDuplicatedInTripSummary() {
        val original = Transaction(
            id = "orig_1", timestamp = 1000L, dateSerial = 46330, timeSerial = 0.5, account = "UPI",
            merchant = "Resort Stay", tripTag = "Goa", category = "Travel", totalAmount = 6000.0,
            companions = "Asha", myShare = 3000.0, owedToMe = 3000.0, status = TransactionStatus.CARRIED_FWD,
            reviewState = ReviewState.CONFIRMED
        )
        val copy = Transaction(
            id = "copy_1", timestamp = 2000L, dateSerial = 46360, timeSerial = 0.5, account = "UPI",
            merchant = "[Carried from Nov] Resort Stay", tripTag = "Goa", category = "Travel", totalAmount = 3000.0,
            companions = "Asha", myShare = 0.0, owedToMe = 3000.0, status = TransactionStatus.PENDING,
            reviewState = ReviewState.CONFIRMED
        )
        val s = useCase.forTrip("Goa", listOf(original, copy))
        assertEquals(1, s.transactionCount)
        assertEquals(listOf("orig_1"), s.transactions.map { it.id })
        assertEquals(6000.0, s.totalPaid, 0.001)
        assertEquals(3000.0, s.givenPending, 0.001)
        assertEquals(3000.0, s.netExpenditure, 0.001)
    }
}
