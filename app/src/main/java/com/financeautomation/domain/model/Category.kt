package com.financeautomation.domain.model

/**
 * Domain entity representing an expense category.
 * Pure Kotlin model with zero Android framework dependencies.
 */
data class Category(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val isExcludedFromLivingBurn: Boolean = false,
    val isArchived: Boolean = false,
    val displayOrder: Int = 0,
    val iconName: String = "Teal",
    val updatedAt: Long = 0L,
    val deleted: Boolean = false
)
