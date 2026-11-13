package com.financeautomation.core.model

/**
 * Two-axis review state representing user confirmation status.
 */
enum class ReviewState {
    NEEDS_REVIEW,
    CONFIRMED
}

/**
 * Two-axis sync state representing cloud persistence status.
 */
enum class SyncState {
    PENDING,
    SYNCED,
    FAILED,
    VOID
}

/**
 * Split mode for allocating shared expenses.
 */
enum class SplitMode {
    JUST_ME,
    SPLIT_EQUALLY,
    CUSTOM
}

/**
 * Standardized transaction status strictly adhering to master ledger rules.
 */
enum class TransactionStatus(val value: String) {
    PERSONAL("Personal"),
    PENDING("Pending"),
    SETTLED("Settled"),
    CARRIED_FWD("Carried Fwd"),
    VOID("Void");

    companion object {
        fun fromString(str: String?): TransactionStatus {
            if (str.isNullOrBlank()) return PERSONAL
            return values().firstOrNull { it.value.equals(str.trim(), ignoreCase = true) }
                ?: if (str.equals("Carried", ignoreCase = true)) CARRIED_FWD else PERSONAL
        }
    }
}

/**
 * Allowed inflow categories for Table 2.
 */
enum class InflowType(val value: String) {
    SALARY("Salary"),
    FRIEND_SETTLEMENT("Friend Settlement"),
    ALLOWANCE("Allowance"),
    OTHER("Other")
}

/**
 * Repayment status for Table 3 payables.
 */
enum class RepaymentStatus(val value: String) {
    PENDING("Pending"),
    PARTIAL("Partial"),
    REPAID("Repaid"),
    CARRIED_FWD("Carried Fwd"),
    VOID("Void");

    companion object {
        fun fromString(str: String?): RepaymentStatus {
            if (str.isNullOrBlank()) return PENDING
            return values().firstOrNull { it.value.equals(str.trim(), ignoreCase = true) }
                ?: if (str.equals("Carried", ignoreCase = true)) CARRIED_FWD else PENDING
        }
    }
}
