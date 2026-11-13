package com.financeautomation.core.constants

/**
 * Package whitelist and privacy guard rules for incoming notification interception.
 * Enforces strict financial-only capture and suppresses personal messaging and OTP alerts.
 */
object PackageWhitelist {

    const val PACKAGE_GPAY: String = "com.google.android.apps.nbu.paisa.user"
    const val PACKAGE_BHIM: String = "in.org.npci.upiapp"
    const val PACKAGE_SUPERMONEY: String = "com.supermoney.app"
    const val PACKAGE_PHONEPE: String = "com.phonepe.app"
    const val PACKAGE_PAYTM: String = "net.one97.paytm"
    const val PACKAGE_DEFAULT_SMS: String = "com.android.mms"
    const val PACKAGE_GOOGLE_MESSAGES: String = "com.google.android.apps.messaging"
    const val PACKAGE_SAMSUNG_MESSAGES: String = "com.samsung.android.messaging"

    val WHITELISTED_FINANCIAL_PACKAGES: Set<String> = setOf(
        PACKAGE_GPAY,
        PACKAGE_BHIM,
        PACKAGE_SUPERMONEY,
        PACKAGE_PHONEPE,
        PACKAGE_PAYTM,
        PACKAGE_DEFAULT_SMS,
        PACKAGE_GOOGLE_MESSAGES,
        PACKAGE_SAMSUNG_MESSAGES
    )

    const val PACKAGE_WHATSAPP: String = "com.whatsapp"
    const val PACKAGE_WHATSAPP_BUSINESS: String = "com.whatsapp.w4b"
    const val PACKAGE_TELEGRAM: String = "org.telegram.messenger"
    const val PACKAGE_SIGNAL: String = "org.thoughtcrime.securesms"
    const val PACKAGE_FB_MESSENGER: String = "com.facebook.orca"
    const val PACKAGE_INSTAGRAM: String = "com.instagram.android"
    const val PACKAGE_DISCORD: String = "com.discord"
    const val PACKAGE_OUTLOOK: String = "com.microsoft.office.outlook"
    const val PACKAGE_GMAIL: String = "com.google.android.gm"

    val BLACKLISTED_PRIVACY_PACKAGES: Set<String> = setOf(
        PACKAGE_WHATSAPP,
        PACKAGE_WHATSAPP_BUSINESS,
        PACKAGE_TELEGRAM,
        PACKAGE_SIGNAL,
        PACKAGE_FB_MESSENGER,
        PACKAGE_INSTAGRAM,
        PACKAGE_DISCORD,
        PACKAGE_OUTLOOK,
        PACKAGE_GMAIL
    )

    // Precompiled OTP and 2FA suppression pattern
    val OTP_SUPPRESSION_REGEX: Regex = Regex(
        """\b(otp|one[- ]time[- ]password|verification[- ]code|secret[- ]code|do[- ]not[- ]share|security[- ]code|auth[- ]code)\b""",
        RegexOption.IGNORE_CASE
    )

    const val PACKAGE_DEBUG_SHELL: String = "com.android.shell"
    const val PACKAGE_DEBUG_TEST: String = "com.financeautomation.test"

    fun isWhitelisted(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (com.financeautomation.BuildConfig.DEBUG) {
            if (trimmed == PACKAGE_DEBUG_SHELL || trimmed == PACKAGE_DEBUG_TEST) {
                return true
            }
        }
        return WHITELISTED_FINANCIAL_PACKAGES.contains(trimmed)
    }

    fun isBlacklisted(packageName: String): Boolean {
        val trimmed = packageName.trim()
        return BLACKLISTED_PRIVACY_PACKAGES.contains(trimmed) ||
                BLACKLISTED_PRIVACY_PACKAGES.any { trimmed.startsWith(it) }
    }

    val SMS_PACKAGES: Set<String> = setOf(
        PACKAGE_DEFAULT_SMS,
        PACKAGE_GOOGLE_MESSAGES,
        PACKAGE_SAMSUNG_MESSAGES
    )

    /** Privacy line shown next to the read-only list of watched apps. */
    const val PRIVACY_STATEMENT: String = "We never read WhatsApp, Telegram or OTP messages."

    /** Names of the apps this app reads, for the read-only "Apps we read" list. */
    fun watchedAppsForDisplay(): List<String> =
        listOf("Google Pay", "BHIM", "Supermoney", "PhonePe", "Paytm", "Bank SMS")

    /** Maps a captured package name to a clean user-facing app name. */
    fun appDisplayName(packageName: String): String = when (packageName.trim()) {
        PACKAGE_GPAY -> "Google Pay"
        PACKAGE_BHIM -> "BHIM"
        PACKAGE_SUPERMONEY -> "Supermoney"
        PACKAGE_PHONEPE -> "PhonePe"
        PACKAGE_PAYTM -> "Paytm"
        PACKAGE_DEFAULT_SMS, PACKAGE_GOOGLE_MESSAGES, PACKAGE_SAMSUNG_MESSAGES -> "Bank SMS"
        else -> if (packageName.isNotBlank() && packageName != "manual") packageName else "Payment App"
    }

    fun isSmsPackage(packageName: String): Boolean {
        return SMS_PACKAGES.contains(packageName.trim())
    }

    fun isOtpMessage(text: String): Boolean {
        return OTP_SUPPRESSION_REGEX.containsMatchIn(text)
    }

    fun isOtpOrAuth(text: String): Boolean {
        return isOtpMessage(text)
    }
}
