package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that TripSummaryUseCase strictly adheres to the shared LivingBurn specification
 * (AGENTS.md Section 2C.5):
 * 1. Rows in review (NEEDS_REVIEW) are excluded from spend and reported as unconfirmed.
 * 2. Savings and Transfer rows are excluded from living burn.
 * 3. Refunds reduce net expenditure without distorting category percentage breakdown (summing to 100%).
 * 4. Net expenditure never drops below zero.
 */
class TripSummaryLivingBurnParityTest {

    private val useCase = TripSummaryUseCase()

    private fun txn(
        id: String,
        category: String,
        total: Double,
        myShare: Double,
        owed: Double,
        status: TransactionStatus = TransactionStatus.PERSONAL,
        reviewState: ReviewState = ReviewState.CONFIRMED,
        companions: String = "Personal",
        merchant: String = "Merchant $id",
        ts: Long = 1000L
    ) = Transaction(
        id = id,
        timestamp = ts,
        dateSerial = 46330,
        timeSerial = 0.5,
        account = "UPI",
        merchant = merchant,
        tripTag = "Goa",
        category = category,
        totalAmount = total,
        companions = companions,
        myShare = myShare,
        owedToMe = owed,
        status = status,
        reviewState = reviewState
    )

    @Test
    fun tripWithRefundSavingsAndRowInReview_obeysLivingBurnSpecification() {
        val rows = listOf(
            // 1. Confirmed personal spend
            txn("1", "Travel", total = 5000.0, myShare = 5000.0, owed = 0.0, merchant = "Flight"),
            // 2. Confirmed split spend
            txn("2", "Travel", total = 4000.0, myShare = 2000.0, owed = 2000.0, status = TransactionStatus.PENDING, companions = "Asha", merchant = "Hotel"),
            // 3. Row waiting in review
            txn("3", "Travel", total = 600.0, myShare = 600.0, owed = 0.0, reviewState = ReviewState.NEEDS_REVIEW, merchant = "Taxi"),
            // 4. Savings row (must be excluded from burn)
            txn("4", CategoryConstants.CATEGORY_SAVINGS, total = 10000.0, myShare = 10000.0, owed = 0.0, merchant = "Trip SIP"),
            // 5. Transfer row (must be excluded from burn)
            txn("5", CategoryConstants.CATEGORY_TRANSFER, total = 2500.0, myShare = 2500.0, owed = 0.0, merchant = "Account Transfer"),
            // 6. Refund row (reduces burn, not an out-of-pocket expense)
            txn("6", CategoryConstants.CATEGORY_REFUND, total = 1000.0, myShare = 1000.0, owed = 0.0, merchant = "Airline Refund")
        )

        val summary = useCase.forTrip("Goa", rows)

        // Total paid counts only confirmed, non-excluded spend (5000 + 4000 = 9000)
        assertEquals(9000.0, summary.totalPaid, 0.001)

        // Pending owed by Asha
        assertEquals(2000.0, summary.givenPending, 0.001)
        assertEquals(0.0, summary.returned, 0.001)

        // Net expenditure: (5000 + 2000) - 1000 = 6000
        assertEquals(6000.0, summary.netExpenditure, 0.001)

        // Unconfirmed row reported separately
        assertEquals(1, summary.unconfirmedCount)
        assertEquals(600.0, summary.unconfirmedAmount, 0.001)

        // Category breakdown includes only confirmed spend rows
        assertEquals(1, summary.categoryBreakdown.size)
        val cat = summary.categoryBreakdown[0]
        assertEquals("Travel", cat.category)
        assertEquals(7000.0, cat.amount, 0.001)
        // Percentages must sum to 100.0% even when refunds exist
        assertEquals(100.0, cat.percentage, 0.001)

        // Biggest single expense must be from counted spend (Flight at 5000.0, not Savings at 10000.0)
        assertEquals("Flight", summary.biggestSingleExpense?.merchant)
        assertEquals(5000.0, summary.biggestSingleExpense?.totalAmount ?: 0.0, 0.001)

        // People count: Asha + user = 2
        assertEquals(2, summary.peopleCount)
    }

    @Test
    fun refundInflowAlsoReducesTripNetExpenditure() {
        val rows = listOf(
            txn("1", "Travel", total = 5000.0, myShare = 5000.0, owed = 0.0, merchant = "Flight")
        )
        val inflows = listOf(
            Inflow(
                id = "inf_1",
                timestamp = 1500L,
                amount = 1200.0,
                account = "UPI",
                inflowType = "Refund",
                source = "Airline",
                note = "Goa Flight cancellation refund"
            ),
            Inflow(
                id = "inf_2",
                timestamp = 1600L,
                amount = 5000.0,
                account = "UPI",
                inflowType = "Salary",
                source = "Employer",
                note = "Goa monthly salary bonus"
            )
        )

        val summary = useCase.forTrip("Goa", rows, inflows = inflows)
        // 5000 - 1200 refund = 3800 (salary is ignored)
        assertEquals(3800.0, summary.netExpenditure, 0.001)
    }

    @Test
    fun netExpenditureNeverFallsBelowZero() {
        val rows = listOf(
            txn("1", "Travel", total = 500.0, myShare = 500.0, owed = 0.0),
            txn("2", CategoryConstants.CATEGORY_REFUND, total = 2000.0, myShare = 2000.0, owed = 0.0)
        )
        val summary = useCase.forTrip("Goa", rows)
        assertEquals(0.0, summary.netExpenditure, 0.001)
    }
}
