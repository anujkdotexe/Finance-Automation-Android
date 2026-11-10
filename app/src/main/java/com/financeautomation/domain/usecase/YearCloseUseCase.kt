package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import java.time.Clock
import java.time.Instant

/**
 * Statement model produced by YearCloseUseCase containing formatted rows for the CarryFwd tab
 * and the serialized JSON string payload to back up to Google Drive.
 */
data class YearCloseStatement(
    val year: Int,
    val generatedAt: Instant,
    val carryFwdSheetRows: List<List<Any?>>,
    val jsonContent: String,
    val receivableCount: Int,
    val payableCount: Int
)

/**
 * Pure domain UseCase compiling year-end unresolved balances statement and Drive backup JSON.
 * Port of handleYearClose from Code.gs v2.3.0 and spec section 3.5.
 * Pure Kotlin with zero Android framework dependencies and zero I/O.
 */
class YearCloseUseCase(
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Compiles unresolved receivables (Debits with status Pending and owed > 0)
     * and unresolved payables (Payables with status Pending or Partial and amountOwed > 0)
     * across all monthly tabs into a structured YearCloseStatement.
     */
    operator fun invoke(
        year: Int,
        allDebits: List<LedgerDebit>,
        allPayables: List<LedgerPayable>
    ): YearCloseStatement {
        val now = Instant.now(clock)
        val isoTimestamp = now.toString()

        val unresolvedReceivables = allDebits.filter {
            it.status == "Pending" && it.owed > 0.0
        }

        val unresolvedPayables = allPayables.filter {
            (it.status == "Pending" || it.status == "Partial") && it.amountOwed > 0.0
        }

        // Build CarryFwd tab rows
        val sheetRows = mutableListOf<List<Any?>>()
        sheetRows.add(listOf("YEAR-END UNRESOLVED BALANCES STATEMENT"))
        sheetRows.add(listOf("Generated at:", isoTimestamp))
        sheetRows.add(emptyList())

        sheetRows.add(listOf("--- RECEIVABLES (PEOPLE OWE ME) ---"))
        sheetRows.add(listOf("Source Tab", "Date Serial", "Item", "Trip Tag", "Companions", "Amount Owed"))
        for (r in unresolvedReceivables) {
            sheetRows.add(
                listOf(
                    r.tab,
                    r.dateSerial ?: "",
                    r.item,
                    r.tripTag.orEmpty(),
                    r.companions,
                    r.owed
                )
            )
        }

        sheetRows.add(emptyList())
        sheetRows.add(listOf("--- PAYABLES (I OWE PEOPLE) ---"))
        sheetRows.add(listOf("Source Tab", "Date Serial", "Paid By", "What For", "Amount Owed"))
        for (p in unresolvedPayables) {
            sheetRows.add(
                listOf(
                    p.tab,
                    p.dateIncurredSerial ?: "",
                    p.paidBy,
                    p.whatFor,
                    p.amountOwed
                )
            )
        }

        // Build JSON content matching Code.gs structure
        val jsonBuilder = StringBuilder()
        jsonBuilder.append("{\n")
        jsonBuilder.append("  \"carry_year\": \"$year\",\n")
        jsonBuilder.append("  \"generated_at\": \"$isoTimestamp\",\n")

        jsonBuilder.append("  \"receivables\": [\n")
        unresolvedReceivables.forEachIndexed { index, r ->
            jsonBuilder.append("    {\n")
            jsonBuilder.append("      \"sourceSheet\": \"${escapeJson(r.tab)}\",\n")
            jsonBuilder.append("      \"rowNumber\": ${r.row},\n")
            jsonBuilder.append("      \"dateSerial\": ${r.dateSerial ?: 0},\n")
            jsonBuilder.append("      \"item\": \"${escapeJson(r.item)}\",\n")
            jsonBuilder.append("      \"tripTag\": \"${escapeJson(r.tripTag.orEmpty())}\",\n")
            jsonBuilder.append("      \"companions\": \"${escapeJson(r.companions)}\",\n")
            jsonBuilder.append("      \"amountOwed\": ${r.owed}\n")
            jsonBuilder.append("    }${if (index < unresolvedReceivables.size - 1) "," else ""}\n")
        }
        jsonBuilder.append("  ],\n")

        jsonBuilder.append("  \"payables\": [\n")
        unresolvedPayables.forEachIndexed { index, p ->
            jsonBuilder.append("    {\n")
            jsonBuilder.append("      \"sourceSheet\": \"${escapeJson(p.tab)}\",\n")
            jsonBuilder.append("      \"rowNumber\": ${p.row},\n")
            jsonBuilder.append("      \"dateSerial\": ${p.dateIncurredSerial ?: 0},\n")
            jsonBuilder.append("      \"paidBy\": \"${escapeJson(p.paidBy)}\",\n")
            jsonBuilder.append("      \"whatFor\": \"${escapeJson(p.whatFor)}\",\n")
            jsonBuilder.append("      \"amountOwed\": ${p.amountOwed}\n")
            jsonBuilder.append("    }${if (index < unresolvedPayables.size - 1) "," else ""}\n")
        }
        jsonBuilder.append("  ]\n")
        jsonBuilder.append("}")

        return YearCloseStatement(
            year = year,
            generatedAt = now,
            carryFwdSheetRows = sheetRows,
            jsonContent = jsonBuilder.toString(),
            receivableCount = unresolvedReceivables.size,
            payableCount = unresolvedPayables.size
        )
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

