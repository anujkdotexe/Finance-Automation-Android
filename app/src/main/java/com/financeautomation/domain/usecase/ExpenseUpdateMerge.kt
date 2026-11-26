package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.EditBlockReason
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.utils.AccountingUtils
import kotlin.math.abs

/** The cells A to L of one expense row. A null date or time is a blank cell. */
data class ExpenseRow(
    val dateSerial: Int?,
    val timeSerial: Double?,
    val account: String,
    val description: String,
    val tripTag: String,
    val category: String,
    val total: Double,
    val companions: String,
    val myShare: Double,
    val owed: Double,
    val status: String,
    val note: String
) {
    /** The value to write into one column, 0 for A up to 11 for L. */
    fun cell(column: Int): Any = when (column) {
        0 -> dateSerial ?: ""
        1 -> timeSerial ?: ""
        2 -> account
        3 -> description
        4 -> tripTag
        5 -> category
        6 -> total
        7 -> companions
        8 -> myShare
        9 -> owed
        10 -> status
        else -> if (note.trimStart().startsWith("=")) "'$note" else note
    }

    companion object {
        fun of(transaction: Transaction) = ExpenseRow(
            dateSerial = transaction.dateSerial,
            timeSerial = transaction.timeSerial,
            account = transaction.account,
            description = transaction.merchant,
            tripTag = transaction.tripTag,
            category = CategoryConstants.normalizeCategory(transaction.category),
            total = transaction.totalAmount,
            companions = transaction.companions,
            myShare = transaction.myShare,
            owed = transaction.owedToMe,
            status = transaction.status.value,
            note = transaction.note
        )

        /**
         * Reads the cells A to L as they came from the sheet; cells after the last filled one are absent. Null when
         * this cannot be an expense row read in full: no date, no total, or an amount that is not a number. A blank
         * share or owed cell is zero.
         */
        fun fromCells(cells: List<Any?>): ExpenseRow? {
            fun text(column: Int): String = cells.getOrNull(column)?.toString().orEmpty().trim()
            fun amount(column: Int): Double? = if (text(column).isEmpty()) 0.0 else text(column).toDoubleOrNull()

            val date = text(0).toDoubleOrNull()?.toInt() ?: return null
            val time = if (text(1).isEmpty()) null else text(1).toDoubleOrNull() ?: return null
            val total = text(6).toDoubleOrNull() ?: return null
            val myShare = amount(8) ?: return null
            val owed = amount(9) ?: return null
            return ExpenseRow(
                dateSerial = date,
                timeSerial = time,
                account = text(2),
                description = text(3),
                tripTag = text(4),
                category = text(5),
                total = total,
                companions = text(7),
                myShare = myShare,
                owed = owed,
                status = text(10),
                note = text(11)
            )
        }
    }
}

/** An edit that waits to be sent: what the sheet may still show, what the user wants and which cells they changed. */
data class PendingExpenseEdit(
    /** The rows this device had before each edit that is still waiting, oldest first. */
    val seen: List<ExpenseRow>,
    val want: ExpenseRow,
    /** Columns the user changed, 0 for A up to 11 for L. */
    val changed: Set<Int>
)

/**
 * Decides what an edit may write. Only cells the user changed are written, so nothing else on the sheet is
 * overwritten. Amounts, split and status (G to K) are written together, and only while the sheet still shows a state
 * this device has seen. Otherwise the sheet changed somewhere else, and an edit of amounts or of the date, time or
 * description (what matches a debt to its carried copy) is refused as a whole.
 */
object ExpenseUpdateMerge {

    val MONEY_COLUMNS: Set<Int> = setOf(6, 7, 8, 9, 10)
    val MATCHING_COLUMNS: Set<Int> = setOf(0, 1, 3)
    const val NOTE_COLUMN: Int = 11
    private const val LAST_COLUMN = 11

    sealed interface Outcome {
        /** The cells to write; empty when nothing on the sheet has to change. */
        data class Write(val columns: Set<Int>) : Outcome

        data class Refused(val reason: EditBlockReason) : Outcome
    }

    /** The columns that differ between two states of a row, comparing amounts, people and status by meaning. */
    fun changedColumns(before: ExpenseRow, after: ExpenseRow): Set<Int> =
        (0..LAST_COLUMN).filterNot { sameColumn(it, before, after) }.toSet()

    fun decide(edit: PendingExpenseEdit, sheet: ExpenseRow): Outcome {
        val userMoney = edit.changed.any { it in MONEY_COLUMNS }
        val userMatching = edit.changed.any { it in MATCHING_COLUMNS }
        if (userMoney || userMatching) {
            val alreadyThere = sameMoney(edit.want, sheet) && sameMatching(edit.want, sheet)
            if (!alreadyThere) {
                if (isCarried(sheet)) return Outcome.Refused(EditBlockReason.DEBT_CARRIED)
                if ((edit.seen + edit.want).none { sameMoney(it, sheet) }) {
                    return Outcome.Refused(EditBlockReason.SHEET_CHANGED)
                }
            }
        }
        val others = edit.changed - MONEY_COLUMNS
        return Outcome.Write(if (userMoney && !isCarried(sheet)) others + MONEY_COLUMNS else others)
    }

    /**
     * An edit queued by an older build says neither what it saw nor what the user changed. It is sent only while the
     * sheet shows the same amounts, split and status as this device, and those cells are left as they are.
     */
    fun decideWithoutHistory(want: ExpenseRow, sheet: ExpenseRow): Outcome {
        if (isCarried(sheet) && !(sameMoney(want, sheet) && sameMatching(want, sheet))) {
            return Outcome.Refused(EditBlockReason.DEBT_CARRIED)
        }
        if (!sameMoney(want, sheet)) return Outcome.Refused(EditBlockReason.SHEET_CHANGED)
        return Outcome.Write((0..LAST_COLUMN).toSet() - MONEY_COLUMNS)
    }

    fun sameMoney(a: ExpenseRow, b: ExpenseRow): Boolean = MONEY_COLUMNS.all { sameColumn(it, a, b) }

    private fun sameMatching(a: ExpenseRow, b: ExpenseRow): Boolean = MATCHING_COLUMNS.all { sameColumn(it, a, b) }

    private fun isCarried(row: ExpenseRow): Boolean = statusOf(row) == TransactionStatus.CARRIED_FWD

    private fun sameColumn(column: Int, a: ExpenseRow, b: ExpenseRow): Boolean = when (column) {
        0 -> a.dateSerial == b.dateSerial
        1 -> sameTime(a.timeSerial, b.timeSerial)
        2 -> a.account.trim() == b.account.trim()
        3 -> a.description.trim() == b.description.trim()
        4 -> a.tripTag.trim() == b.tripTag.trim()
        5 -> a.category.trim() == b.category.trim()
        6 -> sameAmount(a.total, b.total)
        7 -> people(a.companions) == people(b.companions)
        8 -> sameAmount(a.myShare, b.myShare)
        9 -> sameAmount(a.owed, b.owed)
        10 -> statusOf(a) == statusOf(b)
        else -> a.note.trim().removePrefix("'") == b.note.trim().removePrefix("'")
    }

    private fun sameTime(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) a == b else abs(a - b) < AccountingUtils.LEGACY_TIME_SERIAL_TOLERANCE

    private fun sameAmount(a: Double, b: Double): Boolean = abs(a - b) < 0.005

    private fun people(companions: String): List<String> =
        AccountingUtils.tokens(companions).map { it.trim().lowercase() }.filter { it != "personal" }.sorted()

    /** Reads a status the way the local table does: an unknown word with money still owed is not Personal. */
    private fun statusOf(row: ExpenseRow): TransactionStatus {
        val parsed = TransactionStatus.fromString(row.status)
        val unknown = parsed == TransactionStatus.PERSONAL && !row.status.trim().equals("Personal", ignoreCase = true)
        return if (unknown && row.owed > 0.0) TransactionStatus.PENDING else parsed
    }
}
