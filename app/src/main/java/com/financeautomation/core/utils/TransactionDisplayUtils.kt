package com.financeautomation.core.utils

import java.util.regex.Pattern

private val LEADING_VOID_PATTERN = Pattern.compile("^\\s*\\[void\\]\\s*", Pattern.CASE_INSENSITIVE)

/**
 * Returns a clean display description by stripping any leading sheet-convention "[Void] " prefix
 * and trimming whitespace. Preserves "[Void]" if it appears inside the name.
 */
fun stripVoidPrefix(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val trimmed = text.trim()
    val matcher = LEADING_VOID_PATTERN.matcher(trimmed)
    return if (matcher.find()) {
        matcher.replaceFirst("").trim()
    } else {
        trimmed
    }
}
