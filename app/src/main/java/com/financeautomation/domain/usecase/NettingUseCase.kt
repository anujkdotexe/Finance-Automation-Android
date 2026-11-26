package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.domain.utils.Tolerances

data class NettingDebitResult(
    val netOwed: Double,
    val offsetTotal: Double,
    val edits: List<CellEdit>
)

data class NettingPayableResult(
    val netAmount: Double,
    val offsetTotal: Double,
    val edits: List<CellEdit>
)

data class RepaymentApplicationResult(
    val applied: Double,
    val newOwed: Double,
    val newStatus: String,
    val newPaidBy: String
)

/**
 * Pure domain UseCase executing bidirectional netting between open Debits and open Payables.
 * Port of Code.gs offsetDebitsAgainstPayables and offsetPayablesAgainstDebits.
 * Pure Kotlin with zero Android framework dependencies.
 */
class NettingUseCase {

    /**
     * Port of offsetDebitsAgainstPayables.
     * Offsets a newly logged debit's owed balance against existing open payables owed to the companions.
     */
    fun nettingOnDebit(
        companions: String,
        owed: Double,
        dateSerial: Int,
        payables: List<LedgerPayable>
    ): NettingDebitResult {
        val list = AccountingUtils.tokens(companions)
        if (list.isEmpty() || owed <= 0.0) {
            return NettingDebitResult(netOwed = owed, offsetTotal = 0.0, edits = emptyList())
        }

        val cap = AccountingUtils.round2(owed / list.size)
        var owedLeft = owed
        var offsetTotal = 0.0
        val edits = mutableListOf<CellEdit>()

        // Create a working mutable copy of payables to reflect in-memory partial applications
        val workingPayables = payables.map { it.copy() }.sortedWith(RepaymentOrder.payables).toMutableList()

        for ((companionIndex, companion) in list.withIndex()) {
            // The last companion takes the rounding remainder so the caps add up to the owed amount
            var capLeft = if (companionIndex == list.lastIndex) AccountingUtils.round2(owed - cap * (list.size - 1)) else cap
            for (i in workingPayables.indices) {
                if (capLeft <= Tolerances.MONEY_LEFT || owedLeft <= Tolerances.MONEY_LEFT) break
                val payable = workingPayables[i]
                if (payable.status != "Pending" && payable.status != "Partial") continue
                val paidByTokens = AccountingUtils.tokens(payable.paidBy)
                if (!paidByTokens.any { AccountingUtils.eq(it, companion) }) continue

                val amountToApply = minOf(capLeft, owedLeft)
                val repRes = applyRepaymentToPayable(payable, companion, amountToApply, dateSerial) ?: continue

                capLeft = AccountingUtils.round2(capLeft - repRes.applied)
                owedLeft = AccountingUtils.round2(owedLeft - repRes.applied)
                offsetTotal = AccountingUtils.round2(offsetTotal + repRes.applied)

                // Update in-memory copy
                workingPayables[i] = payable.copy(
                    amountOwed = repRes.newOwed,
                    status = repRes.newStatus,
                    paidBy = repRes.newPaidBy
                )

                // Record edits: Date Repaid (AB), Paid By (Y), Amount I Owe (AA), Repayment Status (AC)
                edits.add(CellEdit(payable.tab, payable.row, SheetSchemaConstants.COL_LETTER_PAYABLE_DATE_REPAID, dateSerial, payable.uuid))
                if (payable.paidBy != repRes.newPaidBy) {
                    edits.add(CellEdit(payable.tab, payable.row, SheetSchemaConstants.COL_LETTER_PAYABLE_PAID_BY, repRes.newPaidBy, payable.uuid))
                }
                edits.add(CellEdit(payable.tab, payable.row, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE, repRes.newOwed, payable.uuid))
                edits.add(CellEdit(payable.tab, payable.row, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, repRes.newStatus, payable.uuid))
            }
        }

        return NettingDebitResult(
            netOwed = AccountingUtils.round2(owedLeft),
            offsetTotal = AccountingUtils.round2(offsetTotal),
            edits = edits
        )
    }

    /**
     * Port of offsetPayablesAgainstDebits.
     * Offsets a newly logged payable amount against existing open debits owed by the creditor.
     */
    fun nettingOnPayable(
        creditor: String,
        amount: Double,
        debits: List<LedgerDebit>,
        settleOneDebtFn: (LedgerDebit, Double, String, Boolean) -> SettlementStepResult
    ): NettingPayableResult {
        if (creditor.isBlank() || amount <= 0.0) {
            return NettingPayableResult(netAmount = amount, offsetTotal = 0.0, edits = emptyList())
        }

        var remaining = amount
        var offsetTotal = 0.0
        val edits = mutableListOf<CellEdit>()

        for (debit in debits.sortedWith(RepaymentOrder.debits)) {
            if (remaining <= Tolerances.MONEY_LEFT) break
            if (debit.status != "Pending" || debit.owed <= 0.0) continue
            val companionTokens = AccountingUtils.tokens(debit.companions)
            if (!companionTokens.any { AccountingUtils.eq(it, creditor) }) continue

            val step = settleOneDebtFn(debit, minOf(debit.owed, remaining), creditor, false)
            if (step.applied > 0.0) {
                remaining = AccountingUtils.round2(remaining - step.applied)
                offsetTotal = AccountingUtils.round2(offsetTotal + step.applied)
                edits.addAll(step.edits)
            }
        }

        return NettingPayableResult(
            netAmount = AccountingUtils.round2(remaining),
            offsetTotal = AccountingUtils.round2(offsetTotal),
            edits = edits
        )
    }

    /**
     * Port of applyRepaymentToPayableRow_.
     */
    fun applyRepaymentToPayable(
        row: LedgerPayable,
        creditor: String,
        remaining: Double,
        dateSerial: Int
    ): RepaymentApplicationResult? {
        if ((row.status != "Pending" && row.status != "Partial") || row.amountOwed <= 0.0) {
            return null
        }
        val list = AccountingUtils.tokens(row.paidBy)
        val matchIdx = if (creditor.isNotBlank()) list.indexOfFirst { AccountingUtils.eq(it, creditor) } else 0
        if (creditor.isNotBlank() && matchIdx == -1) {
            return null
        }

        val isShared = list.size > 1 && matchIdx >= 0
        val share = if (isShared) AccountingUtils.round2(row.amountOwed / list.size) else row.amountOwed

        val applied = AccountingUtils.round2(minOf(remaining, share, row.amountOwed))
        if (applied <= 0.0) return null

        val newOwed = maxOf(0.0, AccountingUtils.round2(row.amountOwed - applied))
        val newPaidBy = if (isShared && applied >= share - Tolerances.SHARE_FULLY_COVERED) {
            val copy = list.toMutableList()
            if (matchIdx in copy.indices) copy.removeAt(matchIdx)
            copy.joinToString(", ")
        } else {
            row.paidBy
        }
        val newStatus = if (newOwed > 0.0) "Partial" else "Repaid"

        return RepaymentApplicationResult(
            applied = applied,
            newOwed = newOwed,
            newStatus = newStatus,
            newPaidBy = newPaidBy
        )
    }
}

