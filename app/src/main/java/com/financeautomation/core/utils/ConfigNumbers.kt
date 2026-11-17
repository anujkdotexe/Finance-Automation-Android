package com.financeautomation.core.utils

import java.math.BigDecimal

/**
 * Parses numbers that arrive from the Config tab. The tab can be edited by hand or by another device, so text such as
 * "Infinity", "NaN", "1e308" or a negative amount must never become a budget or a subscription amount.
 */
object ConfigNumbers {

    /** The largest amount the app accepts anywhere (same limit as the amount calculator). */
    val MAX_AMOUNT: BigDecimal = BigDecimal("10000000000")

    /**
     * Returns the amount in [text] as a Double, or null when it is not a plain decimal number, is not greater than
     * zero, is larger than [MAX_AMOUNT] or has more than two decimals. Thousands commas and surrounding spaces are
     * ignored. Parsed as a BigDecimal, so the words Infinity and NaN never parse.
     */
    fun parseMoney(text: String?): Double? {
        val cleaned = text?.replace(",", "")?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned.any { it != '.' && it != '-' && it != '+' && !it.isDigit() }) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0 || value > MAX_AMOUNT) return null
        if (value.stripTrailingZeros().scale() > 2) return null
        return value.toDouble()
    }
}
