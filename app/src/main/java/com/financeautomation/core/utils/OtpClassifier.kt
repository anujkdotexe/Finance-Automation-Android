package com.financeautomation.core.utils

/**
 * Decides whether a message is a one-time password message that must never be read.
 *
 * A message is an OTP only when an OTP phrase appears together with a numeric code close to it, and the text does
 * not also state a completed payment (a direction verb tied to an amount). Boilerplate such as "do not share OTP"
 * at the end of a real bank debit alert therefore does not suppress it.
 * Pure Kotlin, no text is logged or stored.
 */
object OtpClassifier {

    private val PHRASE = Regex(
        """(?i)\b(?:otp|one[- ]time[- ]password|verification[- ]code|secret[- ]code|security[- ]code|auth(?:entication)?[- ]code|passcode)\b"""
    )

    // Currency amounts and masked account digits must not be mistaken for the code
    private val AMOUNT = Regex("""(?i)(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d+)?""")
    private val MASKED_ACCOUNT = Regex("""(?i)\b(?:x+|\*+|ending\s+)\d{3,4}\b""")
    private val CODE = Regex("""(?<![\d.,])\d{4,8}(?![\d,]|\.\d)""")

    private const val WINDOW = 45

    fun isOtp(text: String): Boolean {
        if (RegexPatterns.hasConfirmation(text)) return false
        for (match in PHRASE.findAll(text)) {
            val start = (match.range.first - WINDOW).coerceAtLeast(0)
            val end = (match.range.last + 1 + WINDOW).coerceAtMost(text.length)
            val window = text.substring(start, end)
                .replace(AMOUNT, " ")
                .replace(MASKED_ACCOUNT, " ")
            if (CODE.containsMatchIn(window)) return true
        }
        return false
    }
}
