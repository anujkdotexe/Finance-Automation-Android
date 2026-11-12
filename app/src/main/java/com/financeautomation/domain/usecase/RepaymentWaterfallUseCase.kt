package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.RowKey
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.domain.utils.Tolerances
import java.util.UUID
import java.util.regex.Pattern

/**
 * Pure domain UseCase executing the Repayment Waterfall when repaying debts owed to friends.
 * Port of Code.gs reconcilePayablesWaterfall and applyRepaymentToPayableRow_.
 * Pure Kotlin with zero Android framework dependencies.
 */
class RepaymentWaterfallUseCase(
    private val nettingUseCase: NettingUseCase = NettingUseCase()
) {

    private val ROW_REF_PATTERN = Pattern.compile("(?:'([^']+)'|([A-Za-z0-9_]+))!(?:Row)?(\\d+)", Pattern.CASE_INSENSITIVE)

    operator fun invoke(
        creditor: String,
        amount: Double,
        explicitRef: String? = null,
        dateSerial: Int,
        timeSerial: Double,
        tabName: String,
        allPayables: List<LedgerPayable>,
        resolveCarriedFn: ((LedgerPayable) -> LedgerPayable?)? = null
    ): AccountingPlan {
        var remaining = AccountingUtils.round2(amount)
        var repaidCount = 0
        val allEdits = mutableListOf<CellEdit>()
        val handledKeys = mutableSetOf<RowKey>()

        val workingPayables = allPayables.map { it.copy() }.toMutableList()

        // 1. Process explicit reference if provided
        if (!explicitRef.isNullOrBlank()) {
            val target = resolveReference(explicitRef, workingPayables)
            if (target != null) {
                val live = if (target.status == "Carried Fwd" && resolveCarriedFn != null) {
                    resolveCarriedFn(target) ?: target
                } else {
                    target
                }

                val key = RowKey(live.tab, live.row)
                handledKeys.add(key)
                val repRes = nettingUseCase.applyRepaymentToPayable(live, creditor, remaining, dateSerial)
                if (repRes != null && repRes.applied > 0.0) {
                    repaidCount++
                    remaining = AccountingUtils.round2(remaining - repRes.applied)

                    allEdits.add(CellEdit(live.tab, live.row, "AA", dateSerial))
                    if (live.paidBy != repRes.newPaidBy) {
                        allEdits.add(CellEdit(live.tab, live.row, "X", repRes.newPaidBy))
                    }
                    allEdits.add(CellEdit(live.tab, live.row, "Z", repRes.newOwed))
                    allEdits.add(CellEdit(live.tab, live.row, "AB", repRes.newStatus))

                    val idx = workingPayables.indexOfFirst { it.tab == live.tab && it.row == live.row }
                    if (idx >= 0) {
                        workingPayables[idx] = live.copy(
                            amountOwed = repRes.newOwed,
                            status = repRes.newStatus,
                            paidBy = repRes.newPaidBy
                        )
                    }
                }
            }
        }

        // 2. FIFO scan across payables oldest first
        if (remaining > Tolerances.MONEY_LEFT && creditor.isNotBlank()) {
            for (i in workingPayables.indices) {
                if (remaining <= Tolerances.MONEY_LEFT) break
                val payable = workingPayables[i]
                val key = RowKey(payable.tab, payable.row)
                if (handledKeys.contains(key)) continue
                if (payable.status != "Pending" && payable.status != "Partial") continue

                val tokens = AccountingUtils.tokens(payable.paidBy)
                if (!tokens.any { AccountingUtils.eq(it, creditor) }) continue

                handledKeys.add(key)
                val repRes = nettingUseCase.applyRepaymentToPayable(payable, creditor, remaining, dateSerial) ?: continue
                if (repRes.applied > 0.0) {
                    repaidCount++
                    remaining = AccountingUtils.round2(remaining - repRes.applied)

                    allEdits.add(CellEdit(payable.tab, payable.row, "AA", dateSerial))
                    if (payable.paidBy != repRes.newPaidBy) {
                        allEdits.add(CellEdit(payable.tab, payable.row, "X", repRes.newPaidBy))
                    }
                    allEdits.add(CellEdit(payable.tab, payable.row, "Z", repRes.newOwed))
                    allEdits.add(CellEdit(payable.tab, payable.row, "AB", repRes.newStatus))

                    workingPayables[i] = payable.copy(
                        amountOwed = repRes.newOwed,
                        status = repRes.newStatus,
                        paidBy = repRes.newPaidBy
                    )
                }
            }
        }

        val appends = mutableListOf<NewRow>()

        // 3. Excess repayment balance logged to Table 1 if at least one payable was repaid
        if (remaining > Tolerances.MONEY_LEFT && repaidCount > 0) {
            val txnUuid = "txn_" + UUID.randomUUID().toString().replace("-", "").take(12)
            val debitCells = listOf(
                dateSerial,
                timeSerial,
                "Cash",
                "[Carried Over] Excess repayment balance",
                "",
                CategoryConstants.CATEGORY_OTHER,
                AccountingUtils.round2(remaining),
                creditor,
                0.0,
                AccountingUtils.round2(remaining),
                "Pending"
            )
            appends.add(NewRow(TableType.DEBIT, tabName, txnUuid, debitCells))
        }

        // 4. Return message or not found
        if (repaidCount == 0 && appends.isEmpty()) {
            return AccountingPlan(
                edits = emptyList(),
                appends = emptyList(),
                settled = emptyList(),
                excess = remaining,
                unmatched = true,
                message = "No pending payable found for $creditor"
            )
        }

        val msgBuilder = StringBuilder("Repaid $repaidCount payable(s)")
        if (remaining > Tolerances.MONEY_LEFT && repaidCount > 0) {
            msgBuilder.append(" (Overpayment of ₹${AccountingUtils.round2(remaining)} logged to Debits)")
        }

        return AccountingPlan(
            edits = allEdits,
            appends = appends,
            settled = emptyList(),
            excess = AccountingUtils.round2(remaining),
            unmatched = false,
            message = msgBuilder.toString()
        )
    }

    private fun resolveReference(ref: String, payables: List<LedgerPayable>): LedgerPayable? {
        val trimmed = ref.trim()
        if (trimmed.isBlank()) return null

        val byUuid = payables.find { it.uuid != null && AccountingUtils.eq(it.uuid, trimmed) }
        if (byUuid != null) return byUuid

        val matcher = ROW_REF_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            val tabName = matcher.group(1) ?: matcher.group(2) ?: ""
            val rowNum = matcher.group(3)?.toIntOrNull() ?: 0
            return payables.find { AccountingUtils.eq(it.tab, tabName) && it.row == rowNum }
        }

        return null
    }
}

