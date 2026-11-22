package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.RowKey
import com.financeautomation.domain.model.SettledRef
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.domain.utils.Tolerances
import java.util.UUID
import java.util.regex.Pattern

data class SettlementStepResult(
    val applied: Double,
    val excess: Double,
    val remainingOwed: Double,
    val edits: List<CellEdit>,
    val usable: Boolean = true,
    val updatedCompanions: String? = null
)

/**
 * Pure domain UseCase executing the Settlement Waterfall when recording income from friends.
 * Port of Code.gs reconcileDebitsWaterfall and updateLinkedDebitSettlement.
 * Pure Kotlin with zero Android framework dependencies.
 */
class SettlementWaterfallUseCase {

    private val ROW_REF_PATTERN = Pattern.compile("^(?:'([^']+)'|([^'!]+))!(?:Row)?(\\d+)$", Pattern.CASE_INSENSITIVE)

    /**
     * Executes the settlement waterfall against available debits.
     */
    operator fun invoke(
        settler: String,
        amount: Double,
        explicitRefs: List<String> = emptyList(),
        creditDateSerial: Int,
        creditTimeSerial: Double,
        account: String,
        inflowType: String,
        reason: String,
        tabName: String,
        allDebits: List<LedgerDebit>,
        resolveCarriedFn: ((LedgerDebit) -> LedgerDebit?)? = null
    ): AccountingPlan {
        var remaining = AccountingUtils.round2(amount)
        val settled = mutableListOf<SettledRef>()
        val allEdits = mutableListOf<CellEdit>()
        val handledKeys = mutableSetOf<RowKey>()

        // Working copy of debits to reflect sequential in-memory modifications
        // Oldest first: order by date then time, undated rows last, and keep the snapshot order for ties
        val workingDebits = allDebits
            .map { it.copy() }
            .sortedWith(compareBy<LedgerDebit>({ it.dateSerial ?: Int.MAX_VALUE }, { it.timeSerial ?: Double.MAX_VALUE }))
            .toMutableList()

        fun findWorkingRow(tab: String, row: Int): Int {
            return workingDebits.indexOfFirst { it.tab == tab && it.row == row }
        }

        // 1. Process explicit references in order
        for (refStr in explicitRefs) {
            if (remaining <= Tolerances.MONEY_LEFT) break
            val targetDebit = resolveReference(refStr, workingDebits) ?: continue
            val liveDebit = if (targetDebit.status == "Carried Fwd" && resolveCarriedFn != null) {
                resolveCarriedFn(targetDebit) ?: continue
            } else {
                targetDebit
            }

            // A reference only settles a debit the settler is a companion of; otherwise skip it and carry on
            if (settler.isNotBlank() && AccountingUtils.tokens(liveDebit.companions).none { AccountingUtils.eq(it, settler) }) continue

            val key = RowKey(liveDebit.tab, liveDebit.row)
            if (handledKeys.contains(key)) continue
            handledKeys.add(key)

            val step = settleOneDebt(liveDebit, remaining, settler, allowFull = true)
            if (!step.usable) continue

            if (step.applied > 0.0) {
                settled.add(SettledRef(liveDebit.tab, liveDebit.row, liveDebit.uuid, step.applied, step.remainingOwed))
                allEdits.addAll(step.edits)
                remaining = step.excess

                val idx = findWorkingRow(liveDebit.tab, liveDebit.row)
                if (idx >= 0) {
                    workingDebits[idx] = liveDebit.copy(
                        owed = step.remainingOwed,
                        status = if (step.remainingOwed == 0.0) "Settled" else "Pending",
                        companions = step.updatedCompanions ?: liveDebit.companions
                    )
                }
            }
        }

        // 2. FIFO waterfall through remaining debits if money remains and settler is specified
        if (remaining > Tolerances.MONEY_LEFT && settler.isNotBlank()) {
            for (i in workingDebits.indices) {
                if (remaining <= Tolerances.MONEY_LEFT) break
                val debit = workingDebits[i]
                val key = RowKey(debit.tab, debit.row)
                if (handledKeys.contains(key)) continue
                if (debit.status != "Pending" || debit.owed <= 0.0) continue

                val companionTokens = AccountingUtils.tokens(debit.companions)
                if (!companionTokens.any { AccountingUtils.eq(it, settler) }) continue

                handledKeys.add(key)
                val step = settleOneDebt(debit, remaining, settler, allowFull = false)
                if (step.applied > 0.0) {
                    settled.add(SettledRef(debit.tab, debit.row, debit.uuid, step.applied, step.remainingOwed))
                    allEdits.addAll(step.edits)
                    remaining = step.excess

                    workingDebits[i] = debit.copy(
                        owed = step.remainingOwed,
                        status = if (step.remainingOwed == 0.0) "Settled" else "Pending",
                        companions = step.updatedCompanions ?: debit.companions
                    )
                }
            }
        }

        val appends = mutableListOf<NewRow>()
        val unmatched = remaining > Tolerances.MONEY_LEFT && settled.isEmpty()

        // 3. Excess settlement credit row in Table 3 if surplus remains and debts were settled
        if (remaining > Tolerances.MONEY_LEFT && settler.isNotBlank() && settled.isNotEmpty()) {
            val payUuid = "pay_" + UUID.randomUUID().toString().replace("-", "").take(12)
            val payCells = listOf(
                creditDateSerial,
                creditTimeSerial,
                settler,
                "Excess settlement credit",
                AccountingUtils.round2(remaining),
                "",
                "Pending"
            )
            appends.add(NewRow(TableType.PAYABLE, tabName, payUuid, payCells))
        }

        // 4. Inflow credit row in Table 2
        val infUuid = "inf_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val linkedDebitRefs = settled.joinToString(", ") { it.uuid ?: "${it.tab}!Row${it.row}" }
        val inflowCells = listOf(
            creditDateSerial,
            creditTimeSerial,
            account,
            inflowType,
            settler,
            reason,
            AccountingUtils.round2(amount),
            linkedDebitRefs
        )
        appends.add(NewRow(TableType.INFLOW, tabName, infUuid, inflowCells))

        // 5. Construct user message matching Code.gs
        val msgBuilder = StringBuilder("Credit logged successfully")
        if (settled.isNotEmpty()) {
            msgBuilder.append(" (${settled.size} debt(s) reconciled)")
        }
        if (remaining > Tolerances.MONEY_LEFT && settled.isNotEmpty()) {
            msgBuilder.append(" (Excess of ₹${AccountingUtils.round2(remaining)} logged to Payables)")
        } else if (unmatched) {
            msgBuilder.append(" (No open debts found for $settler; credit left unlinked)")
        }

        return AccountingPlan(
            edits = allEdits,
            appends = appends,
            settled = settled,
            excess = AccountingUtils.round2(remaining),
            unmatched = unmatched,
            message = msgBuilder.toString()
        )
    }

    /**
     * Settle a single debt row. Port of updateLinkedDebitSettlement.
     */
    fun settleOneDebt(
        row: LedgerDebit,
        paid: Double,
        settler: String,
        allowFull: Boolean
    ): SettlementStepResult {
        if (row.status == "Carried Fwd") {
            return SettlementStepResult(0.0, AccountingUtils.round2(paid), row.owed, emptyList(), usable = false)
        }
        if (row.status != "Pending" || row.owed <= 0.0) {
            return SettlementStepResult(0.0, AccountingUtils.round2(paid), row.owed, emptyList(), usable = true)
        }

        val list = AccountingUtils.tokens(row.companions)
        val matchIdx = if (settler.isNotBlank() && list.size > 1) {
            list.indexOfFirst { AccountingUtils.eq(it, settler) }
        } else {
            -1
        }
        val isShared = matchIdx >= 0
        val share = if (isShared) AccountingUtils.round2(row.owed / list.size) else row.owed

        var applied = minOf(paid, row.owed)
        if (isShared && (!allowFull || paid < row.owed - Tolerances.PAYMENT_COVERS_WHOLE)) {
            applied = minOf(applied, share)
        }
        applied = AccountingUtils.round2(applied)

        val edits = mutableListOf<CellEdit>()
        var updatedCompanions: String? = null

        if (isShared) {
            if (applied >= row.owed - Tolerances.SHARE_FULLY_COVERED) {
                updatedCompanions = settler
                edits.add(CellEdit(row.tab, row.row, "H", settler, row.uuid))
            } else if (applied >= share - Tolerances.SHARE_FULLY_COVERED) {
                val copy = list.toMutableList()
                copy.removeAt(matchIdx)
                val joined = copy.joinToString(", ")
                updatedCompanions = joined
                edits.add(CellEdit(row.tab, row.row, "H", joined, row.uuid))
            }
        }

        val remainingOwed = maxOf(0.0, AccountingUtils.round2(row.owed - applied))
        val excess = maxOf(0.0, AccountingUtils.round2(paid - applied))

        edits.add(CellEdit(row.tab, row.row, "J", remainingOwed, row.uuid))
        edits.add(CellEdit(row.tab, row.row, "K", if (remainingOwed == 0.0) "Settled" else "Pending", row.uuid))

        return SettlementStepResult(
            applied = applied,
            excess = excess,
            remainingOwed = remainingOwed,
            edits = edits,
            usable = true,
            updatedCompanions = updatedCompanions
        )
    }

    private fun resolveReference(ref: String, debits: List<LedgerDebit>): LedgerDebit? {
        val trimmed = ref.trim()
        if (trimmed.isBlank()) return null

        // 1. Direct UUID match
        val byUuid = debits.find { it.uuid != null && AccountingUtils.eq(it.uuid, trimmed) }
        if (byUuid != null) return byUuid

        // 2. Tab!RowN or 'Tab'!RowN format
        val matcher = ROW_REF_PATTERN.matcher(trimmed)
        if (matcher.matches()) {
            val tabName = (matcher.group(1) ?: matcher.group(2) ?: "").trim()
            val rowNum = matcher.group(3)?.toIntOrNull() ?: 0
            return debits.find { AccountingUtils.eq(it.tab, tabName) && it.row == rowNum }
        }

        return null
    }
}

