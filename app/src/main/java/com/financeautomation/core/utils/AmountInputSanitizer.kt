package com.financeautomation.core.utils

/**
 * Strict amount input sanitizer ensuring numbers contain at most one decimal point
 * and up to two decimal places.
 */
object AmountInputSanitizer {
    private val AMOUNT_PATTERN = Regex("""^\d*(\.\d{0,2})?$""")
    private val EXPRESSION_PATTERN = Regex("""^[0-9+\-*/()., ]*$""")

    fun isValidAmountInput(input: String): Boolean {
        return input.isEmpty() || input.matches(AMOUNT_PATTERN)
    }

    fun isValidExpressionInput(input: String): Boolean {
        return input.matches(EXPRESSION_PATTERN)
    }

    fun sanitizeAmountInput(input: String, fallback: String = ""): String {
        return if (isValidAmountInput(input)) input else fallback
    }
}

