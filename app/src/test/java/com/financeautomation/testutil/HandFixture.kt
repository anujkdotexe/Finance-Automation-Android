package com.financeautomation.testutil

import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction

/** The audit's hand-checked month: a split of 50 (of 100), Savings 500, a review row 30 and a refund of 20. */
object HandFixture {
    private val at = System.currentTimeMillis() - 60_000L

    private fun txn(id: String, total: Double, share: Double, owed: Double = 0.0, category: String = "Food & Drink",
                    review: ReviewState = ReviewState.CONFIRMED, status: TransactionStatus = if (owed > 0) TransactionStatus.PENDING else TransactionStatus.PERSONAL) =
        Transaction(
            id = id, timestamp = at, dateSerial = 46000, timeSerial = 0.5, account = "SBI", merchant = id, tripTag = "",
            category = category, totalAmount = total, companions = if (owed > 0) "Asha" else "Personal", myShare = share,
            owedToMe = owed, status = status, sourcePackage = "manual", reviewState = review, syncState = SyncState.SYNCED,
            sheetRowIndex = null, isPossibleDuplicate = false
        )

    val transactions = listOf(
        txn("split", total = 100.0, share = 50.0, owed = 50.0),
        txn("savings", total = 500.0, share = 500.0, category = "Savings"),
        txn("review", total = 30.0, share = 30.0, review = ReviewState.NEEDS_REVIEW)
    )
    val inflows = listOf(Inflow("refund", at, 20.0, "SBI", "Refund / Return", "Shop", "returned"))

    const val LIVING_BURN = 30.0      // 50 - 20
    const val DAY_SUM_GROSS = 50.0    // what the day headers add to, before the refund
    const val REFUNDS = 20.0
    const val SAVINGS = 500.0
    const val REVIEW = 30.0
}
