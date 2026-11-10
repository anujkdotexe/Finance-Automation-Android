package com.financeautomation.domain.model

import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.TransactionStatus

/**
 * Domain entity holding the computed result of an expense split calculation.
 * Pure Kotlin model with zero Android framework dependencies.
 */
data class SplitCalculation(
    val totalAmount: Double,
    val myShare: Double,
    val owedToMe: Double,
    val companionsText: String,
    val status: TransactionStatus,
    val splitMode: SplitMode
)
