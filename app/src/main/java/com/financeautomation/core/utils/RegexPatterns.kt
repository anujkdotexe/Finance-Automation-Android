package com.financeautomation.core.utils

/**
 * Precompiled regular expressions for notification and SMS transaction parsing.
 * All patterns are declared as top-level compiled constants for maximum efficiency.
 */
object RegexPatterns {

    // Amount extractor matching Rs. 1,234.50, INR 500, Rs 99, etc.
    val AMOUNT_REGEX: Regex = Regex(
        """(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    // Bank SMS amount extractor matching "debited by Rs 500", "spent INR 250", etc.
    val BANK_SMS_AMOUNT_REGEX: Regex = Regex(
        """(?i)(?:debited\s+(?:by|for)?|spent|paid|withdrawn|transferred\s+to|vpa)\s+(?:Rs\.?|INR|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""
    )

    // Merchant / Payee regex extracting business names or VPAs
    val MERCHANT_REGEX: Regex = Regex(
        """(?:paid\s+to|sent\s+to|transferred\s+to|trf\s+to|towards|to|at|vpa)\s+([A-Za-z0-9\s&._\-@]+?)(?:,\s*|\s+on\b|\.(?!\d)|$|\s+ref\b|\s+using\b|\s+via\b|\s+through\b|\s+utr\b|\s+a/c\b|\s+avl\b|\s+bal\b|\b\d{12}\b)""",
        RegexOption.IGNORE_CASE
    )

    private val INVALID_MERCHANT_REGEX: Regex = Regex(
        """(?i)^(?:[#\d\s\-+]+|.*your\s+account.*|.*your\s+a/c.*|.*blockupi.*|.*call\b.*)$"""
    )

    // Financial transaction keywords check for SMS filtering
    val FINANCIAL_KEYWORDS_REGEX: Regex = Regex(
        """(?i)\b(debited|credited|withdrawn|transferred|txn|a/c|account|spent|paid\s+thru|paid\s+to|received)\b"""
    )

    // UPI Virtual Payment Address (VPA) regex
    val VPA_REGEX: Regex = Regex(
        """\b([a-zA-Z0-9.\-_]+@[a-zA-Z0-9]+)\b"""
    )

    // Debit direction flag keywords
    val DEBIT_FLAG_REGEX: Regex = Regex(
        """(?i)\b(debited|paid|spent|sent|withdrawn|deducted|purchased|debited\s+by|transfer\s+to)\b"""
    )

    // Credit direction flag keywords
    val CREDIT_FLAG_REGEX: Regex = Regex(
        """(?i)\b(credited|received|deposited|refunded|credited\s+with|cashback|added)\b"""
    )

    // Funding account regex matching bank name and last 3-4 digits
    val ACCOUNT_REGEX: Regex = Regex(
        """(?i)(?:(?!Rs|INR|₹)([A-Za-z]{2,10}\s*Bank|[A-Za-z]{2,6})\s+)?(?:A/c|Account|acct|card)\s*(?:no\.?)?\s*(?:ending\s+(?:with\s+)?|[*xX]+|\.\.+)?(\d{3,4})"""
    )

    // Fallback masked account regex matching standalone ending digits or masked card numbers
    val FALLBACK_ACCOUNT_REGEX: Regex = Regex(
        """(?i)(?:\bending\s+(?:with\s+|in\s+)?|[*xX]{2,}|\.\.{2,})(\d{3,4})\b"""
    )

    // OTP and auth code suppression pattern
    val OTP_REGEX: Regex = Regex(
        """(?i)\b(otp|one[- ]time[- ]password|verification[- ]code|secret[- ]code|do[- ]not[- ]share|security[- ]code|auth[- ]code)\b"""
    )

    // A completed payment: a direction verb directly tied to an amount ("Paid Rs 349", "Rs 500 debited")
    val CONFIRMATION_REGEX: Regex = Regex(
        """(?i)\b(?:paid|debited|sent|spent|withdrawn|deducted|credited|received|refunded)\b[^\d₹]{0,12}(?:rs\.?|inr|₹)\s*[\d,]+""" +
            """|(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d+)?\s*(?:has\s+been\s+|was\s+|is\s+)?(?:debited|paid|sent|spent|withdrawn|credited|received)\b"""
    )

    // "Payment successful" style wording that confirms an outgoing payment without a separate debit verb
    val PAYMENT_SUCCESS_REGEX: Regex = Regex(
        """(?i)\bpayment\b[^.]{0,40}\bsuccessful(?:ly)?\b|\bsuccessful(?:ly)?\s+payment\b|\bpaid\s+successfully\b"""
    )

    // Promotional or reminder wording used by payment apps
    val PROMO_REGEX: Regex = Regex(
        """(?i)\b(cashbacks?|offers?|rewards?|coupons?|vouchers?|discounts?|scratch\s*cards?|win|earn|refer(?:ral)?|invite|bonus|due|overdue|reminder|bills?|recharge|expires?|expiring|apply\s+now|pre-?approved|loans?|emi|limited\s+time|get\s+up\s+to|upto)\b"""
    )

    /** True when the text states a completed payment with an amount (verb and amount together). */
    fun hasConfirmation(text: String): Boolean = CONFIRMATION_REGEX.containsMatchIn(text)

    /** True for "payment successful" wording. */
    fun isPaymentSuccessful(text: String): Boolean = PAYMENT_SUCCESS_REGEX.containsMatchIn(text)

    /** True for promotional or reminder wording. */
    fun isPromotional(text: String): Boolean = PROMO_REGEX.containsMatchIn(text)

    /**
     * Extracts parsed currency amount from arbitrary notification text.
     */
    fun extractAmount(text: String): Double? {
        val match = AMOUNT_REGEX.find(text) ?: BANK_SMS_AMOUNT_REGEX.find(text)
        return match?.groupValues?.get(1)?.let { raw ->
            val clean = raw.replace(",", "")
            clean.toDoubleOrNull()
        }
    }

    /**
     * Extracts merchant or payee name.
     */
    fun extractMerchant(text: String): String? {
        val vpaMatch = VPA_REGEX.find(text)
        if (vpaMatch != null) {
            val vpa = vpaMatch.groupValues[1].trim()
            if (!vpa.contains("ref", ignoreCase = true) && !INVALID_MERCHANT_REGEX.matches(vpa)) {
                return vpa
            }
        }
        for (match in MERCHANT_REGEX.findAll(text)) {
            val candidate = match.groupValues.getOrNull(1)?.trim()?.trim(' ', '.', ',', '-') ?: continue
            if (candidate.isNotBlank() && !INVALID_MERCHANT_REGEX.matches(candidate)) {
                return candidate
            }
        }
        return null
    }

    /**
     * Checks if sender identifier is a personal phone number rather than an institutional/bank handle.
     */
    fun isPersonalSender(sender: String): Boolean {
        val cleaned = sender.replace(" ", "").replace("-", "")
        return cleaned.startsWith("+") || (cleaned.length >= 7 && cleaned.all { it.isDigit() })
    }

    /**
     * Checks if text contains financial transaction keywords.
     */
    fun hasFinancialKeywords(text: String): Boolean {
        return FINANCIAL_KEYWORDS_REGEX.containsMatchIn(text)
    }

    private val PREPOSITION_PREFIX_REGEX: Regex = Regex(
        """^(?i)(?:paid\s+|using\s+|from\s+|via\s+|through\s+|with\s+|by\s+|on\s+|in\s+|at\s+)+"""
    )

    private val INVALID_BANK_NAMES: Set<String> = setOf(
        "rs", "inr", "₹", "using", "from", "via", "through", "with", "by", "on", "in", "at", "paid"
    )

    private fun cleanBankName(rawBank: String?): String? {
        if (rawBank.isNullOrBlank()) return null
        val cleaned = PREPOSITION_PREFIX_REGEX.replace(rawBank.trim(), "").trim()
        if (cleaned.isBlank() || cleaned.lowercase() in INVALID_BANK_NAMES) {
            return null
        }
        return cleaned
    }

    /**
     * Extracts funding account information (e.g. "Bank XX1234").
     */
    fun extractAccount(text: String): String? {
        val match = ACCOUNT_REGEX.find(text)
        if (match != null) {
            val bank = cleanBankName(match.groupValues.getOrNull(1))
            val digits = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
            return when {
                bank != null && digits != null -> "$bank XX$digits"
                digits != null -> "A/c XX$digits"
                bank != null -> bank
                else -> null
            }
        }

        val fbMatch = FALLBACK_ACCOUNT_REGEX.find(text)
        if (fbMatch != null) {
            val digits = fbMatch.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            if (digits != null) {
                return "A/c XX$digits"
            }
        }

        return null
    }

    /**
     * Returns true if text indicates an outflow / debit transaction.
     */
    fun isDebit(text: String): Boolean {
        return DEBIT_FLAG_REGEX.containsMatchIn(text)
    }

    /**
     * Returns true if text indicates an inflow / credit transaction.
     */
    fun isCredit(text: String): Boolean {
        return CREDIT_FLAG_REGEX.containsMatchIn(text)
    }

    /**
     * Returns true if text contains an OTP or authentication code.
     */
    fun isOtp(text: String): Boolean {
        return OTP_REGEX.containsMatchIn(text)
    }
}
