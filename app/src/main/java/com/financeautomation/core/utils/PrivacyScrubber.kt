package com.financeautomation.core.utils

/**
 * Removes anything that could identify the user or their data from technical text before it is stored in the
 * diagnostics trail, the failed queue reason or a shareable report. Google error messages can echo spreadsheet
 * ids, e-mail addresses, bearer tokens, and cell content, so text is scrubbed at the point where it is stored.
 * Pure Kotlin, no Android dependencies.
 */
object PrivacyScrubber {

    const val MAX_LENGTH: Int = 200

    // Drive and Sheets file ids are long url-safe tokens that contain at least one digit (class names do not)
    private val LONG_TOKEN = Regex("""(?=[A-Za-z_-]*\d)[A-Za-z0-9_-]{25,}""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val RUPEE_AMOUNT = Regex("""(?i)(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d+)?""")
    private val LONG_NUMBER = Regex("""\d{6,}""")
    // A quoted value of more than a few characters is likely cell content, for example a payee typed by the user
    private val QUOTED_TEXT = Regex("""(['"])(?:(?!\1).){13,}\1""")
    private val BEARER_TOKEN = Regex("""(?i)Bearer\s+[A-Za-z0-9._-]+""")
    // A spreadsheet id is a 44 character url-safe token; one with no digit is not caught by LONG_TOKEN
    private val SPREADSHEET_ID = Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{44}(?![A-Za-z0-9_-])""")
    // A UPI handle such as name@bank: word characters, dots or dashes, an at sign, letters, no dot needed
    private val UPI_HANDLE = Regex("""[A-Za-z0-9._-]+@[A-Za-z]+""")
    // Ten digit phone numbers, possibly after +91 and possibly split as 5+5 by a space or dash
    private val PHONE = Regex("""(?<!\d)(?:\+?91[ -]?)?\d{5}[ -]?\d{5}(?!\d)""")
    private val YA29_TOKEN = Regex("""ya29\.[A-Za-z0-9_-]+""")

    fun scrub(text: String, capLength: Boolean = true): String {
        var out = text
        out = BEARER_TOKEN.replace(out, "Bearer [token]")
        out = YA29_TOKEN.replace(out, "[token]")
        out = EMAIL.replace(out, "[email]")
        out = UPI_HANDLE.replace(out, "[upi]")
        out = RUPEE_AMOUNT.replace(out, "[amount]")
        out = LONG_TOKEN.replace(out, "[id]")
        out = SPREADSHEET_ID.replace(out, "[id]")
        out = PHONE.replace(out, "[phone]")
        out = LONG_NUMBER.replace(out, "[number]")
        out = QUOTED_TEXT.replace(out, "[text]")
        return if (capLength && out.length > MAX_LENGTH) out.take(MAX_LENGTH) else out
    }

    fun scrubWithoutLengthCap(text: String): String = scrub(text, capLength = false)
}
