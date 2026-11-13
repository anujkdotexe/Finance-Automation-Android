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
        val owedToYou = transactions
            .filter { it.status == TransactionStatus.PENDING && it.owedToMe > 0.0 }
            .flatMap { AccountingUtils.calculateReceivablesForTransaction(it) }
            .filter { it.key == key }
            .sumOf { it.amount }
        val youOwe = payables
            .filter { AccountingUtils.normalizePersonName(it.paidBy) == key }
            .sumOf { it.amount }
        return kotlin.math.abs(owedToYou) > 0.001 || kotlin.math.abs(youOwe) > 0.001
    }
}
