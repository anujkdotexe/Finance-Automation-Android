package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.stripVoidPrefix
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.InflowRepository
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the CSV text for one calendar month (Asia/Kolkata), for sharing or backup.
 *
 * Layout: the expense section comes first with the columns
 * Date, Time, Description, Category, Account, Amount, My share, Owed to me, Status, Companions.
 * Income and refund entries follow in a second section, separated by one blank line and introduced by a title
 * line "Income and refunds" and its own header row (Date, Time, Source, Reason, Type, Account, Amount). A second
 * section keeps the expense columns clean for spreadsheets and avoids empty columns on income rows.
 *
 * Void rows are excluded. Cells are escaped for commas, quotes and line breaks, and text cells starting with
 * = + - @ (or a tab or carriage return) get a leading single quote so spreadsheet apps never run them as formulas.
 * Pure Kotlin with zero Android framework dependencies.
 */
class ExportMonthCsvUseCase(
    private val transactionRepository: TransactionRepository,
    private val inflowRepository: InflowRepository,
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
) {

    suspend operator fun invoke(year: Int, month: Int): String {
        val transactions = transactionRepository.getTransactions().first()
        val inflows = inflowRepository.getAllInflows().first()
        return build(year, month, transactions, inflows)
    }

    /** Pure builder, separate from the repositories so it can be tested with plain lists. */
    fun build(year: Int, month: Int, transactions: List<Transaction>, inflows: List<Inflow>): String {
        val ym = YearMonth.of(year, month)
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val expenseRows = transactions
            .filter { it.timestamp in start until end && it.status != TransactionStatus.VOID && !CarriedCopies.isCopy(it) }
            .sortedBy { it.timestamp }
        val inflowRows = inflows
            .filter { it.timestamp in start until end && !it.status.equals("VOID", ignoreCase = true) }
            .sortedBy { it.timestamp }

        val out = StringBuilder()
        out.appendLine(row(EXPENSE_HEADER))
        for (t in expenseRows) {
            out.appendLine(
                row(
                    listOf(
                        date(t.timestamp), time(t.timestamp), text(stripVoidPrefix(t.merchant)), text(t.category), text(t.account),
                        money(t.totalAmount), money(t.myShare), money(t.owedToMe), text(t.status.value),
                        text(t.companions)
                    )
                )
            )
        }
        out.appendLine()
        out.appendLine(row(listOf(INFLOW_TITLE)))
        out.appendLine(row(INFLOW_HEADER))
        for (i in inflowRows) {
            out.appendLine(
                row(
                    listOf(
                        date(i.timestamp), time(i.timestamp), text(stripVoidPrefix(i.source)), text(i.note), text(i.inflowType),
                        text(i.account), money(i.amount)
                    )
                )
            )
        }
        return out.toString()
    }

    private fun date(ts: Long): String = DATE.format(Instant.ofEpochMilli(ts).atZone(zone))
    private fun time(ts: Long): String = TIME.format(Instant.ofEpochMilli(ts).atZone(zone))

    private fun money(v: Double): String =
        BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** Text cell: injection guard applied, escaping done later in [row]. */
    private fun text(raw: String): String = guard(raw)

    private fun row(cells: List<String>): String = cells.joinToString(",") { escape(it) }

    companion object {
        val EXPENSE_HEADER = listOf(
            "Date", "Time", "Description", "Category", "Account", "Amount", "My share", "Owed to me", "Status",
            "Companions"
        )
        const val INFLOW_TITLE = "Income and refunds"
        val INFLOW_HEADER = listOf("Date", "Time", "Source", "Reason", "Type", "Account", "Amount")

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
        private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

        /** Prefixes a single quote when the text could be read as a spreadsheet formula. */
        fun guard(raw: String): String {
            val trimmed = raw.trimStart(' ')
            return if (trimmed.isNotEmpty() && trimmed[0] in FORMULA_TRIGGERS) "'$raw" else raw
        }

        /** RFC 4180 style escaping: quote the cell when it holds a comma, quote or line break. */
        fun escape(cell: String): String {
            val needsQuotes = cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
            return if (needsQuotes) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
        }
    }
}
