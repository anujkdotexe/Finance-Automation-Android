package com.financeautomation.domain.model

/**
 * Domain entity representing a funding/payment account (e.g. SBI, Canara, HDFC, Credit Card, Cash).
 * Pure Kotlin model with zero Android framework dependencies.
 */
data class Account(
    val id: String,
    val name: String,
    val lastFourDigits: String? = null,
    val isDefault: Boolean = false,
    val isArchived: Boolean = false,
    val displayOrder: Int = 0
)
