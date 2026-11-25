package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.VoidDecision
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.domain.utils.AccountingUtils.LegacyDebtMatch
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
 *
 * "Paid for by a friend" is decided only from a debt: a linked one, a queued one or a matching one. The account and
 * the shape of the expense id never decide it on their own.
 */
object VoidDecisionEngine {

    /**
     * Determines the all-or-nothing void decision for a given expense and candidate debts.
     * [queuedPayableId] is the debt of a delivery still in the queue; [queuedDeliveryNets] is true when that
     * delivery's stored plan already offsets part of the debt against what the friend owed.
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
        category: String = "",
        queuedPayableId: String? = null,
        queuedDeliveryNets: Boolean = false
    ): VoidDecision {
        // 0. A planned delivery that nets the debt has settled, or is about to settle, the friend's own debts
        if (queuedDeliveryNets) return VoidDecision.Block(VoidDecision.BlockReason.DEBT_OFFSET)

        // 1. If an exact linked payable exists by UUID match
        if (exactLinkedPayable != null) {
            return evaluateCandidate(exactLinkedPayable, expenseTotal)
        }

        // 2. The debt is still on its way to the sheet
        if (queuedPayableId != null) {
            return VoidDecision.VoidBoth(payableId = queuedPayableId)
        }

        // 3. Old entries with unrelated ids. A voided debt is owed to nobody and is not a candidate.
        val matches = allPayableCandidates
            .filter { !it.repaymentStatus.equals("Void", ignoreCase = true) }
            .map { candidate ->
                candidate to AccountingUtils.matchLegacyPayable(
                    dateIncurredSerial = candidate.dateIncurredSerial,
                    timeSerial = candidate.timeSerial,
                    paidBy = candidate.paidBy,
                    whatFor = candidate.whatFor,
                    txDateSerial = dateSerial,
                    txTimeSerial = timeSerial,
                    txMerchant = merchant,
                    txAccount = account,
                    txCategory = category
                )
            }
            .filter { it.second != LegacyDebtMatch.NONE }

        return when {
            // No linked debt or candidate: an ordinary expense, which is never blocked
            matches.isEmpty() -> VoidDecision.OrdinaryVoid
            matches.size == 1 && matches.first().second == LegacyDebtMatch.AGREES ->
                evaluateCandidate(matches.first().first, expenseTotal)
            // Several candidates, or one whose creditor cannot be confirmed
            else -> VoidDecision.Block(VoidDecision.BlockReason.AMBIGUOUS_DEBT_MATCH)
        }
    }

    private fun evaluateCandidate(candidate: PayableCandidate, expenseTotal: Double): VoidDecision {
        // Already voided: nothing is owed, so only the expense is left to void (also what a retry finds)
        if (candidate.repaymentStatus.equals("Void", ignoreCase = true)) return VoidDecision.OrdinaryVoid

        // The open amount lives on a later copy, which cannot be voided together with this entry safely
        if (candidate.repaymentStatus.equals("Carried Fwd", ignoreCase = true)) {
            return VoidDecision.Block(VoidDecision.BlockReason.DEBT_CARRIED_FORWARD)
        }

        val isPending = candidate.repaymentStatus.equals("Pending", ignoreCase = true)
        val amountMatches = abs(candidate.amountIOwe - expenseTotal) < 0.01

        if (isPending && amountMatches) {
            return VoidDecision.VoidBoth(
                payableId = candidate.uuid,
                sheetRowIndex = candidate.sheetRowIndex,
                tabName = candidate.tabName
            )
        }

        // Not pending, or less than the expense: repaid in part or full, or offset
        if (!isPending || candidate.amountIOwe < expenseTotal) {
            return VoidDecision.Block(VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID)
        }

        return VoidDecision.Block(VoidDecision.BlockReason.DEBT_OFFSET)
    }
}
