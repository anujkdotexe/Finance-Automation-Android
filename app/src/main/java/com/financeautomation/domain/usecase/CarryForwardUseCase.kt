package com.financeautomation.domain.usecase

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
                        AccountingUtils.eq(debit.item.removePrefix(targetPrefix).trim(), current.item.removePrefix("[Carried from ").substringAfter("] ").trim())
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
                        AccountingUtils.eq(payable.whatFor.removePrefix(targetPrefix).trim(), current.whatFor.removePrefix("[Carried from ").substringAfter("] ").trim())
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
        // Idempotent per source row, not per tab: a row is skipped when it already has a lineage record, or when
        // the target already holds its copy (same text and amount). Everything else that is open is carried,
        // so a debt logged after the first run is still picked up.
        val carriedFrom = existingLineage.mapNotNull { it.carriedFromUuid }.map { it.trim().lowercase() }.toSet()
        fun debitAlreadyCarried(d: LedgerDebit): Boolean {
            val key = (d.uuid ?: "${d.tab}!Row${d.row}").trim().lowercase()
            if (key in carriedFrom) return true
            val text = "$prefix ${d.item}"
            return targetDebits.any {
                it.item.equals(text, ignoreCase = true) && Math.abs(it.owed - d.owed) < 0.005
            }
        }
        fun payableAlreadyCarried(p: LedgerPayable): Boolean {
            val key = (p.uuid ?: "${p.tab}!Row${p.row}").trim().lowercase()
            if (key in carriedFrom) return true
            val text = "$prefix ${p.whatFor}"
            return targetPayables.any {
                it.whatFor.equals(text, ignoreCase = true) && Math.abs(it.amountOwed - p.amountOwed) < 0.005
            }
        }

        val appends = mutableListOf<NewRow>()
        val edits = mutableListOf<CellEdit>()
        val lineage = mutableListOf<LineageRecord>()
        val now = System.currentTimeMillis()

        // 1. Rollover open debits (status == Pending and owed > 0)
        for (debit in sourceDebits) {
            if (debit.status == "Pending" && debit.owed > 0.0 && !debitAlreadyCarried(debit)) {
                val newUuid = "txn_" + UUID.randomUUID().toString().replace("-", "").take(12)
                val sourceUuid = debit.uuid ?: "${debit.tab}!Row${debit.row}"

                val newCells = listOf(
                    debit.dateSerial,
                    debit.timeSerial,
                    debit.account,
                    "$prefix ${debit.item}",
                    debit.tripTag.orEmpty(),
                    debit.category,
                    AccountingUtils.round2(debit.owed),
                    debit.companions,
                    0.0,
                    AccountingUtils.round2(debit.owed),
                    "Pending"
                )

                appends.add(NewRow(TableType.DEBIT, targetTab, newUuid, newCells))
                edits.add(CellEdit(debit.tab, debit.row, "K", "Carried Fwd"))

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
            if ((payable.status == "Pending" || payable.status == "Partial") && payable.amountOwed > 0.0 && !payableAlreadyCarried(payable)) {
                val newUuid = "pay_" + UUID.randomUUID().toString().replace("-", "").take(12)
                val sourceUuid = payable.uuid ?: "${payable.tab}!Row${payable.row}"

                val newCells = listOf(
                    payable.dateIncurredSerial,
                    payable.timeSerial,
                    payable.paidBy,
                    "$prefix ${payable.whatFor}",
                    AccountingUtils.round2(payable.amountOwed),
                    "",
                    "Pending"
                )

                appends.add(NewRow(TableType.PAYABLE, targetTab, newUuid, newCells))
                edits.add(CellEdit(payable.tab, payable.row, "AB", "Carried Fwd"))

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

