package com.financeautomation.core.utils

/**
 * Removes anything that could identify the user or their data from technical text before it is stored in the
 * diagnostics trail, the failed queue reason or a shareable report. Google error messages can echo spreadsheet
 * ids, e-mail addresses and cell content, so text is scrubbed at the point where it is stored.
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

    fun scrub(text: String): String {
        var out = text
        out = EMAIL.replace(out, "[email]")
        out = RUPEE_AMOUNT.replace(out, "[amount]")
        out = LONG_TOKEN.replace(out, "[id]")
        out = LONG_NUMBER.replace(out, "[number]")
        out = QUOTED_TEXT.replace(out, "[text]")
        return if (out.length > MAX_LENGTH) out.take(MAX_LENGTH) else out
    }
}
