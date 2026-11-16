package com.financeautomation.domain.model

data class OpenDebtItem(
    val uuid: String,
    val description: String,
    val amountOwed: Double,
    val tabName: String,
    val person: String = ""
) {
    val displayLabel: String
        get() = if (person.isNotBlank()) {
            "$description (₹${amountOwed.toInt()} - $person) [$tabName]"
        } else {
            "$description (₹${amountOwed.toInt()}) [$tabName]"
        }
}
