package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.SettledRef
import kotlin.math.abs

/**
 * What to do with a saved plan before it is sent again. The edits of a plan are written to the sheet in one call, so
 * they are all there or none of them is. The open amount of every debt the plan changes tells which: a carry never
 * changes the amount of the row it leaves behind, so the amount still proves it after the debt has moved on.
 */
object StoredPlanReplay {

    private val DEBIT_AMOUNT = SheetSchemaConstants.COL_LETTER_DEBIT_OWED_TO_ME
    private val DEBIT_PARTY = SheetSchemaConstants.COL_LETTER_DEBIT_COMPANIONS
    private val DEBIT_STATUS = SheetSchemaConstants.COL_LETTER_DEBIT_STATUS
    private val PAYABLE_AMOUNT = SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE
    private val PAYABLE_PARTY = SheetSchemaConstants.COL_LETTER_PAYABLE_PAID_BY
    private val PAYABLE_STATUS = SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS
    private val PAYABLE_COLUMNS = setOf("W", "X", "Y", "Z", "AA", "AB", "AC")
    private const val CARRIED = "Carried Fwd"

    sealed interface Outcome {
        /**
         * The edits are not on the sheet yet. [plan] is the saved plan, with the edits of a debt that has since been
         * carried pointed at its live row. [movedFrom] are the rows those debts were carried from.
         */
        data class Send(val plan: AccountingPlan, val movedFrom: List<ChainRow>) : Outcome

        /**
         * The edits are already on the sheet, so nothing is written again. [local] is the part of the plan this device
         * may still apply to itself: it never sets the status of a row that has since been carried.
         */
        data class AlreadyWritten(val local: AccountingPlan) : Outcome

        /** What was saved cannot be proven. Nothing may be written. */
        data object Unprovable : Outcome
    }

    fun isPayableColumn(column: String): Boolean = column.uppercase() in PAYABLE_COLUMNS

    /**
     * Notes on every edit the value it expects to replace, from the rows the plan was made from. This is what later
     * proves whether the edits were written.
     */
    fun withExpectedValues(plan: AccountingPlan, debits: List<LedgerDebit>, payables: List<LedgerPayable>): AccountingPlan =
        plan.copy(
            edits = plan.edits.map { edit ->
                val column = edit.column.uppercase()
                val before: Any? = if (isPayableColumn(column)) {
                    val row = payables.firstOrNull { matches(edit, it.tab, it.row, it.uuid) }
                    when (column) {
                        PAYABLE_AMOUNT -> row?.amountOwed
                        PAYABLE_PARTY -> row?.paidBy
                        PAYABLE_STATUS -> row?.status
                        else -> null
                    }
                } else {
                    val row = debits.firstOrNull { matches(edit, it.tab, it.row, it.uuid) }
                    when (column) {
                        DEBIT_AMOUNT -> row?.owed
                        DEBIT_PARTY -> row?.companions
                        DEBIT_STATUS -> row?.status
                        else -> null
                    }
                }
                edit.copy(before = before)
            }
        )

    private fun matches(edit: CellEdit, tab: String, row: Int, uuid: String?): Boolean =
        edit.tab.equals(tab, ignoreCase = true) &&
            if (!edit.uuid.isNullOrBlank()) edit.uuid.equals(uuid, ignoreCase = true) else edit.row == row

    private enum class RowState { WRITTEN, NOT_WRITTEN, MOVED, MISSING, CHANGED }

    private class Target(
        val payable: Boolean,
        val edits: List<CellEdit>,
        val current: ChainRow?,
        val state: RowState
    )

    /** [debits] and [payables] are every row of the workbook as it is now; the amount is the open amount. */
    fun check(plan: AccountingPlan, debits: List<ChainRow>, payables: List<ChainRow>): Outcome {
        val targets = plan.edits
            .groupBy { Triple(isPayableColumn(it.column), it.tab.lowercase(), it.uuid?.lowercase() ?: "row ${it.row}") }
            .map { (key, edits) -> target(key.first, edits, if (key.first) payables else debits) }
        if (targets.any { it.state == RowState.CHANGED }) return Outcome.Unprovable

        if (targets.any { it.state == RowState.WRITTEN }) {
            // One call writes every edit, so a mix of written and unwritten rows was changed by something else
            if (targets.any { it.state == RowState.NOT_WRITTEN || it.state == RowState.MOVED }) return Outcome.Unprovable
            val carried = targets.filter { it.current?.status.equals(CARRIED, ignoreCase = true) }
                .flatMap { it.edits }.toSet()
            return Outcome.AlreadyWritten(
                plan.copy(
                    appends = emptyList(),
                    edits = plan.edits.filterNot { it in carried && isStatusColumn(it.column) },
                    settled = plan.settled.filterNot { ref -> carried.any { it.tab == ref.tab && it.row == ref.row } }
                )
            )
        }

        var edits = plan.edits
        var settled = plan.settled
        val movedFrom = mutableListOf<ChainRow>()
        val takenCopies = mutableSetOf<Pair<String, Int>>()
        for (moved in targets.filter { it.state == RowState.MOVED }) {
            val original = moved.current ?: return Outcome.Unprovable
            val rows = if (moved.payable) payables else debits
            val resolution = CarriedDebtChain.resolve(original, rows, original.amount)
            val live = (resolution as? CarriedResolution.Untouched)?.copies?.lastOrNull() ?: return Outcome.Unprovable
            if (!takenCopies.add(live.tab to live.row)) return Outcome.Unprovable
            val old = moved.edits.toSet()
            edits = edits.map {
                if (it in old) it.copy(tab = live.tab, row = live.row, uuid = live.uuid.ifBlank { null }) else it
            }
            settled = settled.map { ref ->
                if (isSameRow(ref, original)) SettledRef(live.tab, live.row, live.uuid, ref.applied, ref.remaining) else ref
            }
            movedFrom.add(original)
        }
        return Outcome.Send(plan.copy(edits = edits, settled = settled), movedFrom)
    }

    private fun isStatusColumn(column: String): Boolean =
        column.uppercase() == DEBIT_STATUS || column.uppercase() == PAYABLE_STATUS

    private fun isSameRow(ref: SettledRef, row: ChainRow): Boolean =
        if (!ref.uuid.isNullOrBlank()) ref.uuid.equals(row.uuid, ignoreCase = true)
        else ref.tab.equals(row.tab, ignoreCase = true) && ref.row == row.row

    private fun target(payable: Boolean, edits: List<CellEdit>, rows: List<ChainRow>): Target {
        val first = edits.first()
        val current = rows.firstOrNull { matches(first, it.tab, it.row, it.uuid) }
        val amountEdits = edits.filter { it.column.uppercase() == if (payable) PAYABLE_AMOUNT else DEBIT_AMOUNT }
        // A row that is not in the read, or an edit of no amount, is left to the id lookup of the write itself
        if (current == null || amountEdits.isEmpty()) return Target(payable, edits, current, RowState.MISSING)

        val planned = number(amountEdits.last().value)
        val before = number(amountEdits.first().before)
        val carried = current.status.equals(CARRIED, ignoreCase = true)
        val state = when {
            planned == null -> RowState.CHANGED
            before == null -> {
                // A plan saved by an older build did not note what it replaced
                if (same(current.amount, planned)) RowState.WRITTEN
                else if (carried) RowState.CHANGED
                else RowState.NOT_WRITTEN
            }
            same(current.amount, planned) && !same(before, planned) -> RowState.WRITTEN
            !same(current.amount, before) -> RowState.CHANGED
            carried -> RowState.MOVED
            current.status.equals("Pending", ignoreCase = true) ||
                (payable && current.status.equals("Partial", ignoreCase = true)) -> RowState.NOT_WRITTEN
            else -> RowState.CHANGED
        }
        return Target(payable, edits, current, state)
    }

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun same(a: Double, b: Double): Boolean = abs(a - b) < 0.005
}
