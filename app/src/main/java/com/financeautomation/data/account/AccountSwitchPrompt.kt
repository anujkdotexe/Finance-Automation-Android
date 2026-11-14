package com.financeautomation.data.account

/** What the user is asked before another Google account takes over this phone's ledger. */
data class AccountSwitchPrompt(
    val email: String,
    val displayName: String?,
    val photoUrl: String?,
    val pendingCount: Int
)
