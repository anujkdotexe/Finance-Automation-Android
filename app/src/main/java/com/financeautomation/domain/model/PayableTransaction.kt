package com.financeautomation.domain.model

import com.financeautomation.core.model.RepaymentStatus

/**
 * Domain entity representing a payable/liability transaction in Table 3.
 * Pure Kotlin model with zero Android framework dependencies.
 */
data class PayableTransaction(
    val id: String,
    val dateIncurredSerial: Int,
    val timeSerial: Double,
    val paidBy: String,
    val whatFor: String,
    val amountIOwe: Double,
    val dateRepaidSerial: Int? = null,
    val repaymentStatus: RepaymentStatus = RepaymentStatus.PENDING
)
