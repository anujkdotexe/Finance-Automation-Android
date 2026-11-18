package com.financeautomation.domain.model

/** How often a subscription falls due. */
enum class SubscriptionFrequency {
    WEEKLY, MONTHLY, YEARLY;

    companion object {
        fun parse(raw: String?): SubscriptionFrequency =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MONTHLY
    }
}

/** State of one due occurrence of a subscription. */
enum class SubscriptionCycleState {
    DUE, PAID, SKIPPED, NEEDS_REVIEW;

    companion object {
        fun parse(raw: String?): SubscriptionCycleState =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DUE
    }
}

/**
 * A recurring payment the user tracks. [amount] null means the amount varies.
 * Dates are epoch days (days since 1970-01-01) in Asia/Kolkata.
 */
data class Subscription(
    val id: String,
    val name: String,
    val amount: Double?,
    val frequency: SubscriptionFrequency,
    val anchorEpochDay: Long,
    val account: String,
    val category: String = DEFAULT_CATEGORY,
    val remind: Boolean = true,
    val keywords: String = "",
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
    val note: String = ""
) {
    /** Lower-cased match tokens: the name plus every comma separated keyword. */
    val matchTokens: List<String>
        get() = (listOf(name) + keywords.split(",")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_CATEGORY: String = "Subscriptions"
    }
}

/** One due occurrence of a subscription. */
data class SubscriptionCycle(
    val id: String,
    val subscriptionId: String,
    val dueEpochDay: Long,
    val state: SubscriptionCycleState,
    val transactionId: String? = null,
    val remindCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/** A cycle joined with its subscription. */
data class SubscriptionCycleWithSubscription(
    val cycle: SubscriptionCycle,
    val subscription: Subscription
)
