package com.financeautomation.domain.model

data class Inflow(
    val id: String,
    val timestamp: Long,
    val amount: Double,
    val account: String,
    val inflowType: String,
    val source: String,
    val note: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "CONFIRMED"
)

