package com.financeautomation.core.utils

import java.util.Locale
import kotlin.math.abs

private fun formatIndianGrouping(rupees: Long): String {
    val str = rupees.toString()
    if (str.length <= 3) return str
    val last3 = str.substring(str.length - 3)
    val remaining = str.substring(0, str.length - 3)
    val groupedRemaining = StringBuilder()
    var count = 0
    for (i in remaining.length - 1 downTo 0) {
        if (count > 0 && count % 2 == 0) {
            groupedRemaining.append(',')
        }
        groupedRemaining.append(remaining[i])
        count++
    }
    return groupedRemaining.reverse().toString() + "," + last3
}

/**
 * Universal monetary formatter strictly implementing Indian grouping (₹1,23,456) per Design Guide 3.4.
 *
 * - No decimals for KPI values (includeDecimals = false) -> ₹1,23,456
 * - Two decimals in rows and sheets (includeDecimals = true) -> ₹1,23,456.78
 * - Negative amounts display a leading minus sign (-₹500), never brackets.
 */
fun formatRupees(amount: Double, includeDecimals: Boolean = false): String {
    val totalPaise = Math.round(abs(amount) * 100.0)
    val isNegative = amount < 0.0 && totalPaise > 0L
    val rupees = totalPaise / 100L
    val paise = (totalPaise % 100L).toInt()

    val formattedInt = formatIndianGrouping(rupees)

    val result = if (includeDecimals) {
        val fracStr = if (paise < 10) "0$paise" else "$paise"
        "₹$formattedInt.$fracStr"
    } else {
        "₹$formattedInt"
    }
    return if (isNegative) "-$result" else result
}

/**
 * Displays rupees with paise only when non-zero (Design Guide 3.4).
 * Used as the default in Dashboard cards, rows, and Transactions.
 */
fun formatRupeesAuto(amount: Double): String {
    val totalPaise = Math.round(abs(amount) * 100.0)
    val isNegative = amount < 0.0 && totalPaise > 0L
    val rupees = totalPaise / 100L
    val paise = (totalPaise % 100L).toInt()

    val formattedInt = formatIndianGrouping(rupees)

    val result = if (paise != 0) {
        val fracStr = if (paise < 10) "0$paise" else "$paise"
        "₹$formattedInt.$fracStr"
    } else {
        "₹$formattedInt"
    }
    return if (isNegative) "-$result" else result
}

/**
 * Formats an amount for pre-filling an editable input field (Design Guide 3.4).
 * Returns empty string for zero/negative, whole numbers without decimals, and up to 2 decimal places.
 */
fun formatAmountInput(amount: Double): String {
    return if (amount <= 0.0) {
        ""
    } else if (amount % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", amount)
    } else {
        String.format(Locale.US, "%.2f", amount)
    }
}

/**
 * Compact monetary formatter for one-third-width KPI cards (Round 6b Y1).
 *
 * - Amounts under 10,000 use standard rupee formatting (e.g. ₹9,999).
 * - Amounts of 10,000 or more use compact suffixes (K for thousands, L for lakhs, Cr for crores).
 * - One decimal place is shown when non-zero remainder exists (e.g. ₹50.0K, ₹12.3L, ₹1.2Cr).
 * - Whole multiples display integer style without trailing zeros (e.g. ₹10K, ₹1L).
 */
fun formatRupeesCompact(amount: Double): String {
    val absAmount = abs(amount)
    if (absAmount < 10000.0) {
        return formatRupeesAuto(amount)
    }

    val isNegative = amount < 0.0
    val (divisor, suffix) = when {
        absAmount >= 10000000.0 -> 10000000.0 to "Cr"
        absAmount >= 100000.0 -> 100000.0 to "L"
        else -> 1000.0 to "K"
    }

    val rawVal = absAmount / divisor
    val hasRemainder = (absAmount % divisor) != 0.0
    val formattedNum = if (!hasRemainder) {
        String.format(Locale.US, "%.0f", rawVal)
    } else {
        val truncated = kotlin.math.floor(rawVal * 10.0) / 10.0
        String.format(Locale.US, "%.1f", truncated)
    }

    val prefix = if (isNegative) "-₹" else "₹"
    return "$prefix$formattedNum$suffix"
}
