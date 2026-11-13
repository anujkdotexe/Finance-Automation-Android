package com.financeautomation.domain.usecase

import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Returned money comes from what was owed originally, and carried forward copies are not trip spending. */
class TripSummaryReturnedTest {

    private fun txn(
        id: String, total: Double, share: Double, owed: Double, status: TransactionStatus,
        merchant: String = id, companions: String = "Asha", ts: Long = 1000L
    ) = Transaction(
        id = id, timestamp = ts, dateSerial = 1, timeSerial = 0.5, account = "SBI", merchant = merchant, tripTag = "Goa",
        category = "Travel", totalAmount = total, companions = companions, myShare = share, owedToMe = owed, status = status,
        sourcePackage = "manual", reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED,
        sheetRowIndex = null, isPossibleDuplicate = false
    )

    private val useCase = TripSummaryUseCase()

    @Test
    fun aSettledRowCountsAsReturnedByWhatWasOwedOriginally() {
        // 300 paid, my share 100, the 200 others owed was paid back (owed is now 0)
        val s = useCase.forTrip("Goa", listOf(txn("a", 300.0, 100.0, 0.0, TransactionStatus.SETTLED)))
        assertEquals(200.0, s.returned, 0.001)
        assertEquals(0.0, s.givenPending, 0.001)
        assertEquals(100.0, s.netExpenditure, 0.001)
    }

    @Test
    fun aPartlyPaidRowSplitsIntoReturnedAndStillOwed() {
        val s = useCase.forTrip("Goa", listOf(txn("a", 300.0, 100.0, 50.0, TransactionStatus.PENDING)))
        assertEquals(150.0, s.returned, 0.001)
        assertEquals(50.0, s.givenPending, 0.001)
    }

    @Test
    fun aCarriedForwardCopyIsNotCountedAsSpendingOrAsAnotherPerson() {
        val original = txn("a", 300.0, 100.0, 200.0, TransactionStatus.CARRIED_FWD, merchant = "Dinner", ts = 1000L)
        val copy = txn("a2", 200.0, 0.0, 200.0, TransactionStatus.PENDING, merchant = "[Carried from Nov26] Dinner", companions = "Asha, Zed", ts = 2000L)
        val s = useCase.forTrip("Goa", listOf(original, copy))
        assertEquals(300.0, s.totalPaid, 0.001)
        assertEquals(1, s.transactionCount)
        assertEquals("Zed appears only on the copy", 2, s.peopleCount)
        assertEquals(200.0, s.givenPending, 0.001)
        assertEquals(0.0, s.returned, 0.001)
    }

    @Test
    fun aCarriedDebtThatWasLaterSettledOnTheCopyIsReturned() {
        val original = txn("a", 300.0, 100.0, 200.0, TransactionStatus.CARRIED_FWD, merchant = "Dinner")
        val settledCopy = txn("a2", 200.0, 0.0, 0.0, TransactionStatus.SETTLED, merchant = "[Carried from Nov26] Dinner")
        val s = useCase.forTrip("Goa", listOf(original, settledCopy))
        assertEquals(0.0, s.givenPending, 0.001)
        assertEquals(200.0, s.returned, 0.001)
        assertEquals(300.0, s.totalPaid, 0.001)
    }

    @Test
    fun aPersonalRowReturnsNothing() {
        val s = useCase.forTrip("Goa", listOf(txn("a", 80.0, 80.0, 0.0, TransactionStatus.PERSONAL, companions = "Personal")))
        assertEquals(0.0, s.returned, 0.001)
        assertEquals(80.0, s.netExpenditure, 0.001)
    }
}
