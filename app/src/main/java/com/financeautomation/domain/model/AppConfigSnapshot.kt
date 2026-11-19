package com.financeautomation.domain.model

/**
 * Encapsulates full configuration state snapshot for cloud mirroring and restore.
 * Strictly contains ZERO financial data (zero amounts, zero merchant descriptions, zero timestamps).
 */
data class AppConfigSnapshot(
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val companions: List<Companion> = emptyList(),
    val tripTags: List<TripTag> = emptyList(),
    val preferences: Map<String, String> = emptyMap()
)
