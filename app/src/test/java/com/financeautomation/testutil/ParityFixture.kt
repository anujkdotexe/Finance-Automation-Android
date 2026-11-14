package com.financeautomation.testutil

import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction

/**
 * One month of data that every screen must read the same way: a plain expense, a split, a void row, a Savings row, a
 * row waiting in review, a carried forward copy and a refund. The expected figures follow the owner rules.
 */
object ParityFixture {
    private val at = System.currentTimeMillis() - 60_000L

    private fun txn(
        id: String, total: Double, share: Double, owed: Double = 0.0, category: String = "Food & Drink",
        status: TransactionStatus = if (owed > 0.0) TransactionStatus.PENDING else TransactionStatus.PERSONAL,
        review: ReviewState = ReviewState.CONFIRMED, merchant: String = id
    ) = Transaction(
        id = id, timestamp = at, dateSerial = 46000, timeSerial = 0.5, account = "SBI", merchant = merchant, tripTag = "",
        category = category, totalAmount = total, companions = if (owed > 0.0) "Asha" else "Personal", myShare = share,
        owedToMe = owed, status = status, sourcePackage = "manual", reviewState = review, syncState = SyncState.SYNCED,
        sheetRowIndex = null, isPossibleDuplicate = false
    )

    val transactions: List<Transaction> = listOf(
        txn("plain", total = 100.0, share = 100.0),
        txn("split", total = 300.0, share = 100.0, owed = 200.0),
        txn("void", total = 0.0, share = 0.0, status = TransactionStatus.VOID, merchant = "[Void] Tea"),
        txn("savings", total = 200.0, share = 200.0, category = "Savings"),
        txn("review", total = 40.0, share = 40.0, review = ReviewState.NEEDS_REVIEW),
        txn("copy", total = 200.0, share = 0.0, owed = 200.0, merchant = "[Carried from Oct26] Dinner")
    )

    val inflows: List<Inflow> = listOf(
        Inflow("refund", at, 60.0, "SBI", "Refund / Return", "Shop", "returned"),
        Inflow("salary", at, 5000.0, "SBI", "Income / Salary", "Employer", "pay")
    )

    /** 100 + 100 = 200 spent, 60 refunded. */
    const val LIVING_BURN = 140.0

    /** Rows that waited in review: one row of 40. */
    const val UNCONFIRMED_COUNT = 1
    const val UNCONFIRMED_AMOUNT = 40.0

    /** Money out without the carried copy: 100 + 300 + 200 + 40. */
    const val TOTAL_PAID = 640.0

    /** Income without the refund. */
    const val INCOME = 5000.0

    /** Savings without the review row. */
    const val SAVINGS = 200.0
}
