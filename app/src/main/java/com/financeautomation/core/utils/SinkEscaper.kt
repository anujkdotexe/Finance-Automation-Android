package com.financeautomation.core.utils

/**
 * Shared output sink escapers across the application (P1-5).
 *
 * Guarantees that data leaving the app is properly escaped and formatted for each destination:
 * - Google Sheets formula string literals
 * - Google Sheets SUMIF / SUMIFS / COUNTIF criteria
 * - Google Drive API 'q' search queries
 * - CSV export cells (formula execution immunity & RFC 4180 quoting)
 * - Config serialization delimiters
 * - Diagnostic and log messages (PII & sensitive detail scrubbing)
 */
object SinkEscaper {

    /**
     * Escapes a string so it can safely be enclosed in double quotes inside a Google Sheets formula.
     * In Google Sheets, a double quote inside a string literal is escaped by doubling it ("").
     */
    fun sheetFormulaLiteral(text: String): String {
        return text.replace("\"", "\"\"")
    }

    /**
     * Escapes wildcard characters for Google Sheets SUMIF, SUMIFS, and COUNTIF criteria.
     * In Sheets criteria syntax:
     * - '~' is the escape character, so literal '~' must be written as '~~'
     * - '*' matches any number of characters, so literal '*' must be written as '~*'
     * - '?' matches any single character, so literal '?' must be written as '~?'
     */
    fun sheetCriterion(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length + 4)
        for (c in text) {
            when (c) {
                '~', '*', '?' -> {
                    sb.append('~')
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Escapes a string for use inside single quotes in a Google Drive API 'q' query parameter.
     * Backslashes must be escaped first, followed by single quotes.
     */
    fun driveQueryLiteral(text: String): String {
        return text.replace("\\", "\\\\").replace("'", "\\'")
    }

    /**
     * Characters that trigger formula execution when evaluated in spreadsheet applications.
     */
    private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * Escapes a cell value for CSV export:
     * 1. Protects against CSV formula injection (DDE attacks): if trimmed text begins with
     *    '=', '+', '-', '@', tab, or carriage return, it is prefixed with a single quote (').
     * 2. Complies with RFC 4180: if text contains commas, double quotes, or newlines, it is
     *    enclosed in double quotes, and internal double quotes are doubled.
     */
    fun csvCell(text: String): String {
        if (text.isEmpty()) return ""

        val trimmed = text.trimStart(' ')
        val needsFormulaPrefix = trimmed.isNotEmpty() && trimmed[0] in FORMULA_TRIGGERS
        val guardedText = if (needsFormulaPrefix) "'$text" else text

        val needsQuotes = guardedText.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) {
            "\"" + guardedText.replace("\"", "\"\"") + "\""
        } else {
            guardedText
        }
    }

    /**
     * Sanitizes a string for Config sync storage by replacing delimiters ('|', ':', ',')
     * with an underscore to prevent corruption during split operations.
     */
    fun configCell(text: String): String {
        return text.map { c ->
            if (c == '|' || c == ':' || c == ',') '_' else c
        }.joinToString("")
    }

    /**
     * Scrubs PII, bank account numbers, emails, tokens, and rupee amounts from log and diagnostic lines.
     */
    fun logMessage(text: String): String {
        return PrivacyScrubber.scrub(text)
    }
}
