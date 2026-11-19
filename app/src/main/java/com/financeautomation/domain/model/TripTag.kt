package com.financeautomation.domain.model

/**
 * Domain entity representing an event or trip tag for grouping transactions.
 * Pure Kotlin model with zero Android framework dependencies.
 */
data class TripTag(
    val id: String,
    val name: String,
    val isActive: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
    val startDateEpochDay: Long? = null,
    val endDateEpochDay: Long? = null
)
