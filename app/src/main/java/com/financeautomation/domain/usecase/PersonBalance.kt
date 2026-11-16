package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.utils.AccountingUtils

/**
 * Whether a person still has an open balance: money they owe you on a pending split, or money you owe them on a
 * pending payable. A person with an open balance cannot be removed from the people list.
 * Pure Kotlin with zero Android framework dependencies.
 */
object PersonBalance {

    /** A payable you still owe: who it is owed to and the amount. */
    data class OpenPayable(val paidBy: String, val amount: Double)

    fun hasOpenBalance(name: String, transactions: List<Transaction>, payables: List<OpenPayable>): Boolean {
        val key = AccountingUtils.normalizePersonName(name)
        if (key.isBlank()) return false
        val hasPendingReceivable = transactions.any { txn ->
            if (txn.status != TransactionStatus.PENDING || txn.owedToMe <= 0.001) return@any false
            if (txn.companions.equals("Personal", ignoreCase = true)) return@any false
            val parsed = AccountingUtils.parseCompanionAmountsWithDisplay(AccountingUtils.tokens(txn.companions))
            if (parsed != null && parsed.isNotEmpty()) {
                parsed.any { it.key == key && it.amount > 0.001 }
            } else {
                val tokens = AccountingUtils.tokens(txn.companions).map { AccountingUtils.normalizePersonName(it) }
                tokens.contains(key)
            }
        }
        val youOwe = payables
            .filter { AccountingUtils.normalizePersonName(it.paidBy) == key }
            .sumOf { it.amount }
        return hasPendingReceivable || kotlin.math.abs(youOwe) > 0.001
    }
}
