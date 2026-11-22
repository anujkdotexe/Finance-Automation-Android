package com.financeautomation.domain.model

import com.financeautomation.core.utils.formatRupeesAuto

data class OpenDebtItem(
    val uuid: String,
    val description: String,
    val amountOwed: Double,
    val tabName: String,
    val person: String = ""
) {
    val displayLabel: String
        get() = if (person.isNotBlank()) {
            "$description (${formatRupeesAuto(amountOwed)} - $person) [$tabName]"
        } else {
            "$description (${formatRupeesAuto(amountOwed)}) [$tabName]"
        }
}
