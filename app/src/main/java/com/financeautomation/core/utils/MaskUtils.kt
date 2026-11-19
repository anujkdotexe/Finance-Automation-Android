package com.financeautomation.core.utils

/** Masks personal identifiers before they leave the app in shared text. */
object MaskUtils {

    private const val NOT_SET = "Not set"

    /** jane.doe@example.com becomes j***@example.com. */
    fun maskEmail(email: String?): String {
        val trimmed = email?.trim().orEmpty()
        if (trimmed.isEmpty()) return NOT_SET
        val at = trimmed.indexOf('@')
        if (at <= 0) return "***"
        return trimmed.first() + "***" + trimmed.substring(at)
    }

    /** Keeps the first and last four characters of a long id. */
    fun maskId(id: String?): String {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return NOT_SET
        if (trimmed.length <= 12) return "***"
        return trimmed.take(4) + "..." + trimmed.takeLast(4)
    }

    fun diagnosticsShareText(email: String?, spreadsheetId: String?): String =
        "Finance Automation Diagnostics Dump\nAccount: ${maskEmail(email)}\nSpreadsheet: ${maskId(spreadsheetId)}"

    fun diagnosticsShareText(context: android.content.Context): String =
        ProblemReportManager.buildDiagnosticsExportReport(context)
}
