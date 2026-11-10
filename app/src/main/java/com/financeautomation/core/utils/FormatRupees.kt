package com.financeautomation.core.utils

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Universal monetary formatter strictly implementing Indian grouping (₹1,23,456) per Design Guide 3.4.
 *
 * - No decimals for KPI values (includeDecimals = false) -> ₹1,23,456
 * - Two decimals in rows and sheets (includeDecimals = true) -> ₹1,23,456.78
 * - Negative amounts display a leading minus sign (-₹500), never brackets.
 */
fun formatRupees(amount: Double, includeDecimals: Boolean = false): String {
    val isNegative = amount < 0
    val absAmount = abs(amount)
    val symbols = DecimalFormatSymbols(Locale.forLanguageTag("en-IN")).apply {
        currencySymbol = "₹"
    }
    val pattern = if (includeDecimals) "₹##,##,##0.00" else "₹##,##,##0"
    val df = DecimalFormat(pattern, symbols)
    val formatted = df.format(absAmount)
    return if (isNegative) "-$formatted" else formatted
}

