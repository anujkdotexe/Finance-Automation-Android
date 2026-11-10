package com.financeautomation.domain.model

import com.financeautomation.core.model.InflowType

/**
 * Domain entity representing an inflow/credit transaction in Table 2.
 * Pure Kotlin model with zero Android framework dependencies.
 */
data class InflowTransaction(
    val id: String,
    val dateSerial: Int,
    val timeSerial: Double,
    val account: String,
    val inflowType: InflowType,
    val receivedFrom: String,
    val reason: String,
    val amount: Double,
    val linkedDebitUuid: String = ""
)
