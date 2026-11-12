package com.financeautomation.core.constants

/**
 * Standard categories, accounts, and category normalization rules.
 * Strictly enforces plural "Savings" for all asset allocations, investments, and SIPs.
 */
object CategoryConstants {

    const val CATEGORY_FOOD_AND_DRINK: String = "Food & Drink"
    const val CATEGORY_GROCERIES: String = "Groceries"
    const val CATEGORY_TRANSIT: String = "Transit"
    const val CATEGORY_SUBSCRIPTIONS: String = "Subscriptions"
    const val CATEGORY_SHOPPING: String = "Shopping"
    const val CATEGORY_TRIP_TRAVEL: String = "Trip/Travel"
    const val CATEGORY_HEALTH: String = "Health"
    const val CATEGORY_FAMILY: String = "Family"
    const val CATEGORY_SAVINGS: String = "Savings"
    const val CATEGORY_OTHER: String = "Other"

    val DEFAULT_CATEGORIES: List<String> = listOf(
        CATEGORY_FOOD_AND_DRINK,
        CATEGORY_GROCERIES,
        CATEGORY_TRANSIT,
        CATEGORY_SUBSCRIPTIONS,
        CATEGORY_SHOPPING,
        CATEGORY_TRIP_TRAVEL,
        CATEGORY_HEALTH,
        CATEGORY_FAMILY,
        CATEGORY_SAVINGS,
        CATEGORY_OTHER
    )

    const val ACCOUNT_CASH: String = "Cash"
    const val ACCOUNT_OTHER: String = "Other"

    val DEFAULT_ACCOUNTS: List<String> = listOf(
        ACCOUNT_CASH,
        ACCOUNT_OTHER
    )

    /**
     * Normalizes category name according to master ledger rules.
     * Throws IllegalArgumentException if singular "Saving" is provided.
     * Maps investments, mutual funds, and SIPs strictly to plural "Savings".
     */
    fun normalizeCategory(categoryName: String): String {
        val trimmed = categoryName.trim()
        val lower = trimmed.lowercase()
        return when (lower) {
            "saving" -> throw IllegalArgumentException(
                "Singular 'Saving' is forbidden. Category must strictly be 'Savings'"
            )
            "savings", "investment", "sip", "mutual fund" -> CATEGORY_SAVINGS
            else -> trimmed
        }
    }
}
