package com.financeautomation.domain.model

/**
 * What "Clear voided history" would delete or did delete on this phone.
 * [skippedPending] counts voided rows whose void has not reached the sheet yet; they stay so the sheet is corrected.
 */
data class VoidedHistory(val transactions: Int, val inflows: Int, val skippedPending: Int) {
    val deletable: Int get() = transactions + inflows
}
