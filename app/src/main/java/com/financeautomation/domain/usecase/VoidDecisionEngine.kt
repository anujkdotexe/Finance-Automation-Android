package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.VoidDecision
import com.financeautomation.domain.utils.AccountingUtils
import kotlin.math.abs

/**
 * Pure candidate representation of a payable row (from Room or Sheet Table 3).
 */
data class PayableCandidate(
    val uuid: String,
    val paidBy: String,
    val whatFor: String,
    val amountIOwe: Double,
    val repaymentStatus: String,
    val dateIncurredSerial: Int?,
    val timeSerial: Double?,
    val tabName: String? = null,
    val sheetRowIndex: Int? = null
)

/**
 * Single decision function for voiding an expense under the ALL-OR-NOTHING rule:
 * - Untouched debt (Pending, amount equal to expense total): void both expense and debt.
 * - Debt partly or fully repaid, or offset against what friend owed: BLOCK the whole void.
 * - A possible matching debt exists but cannot be identified safely: BLOCK, change nothing.
 * - Ordinary expense with no matching debt candidate: void expense as before (never blocked).
 */
object VoidDecisionEngine {

    /**
     * Determines the all-or-nothing void decision for a given expense and candidate debts.
     */
    fun decide(
        expenseTotal: Double,
        expenseId: String,
        merchant: String,
        account: String,
        dateSerial: Int,
        timeSerial: Double,
        exactLinkedPayable: PayableCandidate?,
        allPayableCandidates: List<PayableCandidate>,
        hasQueuedPayableWithoutPlan: Boolean = false,
        hasQueuedPayableWithPlan: Boolean = false
    ): VoidDecision {
        // 1. If an exact linked payable exists by UUID match
        if (exactLinkedPayable != null) {
            return evaluateCandidate(exactLinkedPayable, expenseTotal)
        }

        // 2. If a queued payable exists
        if (hasQueuedPayableWithoutPlan || hasQueuedPayableWithPlan) {
            val linkedPayId = AccountingUtils.linkedPayableIdForExpense(expenseId)
            return VoidDecision.VoidBoth(
                payableId = linkedPayId ?: "pay_${expenseId.removePrefix("txn_")}"
            )
        }

        // 3. Check for matching legacy payable candidates
        val matchingCandidates = allPayableCandidates.filter { candidate ->
            AccountingUtils.legacyPayableAgreesWithExpense(
                dateIncurredSerial = candidate.dateIncurredSerial,
                timeSerial = candidate.timeSerial,
                paidBy = candidate.paidBy,
                whatFor = candidate.whatFor,
                txDateSerial = dateSerial,
                txTimeSerial = timeSerial,
                txMerchant = merchant,
                txAccount = account
            )
        }

        when (matchingCandidates.size) {
            0 -> {
                // No linked debt or candidate found: this is an ordinary expense.
                // Ordinary expenses are NEVER blocked.
                return VoidDecision.OrdinaryVoid
            }
            1 -> {
                // Exactly one matching candidate
                return evaluateCandidate(matchingCandidates.first(), expenseTotal)
            }
            else -> {
                // Multiple possible matching candidates exist and cannot be identified safely
                return VoidDecision.Block(VoidDecision.BlockReason.AMBIGUOUS_DEBT_MATCH)
            }
        }
    }

    private fun evaluateCandidate(candidate: PayableCandidate, expenseTotal: Double): VoidDecision {
        val isPending = candidate.repaymentStatus.equals("Pending", ignoreCase = true)
        val amountMatches = abs(candidate.amountIOwe - expenseTotal) < 0.01

        if (isPending && amountMatches) {
            return VoidDecision.VoidBoth(
                payableId = candidate.uuid,
                sheetRowIndex = candidate.sheetRowIndex,
                tabName = candidate.tabName
            )
        }

        if (!isPending) {
            return VoidDecision.Block(VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID)
        }

        // Status is pending, but amount was reduced (partially repaid or offset)
        if (candidate.amountIOwe < expenseTotal) {
            return VoidDecision.Block(VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID)
        }

        return VoidDecision.Block(VoidDecision.BlockReason.DEBT_OFFSET)
    }
}
