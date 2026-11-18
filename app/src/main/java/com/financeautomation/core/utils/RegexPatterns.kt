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
        """\b(?:paid\s+to|sent\s+to|transferred\s+to|trf\s+to|received\s+from|from|towards|to|at)\s+([A-Za-z0-9\s&._\-@]+?)(?:,\s*|\s+on\b|\.(?!\d)|$|\s+ref\b|\s+using\b|\s+via\b|\s+through\b|\s+into\b|\s+utr\b|\s+a/c\b|\s+avl\b|\s+bal\b|\b\d{12}\b)""",
        RegexOption.IGNORE_CASE
    )

    private val INVALID_MERCHANT_REGEX: Regex = Regex(
        """(?i)^(?:[#\d\s\-+]+|.*your\s+account.*|.*your\s+a/c.*|.*blockupi.*|.*call\b.*|.*report\s+(?:cyber\s+)?fraud.*|.*dial\s+\d+.*)$"""
    )

    private val OWN_VPA_CONTEXT_REGEX: Regex = Regex("""(?i)\b(?:into|using|via|through|from|your\s+vpa|my\s+vpa|linked\s+to)\s+$""")

    // Financial transaction keywords check for SMS filtering
    val FINANCIAL_KEYWORDS_REGEX: Regex = Regex(
        """(?i)\b(debited|credited|withdrawn|transferred|txn|a/c(?:\s+no)?|account|acct|ac|spent|paid\s+thru|paid\s+to|received|dr\.?|cr\.?|debit|debits|credit|credits|payment\s+of|bill\s+payment\s+of|successfully\s+paid|recharge\s+of|jiopay|upi\s*ref|upi:)(?=\W|$)"""
    )

    // UPI Virtual Payment Address (VPA) regex
    val VPA_REGEX: Regex = Regex(
        """\b([a-zA-Z0-9.\-_]+@[a-zA-Z0-9]+)\b(?!\.[a-zA-Z])"""
    )

    // The amount tied to a debit or credit verb: "debited Rs 450", "spent INR 349", "Rs 200 paid", "debited by 30"
    private val AMOUNT_NEXT_TO_VERB_REGEX: Regex = Regex(
        """(?i)\b(?:debit(?:ed)?(?:\s+by|\s+for)?|spent|paid|withdrawn|sent|deducted|purchase|payment\s+of|bill\s+payment\s+of|credited|received|refunded|recharge\s+of|recharge\s+successful|dr\.?|cr\.?)\b[^\d₹]{0,15}(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)""" +
            """|\b(?:debit(?:ed)?\s+by|debited\s+for)\s*([\d,]+(?:\.\d{1,2})?)""" +
            """|(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)\s*(?:has\s+been\s+|was\s+|is\s+)?(?:debited|paid|sent|spent|withdrawn|deducted|credited|received|refunded|dr\.?|cr\.?)\b""" +
            """|(?:Plan\s+Name|प्लान\s+का\s+नाम|प्लॅन\s+चे\s+नाव)\s*:\s*([\d,]+(?:\.\d{1,2})?)"""
    )

    // Words that mark an amount as a balance or limit, not the payment
    private val BALANCE_BEFORE_REGEX: Regex = Regex("""(?i)(?:bal(?:ance)?|avl|avail(?:able)?|limit|outstanding|minimum|due)\W{0,15}$""")
    private val BALANCE_AFTER_REGEX: Regex = Regex("""(?i)^\W{0,15}(?:bal(?:ance)?|avl|avail(?:able)?|limit|outstanding|is\s+your\s+(?:bal|avail))""")

    // Debit direction flag keywords
    val DEBIT_FLAG_REGEX: Regex = Regex(
        """(?i)\b(debited|paid|spent|sent|withdrawn|deducted|purchased|debited\s+by|transfer\s+to|dr\.?)\b"""
    )

    // Credit direction flag keywords
    val CREDIT_FLAG_REGEX: Regex = Regex(
        """(?i)\b(credited|received|deposited|refunded|credited\s+with|cashback|added|cr\.?)\b"""
    )

    // Funding account regex matching bank name and last 3-4 digits
    val ACCOUNT_REGEX: Regex = Regex(
        """(?i)(?:(?!Rs|INR|₹)([A-Za-z]{2,10}\s*Bank|[A-Za-z]{2,6})\s+)?(?:A/c|Account|acct|card)\s*(?:no\.?)?\s*(?:ending\s+(?:with\s+)?|[*xX]+|\.\.+)?(\d{3,4})"""
    )

    // Fallback masked account regex matching standalone ending digits or masked card numbers
    val FALLBACK_ACCOUNT_REGEX: Regex = Regex(
        """(?i)(?:\bending\s+(?:with\s+|in\s+)?|[*xX]{2,}|\.\.{2,})(\d{3,4})\b"""
    )

    // A completed payment: a direction verb directly tied to an amount ("Paid Rs 349", "Rs 500 debited")
    val CONFIRMATION_REGEX: Regex = Regex(
        """(?i)\b(?:paid|debited|sent|spent|withdrawn|deducted|credited|received|refunded|recharge\s+of|recharge\s+successful|bill\s+payment\s+of|dr\.?|cr\.?)\b[^\d₹]{0,12}(?:rs\.?|inr|₹)\s*[\d,]+""" +
            """|(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d+)?\s*(?:has\s+been\s+|was\s+|is\s+)?(?:debited|paid|sent|spent|withdrawn|credited|received|dr\.?|cr\.?)\b""" +
            """|(?:Plan\s+Name|प्लान\s+का\s+नाम|प्लॅन\s+चे\s+नाव)\s*:\s*[\d,]+(?:\.\d{1,2})?"""
    )

    // "Payment successful" style wording that confirms an outgoing payment without a separate debit verb
    val PAYMENT_SUCCESS_REGEX: Regex = Regex(
        """(?i)\b(?:payment|recharge|bill|emi)\b[^.]{0,40}\bsuccessful(?:ly)?\b|\bsuccessful(?:ly)?\s+payment\b|\bpaid\s+successfully\b|रिचार्ज\s+सफल|रिचार्ज\s+यशस्वी"""
    )

    // A completed outgoing payment: a debit verb tied to an amount
    val DEBIT_CONFIRMATION_REGEX: Regex = Regex(
        """(?i)\b(?:paid|debited|sent|spent|withdrawn|deducted|purchased?|recharge\s+of|recharge\s+successful|bill\s+payment\s+of|dr\.?)\b[^\d₹]{0,12}(?:rs\.?|inr|₹)\s*[\d,]+""" +
            """|(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d+)?\s*(?:has\s+been\s+|was\s+|is\s+)?(?:debited|paid|sent|spent|withdrawn|deducted|purchased?|dr\.?)\b""" +
            """|(?:Plan\s+Name|प्लान\s+का\s+नाम|प्लॅन\s+चे\s+नाव)\s*:\s*[\d,]+(?:\.\d{1,2})?"""
    )

    // A completed incoming payment: a credit verb tied to an amount
    val CREDIT_CONFIRMATION_REGEX: Regex = Regex(
        """(?i)\b(?:credited|received|deposited|refunded|cr\.?)\b[^\d₹]{0,12}(?:rs\.?|inr|₹)\s*[\d,]+""" +
            """|(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d+)?\s*(?:has\s+been\s+|was\s+|is\s+)?(?:credited|received|deposited|refunded|cr\.?)\b"""
    )

    // Advertising wording. It marks a message as a promotion even next to an amount, unless a purchase was paid.
    val HARD_PROMO_REGEX: Regex = Regex(
        """(?i)\b(cashbacks?|offers?|rewards?|coupons?|vouchers?|discounts?|scratch\s*cards?|refer(?:ral)?|invite|bonus|expires?|expiring|apply\s+now|pre-?approved|limited\s+time|get\s+up\s+to|upto)\b"""
    )

    // Words that also appear in real completed payments ("bill payment successful", "EMI paid").
    // They count as promotional only when the text shows no success wording.
    val SOFT_PROMO_REGEX: Regex = Regex(
        """(?i)\b(win|earn|due|overdue|reminder|bills?|recharge|loans?|emi)\b"""
    )

    /** True when the text states a completed payment with an amount (verb and amount together). */
    fun hasConfirmation(text: String): Boolean = CONFIRMATION_REGEX.containsMatchIn(text)

    /** True for "payment successful" wording. */
    fun isPaymentSuccessful(text: String): Boolean = PAYMENT_SUCCESS_REGEX.containsMatchIn(text)

    /** True for promotional or reminder wording of either kind. */
    fun isPromotional(text: String): Boolean = isHardPromotional(text) || isSoftPromotional(text)

    fun isHardPromotional(text: String): Boolean = HARD_PROMO_REGEX.containsMatchIn(text)

    fun isSoftPromotional(text: String): Boolean = SOFT_PROMO_REGEX.containsMatchIn(text)

    /** True when the text states a completed outgoing payment (debit verb tied to an amount). */
    fun hasDebitConfirmation(text: String): Boolean = DEBIT_CONFIRMATION_REGEX.containsMatchIn(text)

    /** True when the text states a completed incoming payment (credit verb tied to an amount). */
    fun hasCreditConfirmation(text: String): Boolean = CREDIT_CONFIRMATION_REGEX.containsMatchIn(text)

    /**
     * Extracts parsed currency amount from arbitrary notification text.
     */
    fun extractAmount(text: String): Double? {
        // 1. The amount next to a debit or credit verb is the payment; a balance or limit mentioned first must not win
        val near = AMOUNT_NEXT_TO_VERB_REGEX.find(text)
        val nearRaw = near?.let {
            it.groupValues.getOrNull(1)?.ifEmpty { "" }?.takeIf { s -> s.isNotEmpty() }
                ?: it.groupValues.getOrNull(2)?.ifEmpty { "" }?.takeIf { s -> s.isNotEmpty() }
                ?: it.groupValues.getOrNull(3)?.ifEmpty { "" }?.takeIf { s -> s.isNotEmpty() }
                ?: it.groupValues.getOrNull(4)?.ifEmpty { "" }?.takeIf { s -> s.isNotEmpty() }
        }
        nearRaw?.replace(",", "")?.toDoubleOrNull()?.let { return it }
        // 2. Otherwise the first amount that is not labelled as a balance or limit
        val all = AMOUNT_REGEX.findAll(text).toList()
        val preferred = all.firstOrNull { m ->
            val before = text.substring((m.range.first - 30).coerceAtLeast(0), m.range.first)
            val afterStart = (m.range.last + 1).coerceAtMost(text.length)
            val afterEnd = (afterStart + 30).coerceAtMost(text.length)
            val after = text.substring(afterStart, afterEnd)
            !BALANCE_BEFORE_REGEX.containsMatchIn(before) && !BALANCE_AFTER_REGEX.containsMatchIn(after)
        }
        val match = preferred ?: BANK_SMS_AMOUNT_REGEX.find(text)?.takeIf { m ->
            val before = text.substring((m.range.first - 30).coerceAtLeast(0), m.range.first)
            val afterStart = (m.range.last + 1).coerceAtMost(text.length)
            val afterEnd = (afterStart + 30).coerceAtMost(text.length)
            val after = text.substring(afterStart, afterEnd)
            !BALANCE_BEFORE_REGEX.containsMatchIn(before) && !BALANCE_AFTER_REGEX.containsMatchIn(after)
        }
        return match?.groupValues?.get(1)?.let { raw ->
            val clean = raw.replace(",", "")
            clean.toDoubleOrNull()
        }
    }

    /**
     * Extracts merchant or payee name.
     */
    fun extractMerchant(text: String): String? {
        for (match in MERCHANT_REGEX.findAll(text)) {
            val candidate = match.groupValues.getOrNull(1)?.trim()?.trim(' ', '.', ',', '-') ?: continue
            if (candidate.isNotBlank() && !INVALID_MERCHANT_REGEX.matches(candidate)) {
                return candidate
            }
        }
        val vpaMatch = VPA_REGEX.find(text)
        if (vpaMatch != null) {
            val before = text.substring((vpaMatch.range.first - 20).coerceAtLeast(0), vpaMatch.range.first)
            val isOwnVpaContext = OWN_VPA_CONTEXT_REGEX.containsMatchIn(before)
            val vpa = vpaMatch.groupValues[1].trim()
            if (!isOwnVpaContext && !vpa.contains("ref", ignoreCase = true) && !INVALID_MERCHANT_REGEX.matches(vpa)) {
                return vpa
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

    private val MIXED_CASE_BILLER_REGISTRY: Set<String> = setOf(
        "JioPay-S", "JioSvc-S", "iPaytm-S", "blnkit-S", "IRCTCi-S", "IRSMSa-S"
    )

    private val BANK_SENDER_UPPER_REGEX: Regex = Regex("""^[A-Z0-9][A-Z0-9-]{2,19}$""")

    /**
     * True for sender ids that look like a bank or payment header: letters and digits in capitals with dashes and no
     * spaces (VM-HDFCBK, AX-SBIUPI-S, HDFCBK, JIOPAY-S), a 4 to 9 digit short code (56161, 5676766), or a registered
     * mixed-case biller header (JioPay-S). Contact names such as "Rahul", "Mom", "Dr-Rao", "Mr-Sharma", "Rahul-K",
     * "Mom-S", "Raj-Mo", "Mary-Jane", or "Smith-Jones", and personal phone numbers are not bank senders.
     */
    fun isBankSenderId(sender: String): Boolean {
        val s = sender.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() } || s.startsWith("+")) return false
        if (s.all { it.isDigit() }) return s.length in 4..9
        if (!s.any { it.isLetter() }) return false
        if (MIXED_CASE_BILLER_REGISTRY.contains(s)) return true
        return BANK_SENDER_UPPER_REGEX.matches(s)
    }

    private val TOWARDS_BILLER_REGEX: Regex = Regex(
        """(?i)\btowards\s+([A-Za-z0-9\s&._\-]+?)(?:\s+(?:electricity|water|gas|utility|broadband|mobile|phone))?(?:\s+bill\b|,\s*|\.|\s+ref\b|\s+using\b|$)"""
    )

    private val EXECUTED_RECHARGE_REGEX: Regex = Regex(
        """(?i)\b(?:recharge\s+successful|Plan\s+Name)\b|रिचार्ज\s+सफल|रिचार्ज\s+यशस्वी|प्लान\s+का\s+नाम|प्लॅन\s+चे\s+नाव"""
    )

    /**
     * Extracts biller name from bill payment or recharge messages.
     */
    fun extractBiller(title: String, text: String): String? {
        val towardsMatch = TOWARDS_BILLER_REGEX.find(text)
        if (towardsMatch != null) {
            val candidate = towardsMatch.groupValues[1].trim()
            if (candidate.isNotBlank() && !INVALID_MERCHANT_REGEX.matches(candidate)) {
                return candidate
            }
        }
        if (EXECUTED_RECHARGE_REGEX.containsMatchIn(text)) {
            val senderClean = title.split("-").firstOrNull()?.trim() ?: ""
            return when {
                senderClean.equals("JioPay", ignoreCase = true) || senderClean.equals("Jio", ignoreCase = true) -> "Jio"
                senderClean.isNotBlank() -> senderClean
                else -> null
            }
        }
        return null
    }

    private val REFERENCE_ID_REGEX: Regex = Regex(
        """(?i)\b(?:ref(?:\s*no\.?)?|upi\s*(?:ref(?:\s*no\.?)?)?|utr(?:\s*no\.?)?|txn\s*(?:id|no\.?)?)[:\s\-]+([A-Za-z0-9]{6,25})\b"""
    )

    /**
     * Extracts transaction / UPI reference id or UTR number from message text.
     */
    fun extractReferenceId(text: String): String? {
        return REFERENCE_ID_REGEX.find(text)?.groupValues?.get(1)?.trim()
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
        return OtpClassifier.isOtp(text)
    }
}
