package com.financeautomation.domain.model

import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus

/**
 * Domain entity representing a financial transaction (debit/expense) in Table 1.
 * Pure Kotlin model with zero Android framework dependencies.
 */
data class Transaction(
    val id: String,
    val timestamp: Long,
    val dateSerial: Int,
    val timeSerial: Double,
    val account: String,
    val merchant: String,
    val tripTag: String = "",
    val category: String,
    val totalAmount: Double,
    val companions: String = "Personal",
    val myShare: Double,
    val owedToMe: Double,
    val status: TransactionStatus = TransactionStatus.PERSONAL,
    val sourcePackage: String = "",
    val reviewState: ReviewState = ReviewState.NEEDS_REVIEW,
    val syncState: SyncState = SyncState.PENDING,
    val sheetRowIndex: Int? = null,
    val isPossibleDuplicate: Boolean = false
) {
    val itemDescription: String get() = merchant
    val packageName: String get() = sourcePackage
}
