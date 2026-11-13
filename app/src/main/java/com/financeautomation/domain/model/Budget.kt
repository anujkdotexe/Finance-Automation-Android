package com.financeautomation.domain.model

/**
 * A monthly spending budget for one category. [deleted] is only true for a tombstone waiting to be pushed to the
 * Config tab; the repository never returns deleted budgets to the app.
 */
data class Budget(
    val category: String,
    val monthlyAmount: Double,
    val updatedAt: Long,
    val deleted: Boolean = false
)
