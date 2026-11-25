package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.LineageRecord
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.utils.AccountingUtils
import java.util.UUID

data class CarryForwardResult(
    val plan: AccountingPlan,
    val lineageRecords: List<LineageRecord>,
    val skippedAlreadyCarried: Boolean = false
)

/**
 * Pure domain UseCase executing monthly debt and payable rollover into a new month tab.
 * Port of Code.gs carryForwardUnsettled and resolveCarriedRow_.
 * Pure Kotlin with zero Android framework dependencies.
 */
class CarryForwardUseCase {

    /**
     * Resolves a 'Carried Fwd' debit row to its ultimate active live row (up to 12 hops).
     */
    fun resolveCarriedDebit(
        initialRow: LedgerDebit,
        allDebits: List<LedgerDebit>,
        lineageRecords: List<LineageRecord> = emptyList(),
        maxHops: Int = 12
    ): LedgerDebit {
        var current = initialRow
        var hops = 0

        while (current.status == "Carried Fwd" && hops < maxHops) {
            hops++
            val currentUuid = current.uuid ?: "${current.tab}!Row${current.row}"

            // 1. Try lineage lookup
            val lineage = lineageRecords.find { AccountingUtils.eq(it.carriedFromUuid, currentUuid) }
            if (lineage != null) {
                val next = allDebits.find { it.uuid != null && AccountingUtils.eq(it.uuid, lineage.uuid) }
                if (next != null) {
                    current = next
                    continue
                }
            }

            // 2. Fallback to description prefix matching: [Carried from <current.tab>]
            val targetPrefix = "[Carried from ${current.tab}]"
            val nextByDesc = allDebits.find { debit ->
                debit.tab != current.tab &&
                        debit.item.startsWith(targetPrefix, ignoreCase = true) &&
                        AccountingUtils.eq(CarriedCopies.stripPrefixes(debit.item), CarriedCopies.stripPrefixes(current.item))
            }

            if (nextByDesc != null) {
                current = nextByDesc
            } else {
                break
            }
        }
        return current
    }

    /**
     * Resolves a 'Carried Fwd' payable row to its ultimate active live row (up to 12 hops).
     */
    fun resolveCarriedPayable(
        initialRow: LedgerPayable,
        allPayables: List<LedgerPayable>,
        lineageRecords: List<LineageRecord> = emptyList(),
        maxHops: Int = 12
    ): LedgerPayable {
        var current = initialRow
        var hops = 0

        while (current.status == "Carried Fwd" && hops < maxHops) {
            hops++
            val currentUuid = current.uuid ?: "${current.tab}!Row${current.row}"

            val lineage = lineageRecords.find { AccountingUtils.eq(it.carriedFromUuid, currentUuid) }
            if (lineage != null) {
                val next = allPayables.find { it.uuid != null && AccountingUtils.eq(it.uuid, lineage.uuid) }
                if (next != null) {
                    current = next
                    continue
                }
            }

            val targetPrefix = "[Carried from ${current.tab}]"
            val nextByDesc = allPayables.find { payable ->
                payable.tab != current.tab &&
                        payable.whatFor.startsWith(targetPrefix, ignoreCase = true) &&
                        AccountingUtils.eq(CarriedCopies.stripPrefixes(payable.whatFor), CarriedCopies.stripPrefixes(current.whatFor))
            }

            if (nextByDesc != null) {
                current = nextByDesc
            } else {
                break
            }
        }
        return current
    }

    /**
     * Carries forward all open debits and open payables from [sourceTab] into [targetTab].
     * [targetDebits] and [targetPayables] are the rows that may already hold copies: the target tab and any tab
     * between the source and the target.
     */
    operator fun invoke(
        targetTab: String,
        sourceTab: String,
        targetDebits: List<LedgerDebit>,
        targetPayables: List<LedgerPayable>,
        sourceDebits: List<LedgerDebit>,
        sourcePayables: List<LedgerPayable>,
        existingLineage: List<LineageRecord> = emptyList()
    ): CarryForwardResult {
        val prefix = "[Carried from $sourceTab]"
        // Idempotent per source row, not per tab: a row is skipped when it already has a lineage record. A copy
        // (same text and amount) belongs to exactly one source row, so two identical debts are not mistaken for one.
        // An open row whose copy exists is only marked, which finishes a run that wrote the copy but not the mark.
        // Everything else that is open is carried, so a debt logged after the first run is still picked up.
        val carriedFrom = existingLineage.mapNotNull { it.carriedFromUuid }.map { it.trim().lowercase() }.toSet()
        val freeDebitCopies = targetDebits.toMutableList()
        val freePayableCopies = targetPayables.toMutableList()
        fun hasLineage(uuid: String?, tab: String, row: Int): Boolean =
            (uuid ?: "$tab!Row$row").trim().lowercase() in carriedFrom
        fun claimDebitCopy(d: LedgerDebit): LedgerDebit? {
            val text = "$prefix ${CarriedCopies.stripPrefixes(d.item)}"
            val index = freeDebitCopies.indexOfFirst {
                it.item.equals(text, ignoreCase = true) && Math.abs(it.owed - d.owed) < 0.005
            }
            return if (index >= 0) freeDebitCopies.removeAt(index) else null
        }
        fun claimPayableCopy(p: LedgerPayable): LedgerPayable? {
            val text = "$prefix ${CarriedCopies.stripPrefixes(p.whatFor)}"
            val index = freePayableCopies.indexOfFirst {
                it.whatFor.equals(text, ignoreCase = true) && Math.abs(it.amountOwed - p.amountOwed) < 0.005
            }
            return if (index >= 0) freePayableCopies.removeAt(index) else null
        }
        // Rows already marked as carried take their own copies first
        sourceDebits.filter { it.status == "Carried Fwd" }.forEach { claimDebitCopy(it) }
        sourcePayables.filter { it.status == "Carried Fwd" }.forEach { claimPayableCopy(it) }

        val appends = mutableListOf<NewRow>()
        val edits = mutableListOf<CellEdit>()
        val lineage = mutableListOf<LineageRecord>()
        val now = System.currentTimeMillis()

        // 1. Rollover open debits (status == Pending and owed > 0)
        for (debit in sourceDebits) {
            if (debit.status == "Pending" && debit.owed > 0.0 && !hasLineage(debit.uuid, debit.tab, debit.row)) {
                val existingCopy = claimDebitCopy(debit)
                if (existingCopy != null) {
                    edits.add(CellEdit(debit.tab, debit.row, "K", "Carried Fwd", debit.uuid))
                    val sourceKey = debit.uuid ?: "${debit.tab}!Row${debit.row}"
                    existingCopy.uuid?.let { lineage.add(LineageRecord(it, sourceKey, sourceKey, now)) }
                    continue
                }
                val newUuid = "txn_" + UUID.randomUUID().toString().replace("-", "").take(12)
                val sourceUuid = debit.uuid ?: "${debit.tab}!Row${debit.row}"

                val newCells = listOf(
                    debit.dateSerial,
                    debit.timeSerial,
                    debit.account,
                    "$prefix ${CarriedCopies.stripPrefixes(debit.item)}",
                    debit.tripTag.orEmpty(),
                    debit.category,
                    AccountingUtils.round2(debit.owed),
                    debit.companions,
                    0.0,
                    AccountingUtils.round2(debit.owed),
                    "Pending"
                )

                appends.add(NewRow(TableType.DEBIT, targetTab, newUuid, newCells))
                edits.add(CellEdit(debit.tab, debit.row, "K", "Carried Fwd", debit.uuid))

                lineage.add(
                    LineageRecord(
                        uuid = newUuid,
                        carriedFromUuid = sourceUuid,
                        rootUuid = sourceUuid,
                        createdAt = now
                    )
                )
            }
        }

        // 2. Rollover open payables (status in (Pending, Partial) and amountOwed > 0)
        for (payable in sourcePayables) {
            if ((payable.status == "Pending" || payable.status == "Partial") && payable.amountOwed > 0.0 &&
                !hasLineage(payable.uuid, payable.tab, payable.row)
            ) {
                val existingCopy = claimPayableCopy(payable)
                if (existingCopy != null) {
                    edits.add(
                        CellEdit(payable.tab, payable.row, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, "Carried Fwd", payable.uuid)
                    )
                    val sourceKey = payable.uuid ?: "${payable.tab}!Row${payable.row}"
                    existingCopy.uuid?.let { lineage.add(LineageRecord(it, sourceKey, sourceKey, now)) }
                    continue
                }
                val newUuid = "pay_" + UUID.randomUUID().toString().replace("-", "").take(12)
                val sourceUuid = payable.uuid ?: "${payable.tab}!Row${payable.row}"

                val newCells = listOf(
                    payable.dateIncurredSerial,
                    payable.timeSerial,
                    payable.paidBy,
                    "$prefix ${CarriedCopies.stripPrefixes(payable.whatFor)}",
                    AccountingUtils.round2(payable.amountOwed),
                    "",
                    "Pending"
                )

                appends.add(NewRow(TableType.PAYABLE, targetTab, newUuid, newCells))
                edits.add(CellEdit(payable.tab, payable.row, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, "Carried Fwd", payable.uuid))

                lineage.add(
                    LineageRecord(
                        uuid = newUuid,
                        carriedFromUuid = sourceUuid,
                        rootUuid = sourceUuid,
                        createdAt = now
                    )
                )
            }
        }

        val plan = AccountingPlan(
            edits = edits,
            appends = appends,
            settled = emptyList(),
            excess = 0.0,
            unmatched = false,
            message = "Carried forward ${appends.size} item(s) from $sourceTab into $targetTab"
        )

        return CarryForwardResult(
            plan = plan,
            lineageRecords = lineage,
            skippedAlreadyCarried = false
        )
    }
}

