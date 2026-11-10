package com.financeautomation.domain.model

/**
 * Domain entity representing a friend or companion for shared expense splitting.
 * Pure Kotlin model with zero Android framework dependencies.
 */
data class Companion(
    val id: String,
    val name: String,
    val isSelected: Boolean = false,
    val balance: Double = 0.0,
    val defaultSplitRatio: Double = 1.0,
    val isArchived: Boolean = false
)
