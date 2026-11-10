package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.LedgerSnapshot
import com.financeautomation.domain.utils.AccountingUtils

/**
 * Result of rebuilding people list from the ledger snapshot.
 */
data class RebuiltPeople(
    val topChips: List<String>,
    val allNames: List<String>
)

/**
 * Pure domain UseCase for rebuilding people list from the master ledger snapshot (Phase 5.7).
 * Extracts names from Table 1 (companions) and Table 3 (paidBy), normalizes with tokens,
 * applies 1.0 weight for current/previous month and 0.5 weight for older tabs,
 * and yields top 6 chips and all search names.
 * Zero Android framework dependencies.
 */
class RebuildPeopleListUseCase {

    operator fun invoke(
        snapshot: LedgerSnapshot,
        currentMonthTab: String = "",
        previousMonthTab: String = ""
    ): RebuiltPeople {
        val frequencyMap = mutableMapOf<String, Double>()

        // 1. Process Table 1 debits (companions)
        for (debit in snapshot.debits) {
            if (debit.status.equals("Void", ignoreCase = true)) continue
            val tokens = AccountingUtils.tokens(debit.companions)
                .filter { !it.equals("Personal", ignoreCase = true) }
            val weight = if (debit.tab.equals(currentMonthTab, ignoreCase = true) ||
                debit.tab.equals(previousMonthTab, ignoreCase = true)
            ) 1.0 else 0.5

            for (token in tokens) {
                val capitalized = token.split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                frequencyMap[capitalized] = (frequencyMap[capitalized] ?: 0.0) + weight
            }
        }

        // 2. Process Table 3 payables (paidBy)
        for (payable in snapshot.payables) {
            if (payable.status.equals("Void", ignoreCase = true)) continue
            val tokens = AccountingUtils.tokens(payable.paidBy)
            val weight = if (payable.tab.equals(currentMonthTab, ignoreCase = true) ||
                payable.tab.equals(previousMonthTab, ignoreCase = true)
            ) 1.0 else 0.5

            for (token in tokens) {
                val capitalized = token.split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                frequencyMap[capitalized] = (frequencyMap[capitalized] ?: 0.0) + weight
            }
        }

        val rankedNames = frequencyMap.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { it.key }

        return RebuiltPeople(
            topChips = rankedNames.take(6),
            allNames = rankedNames
        )
    }
}

