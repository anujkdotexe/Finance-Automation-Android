package com.financeautomation.core.utils

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Universal monetary formatter strictly implementing Indian grouping (₹1,23,456) per Design Guide 3.4.
 *
 * - No decimals for KPI values (includeDecimals = false) -> ₹1,23,456
 * - Two decimals in rows and sheets (includeDecimals = true) -> ₹1,23,456.78
 * - Negative amounts display a leading minus sign (-₹500), never brackets.
 */
fun formatRupees(amount: Double, includeDecimals: Boolean = false): String {
    val isNegative = amount < 0.0
    val absAmount = abs(amount)

    val longPart = absAmount.toLong()
    val fracPart = ((absAmount - longPart) * 100.0).roundToLong().toInt()

    val str = longPart.toString()
    val formattedInt = if (str.length <= 3) {
        str
    } else {
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
        groupedRemaining.reverse().toString() + "," + last3
    }

    val result = if (includeDecimals) {
        val fracStr = if (fracPart < 10) "0$fracPart" else "$fracPart"
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
