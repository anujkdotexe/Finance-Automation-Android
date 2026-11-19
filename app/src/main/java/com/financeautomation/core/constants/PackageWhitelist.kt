package com.financeautomation.core.constants

import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Privacy class designating how incoming notifications are gated and processed.
 */
enum class PrivacyClass {
    PAYMENT_APP,
    BANK_SMS,
    CHAT_APP_PAYMENTS_ONLY,
    USER_WATCHED_GENERIC
}

/**
 * Entry in the payment apps registry.
 */
data class PaymentAppInfo(
    val packageId: String,
    val displayName: String,
    val defaultEnabled: Boolean = true,
    val privacyClass: PrivacyClass = PrivacyClass.PAYMENT_APP
)

/**
 * Package whitelist and privacy guard rules for incoming notification interception.
 * Enforces strict financial-only capture and suppresses personal messaging, emails, and OTP alerts.
 */
object PackageWhitelist {

    const val PACKAGE_GPAY: String = "com.google.android.apps.nbu.paisa.user"
    const val PACKAGE_BHIM: String = "in.org.npci.upiapp"
    const val PACKAGE_SUPERMONEY: String = "com.supermoney.app"
    const val PACKAGE_PHONEPE: String = "com.phonepe.app"
    const val PACKAGE_PAYTM: String = "net.one97.paytm"
    const val PACKAGE_AMAZON_PAY: String = "in.amazon.mShop.android.shopping"
    const val PACKAGE_CRED: String = "com.dreamplug.androidapp"
    const val PACKAGE_MOBIKWIK: String = "com.mobikwik_new"
    const val PACKAGE_FREECHARGE: String = "com.freecharge.android"
    const val PACKAGE_NAVI: String = "com.naviapp"
    const val PACKAGE_SAMSUNG_WALLET: String = "com.samsung.android.spay"
    const val PACKAGE_WHATSAPP: String = "com.whatsapp"
    const val PACKAGE_WHATSAPP_BUSINESS: String = "com.whatsapp.w4b"

    const val PACKAGE_DEFAULT_SMS: String = "com.android.mms"
    const val PACKAGE_GOOGLE_MESSAGES: String = "com.google.android.apps.messaging"
    const val PACKAGE_SAMSUNG_MESSAGES: String = "com.samsung.android.messaging"

    /**
     * Verified payment apps registry with per-app toggles and privacy classifications.
     */
    val REGISTRY: List<PaymentAppInfo> = listOf(
        PaymentAppInfo(PACKAGE_GPAY, "Google Pay", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_PHONEPE, "PhonePe", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_PAYTM, "Paytm", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_BHIM, "BHIM", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_SUPERMONEY, "Supermoney", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_AMAZON_PAY, "Amazon Pay", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_CRED, "CRED", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_MOBIKWIK, "MobiKwik", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_FREECHARGE, "Freecharge", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_NAVI, "Navi", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_SAMSUNG_WALLET, "Samsung Wallet", true, PrivacyClass.PAYMENT_APP),
        PaymentAppInfo(PACKAGE_WHATSAPP, "WhatsApp (Payments)", true, PrivacyClass.CHAT_APP_PAYMENTS_ONLY),
        PaymentAppInfo(PACKAGE_WHATSAPP_BUSINESS, "WhatsApp Business (Payments)", true, PrivacyClass.CHAT_APP_PAYMENTS_ONLY),
        PaymentAppInfo(PACKAGE_DEFAULT_SMS, "Default SMS", true, PrivacyClass.BANK_SMS),
        PaymentAppInfo(PACKAGE_GOOGLE_MESSAGES, "Google Messages", true, PrivacyClass.BANK_SMS),
        PaymentAppInfo(PACKAGE_SAMSUNG_MESSAGES, "Samsung Messages", true, PrivacyClass.BANK_SMS)
    )

    val WHITELISTED_FINANCIAL_PACKAGES: Set<String> = REGISTRY.map { it.packageId }.toSet()

    // Strictly blocked messaging, mail and browser apps where non-payment text must never be touched
    const val PACKAGE_TELEGRAM: String = "org.telegram.messenger"
    const val PACKAGE_SIGNAL: String = "org.thoughtcrime.securesms"
    const val PACKAGE_FB_MESSENGER: String = "com.facebook.orca"
    const val PACKAGE_INSTAGRAM: String = "com.instagram.android"
    const val PACKAGE_DISCORD: String = "com.discord"
    const val PACKAGE_OUTLOOK: String = "com.microsoft.office.outlook"
    const val PACKAGE_GMAIL: String = "com.google.android.gm"
    const val PACKAGE_CHROME: String = "com.android.chrome"
    const val PACKAGE_FIREFOX: String = "org.mozilla.firefox"
    const val PACKAGE_OPERA: String = "com.opera.browser"
    const val PACKAGE_BRAVE: String = "com.brave.browser"
    const val PACKAGE_EDGE: String = "com.microsoft.emmx"

    val BLACKLISTED_PRIVACY_PACKAGES: Set<String> = setOf(
        PACKAGE_TELEGRAM,
        PACKAGE_SIGNAL,
        PACKAGE_FB_MESSENGER,
        PACKAGE_INSTAGRAM,
        PACKAGE_DISCORD,
        PACKAGE_OUTLOOK,
        PACKAGE_GMAIL,
        PACKAGE_CHROME,
        PACKAGE_FIREFOX,
        PACKAGE_OPERA,
        PACKAGE_BRAVE,
        PACKAGE_EDGE
    )

    val SMS_PACKAGES: Set<String> = setOf(
        PACKAGE_DEFAULT_SMS,
        PACKAGE_GOOGLE_MESSAGES,
        PACKAGE_SAMSUNG_MESSAGES
    )

    private val disabledPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val userWatchedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var preferencesRepository: PreferencesRepository? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(prefs: PreferencesRepository) {
        preferencesRepository = prefs
        coroutineScope.launch {
            loadFromPreferences(prefs)
        }
    }

    suspend fun loadFromPreferences(prefs: PreferencesRepository) {
        preferencesRepository = prefs
        val disabled = prefs.getDisabledPackages()
        disabledPackages.clear()
        disabledPackages.addAll(disabled)

        val watched = prefs.getUserWatchedPackages()
        userWatchedPackages.clear()
        userWatchedPackages.addAll(watched)
    }

    private fun persistDisabled() {
        val prefs = preferencesRepository ?: return
        coroutineScope.launch {
            try {
                prefs.setDisabledPackages(disabledPackages.toSet())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    private fun persistWatched() {
        val prefs = preferencesRepository ?: return
        coroutineScope.launch {
            try {
                prefs.setUserWatchedPackages(userWatchedPackages.toSet())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    suspend fun saveToPreferences(prefs: PreferencesRepository) {
        prefs.setDisabledPackages(disabledPackages.toSet())
        prefs.setUserWatchedPackages(userWatchedPackages.toSet())
    }

    fun resetForTesting() {
        disabledPackages.clear()
        userWatchedPackages.clear()
        preferencesRepository = null
    }

    fun setPackageEnabled(packageId: String, enabled: Boolean) {
        val trimmed = packageId.trim()
        if (enabled) {
            disabledPackages.remove(trimmed)
        } else {
            disabledPackages.add(trimmed)
        }
        persistDisabled()
    }

    fun isPackageEnabled(packageId: String): Boolean {
        return !disabledPackages.contains(packageId.trim())
    }

    /**
     * Checks if a package is eligible for the "Watch another app" allowlist.
     * Enforces strict allow-style rule:
     * - Must not be a personal chat, email, or social media application.
     * - Must match known financial / payment identifiers or categories.
     */
    fun isEligibleForWatch(packageId: String, displayName: String? = null): Boolean {
        val trimmed = packageId.trim()
        if (trimmed.isBlank()) return false
        val pkg = trimmed.lowercase()
        val name = displayName?.trim()?.lowercase().orEmpty()

        if (WHITELISTED_FINANCIAL_PACKAGES.contains(trimmed)) return false
        if (userWatchedPackages.contains(trimmed)) return false

        if (isChatMailOrSocial(pkg, name)) return false

        return isFinancialAppIdentifier(pkg, name)
    }

    fun isChatMailOrSocial(pkg: String, name: String = ""): Boolean {
        val target = "$pkg $name".lowercase()
        val blockedKeywords = listOf(
            "mail", "email", "chat", "messag", "talk", "social", "call", "sms",
            "browser", "slack", "teams", "viber", "wechat", "discord", "telegram",
            "signal", "instagram", "facebook", "twitter", "reddit", "tiktok",
            "snapchat", "whatsapp", "meet", "zoom", "skype", "line"
        )
        return isBlacklisted(pkg) || blockedKeywords.any { target.contains(it) }
    }

    fun isFinancialAppIdentifier(pkg: String, name: String = ""): Boolean {
        val target = "$pkg $name".lowercase()
        val allowedKeywords = listOf(
            "pay", "bank", "wallet", "finance", "fintech", "money", "upi", "credit", "card",
            "invest", "wealth", "lending", "cash", "sbi", "hdfc", "icici", "axis", "canara",
            "pnb", "bob", "kotak", "indusind", "yesbank", "rbl", "idfc", "federal", "unionbank",
            "baroda", "yono", "custom"
        )
        return allowedKeywords.any { target.contains(it) }
    }

    fun addWatchedApp(packageId: String, displayName: String? = null) {
        val trimmed = packageId.trim()
        if (trimmed.isNotBlank() && isEligibleForWatch(trimmed, displayName)) {
            userWatchedPackages.add(trimmed)
            persistWatched()
        }
    }

    fun removeWatchedApp(packageId: String) {
        userWatchedPackages.remove(packageId.trim())
        persistWatched()
    }

    fun getWatchedApps(): Set<String> = userWatchedPackages.toSet()

    /**
     * Filters a list of installed package (packageId to displayName) pairs for the "Watch another app" picker.
     * Enforces allow-style rule: only packages whose category or name matches financial, bank, or payment
     * are listed, and never chat, mail, or social apps.
     */
    fun getAvailableAppsToWatch(installedPackages: List<Pair<String, String>>): List<Pair<String, String>> {
        return installedPackages.filter { (pkg, name) ->
            isEligibleForWatch(pkg, name)
        }
    }

    fun isGenericWatchedApp(packageName: String): Boolean {
        val trimmed = packageName.trim()
        return userWatchedPackages.contains(trimmed) && !WHITELISTED_FINANCIAL_PACKAGES.contains(trimmed)
    }

    fun isWhitelisted(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (disabledPackages.contains(trimmed)) return false
        return WHITELISTED_FINANCIAL_PACKAGES.contains(trimmed) || userWatchedPackages.contains(trimmed)
    }

    fun isBlacklisted(packageName: String): Boolean {
        val trimmed = packageName.trim()
        return BLACKLISTED_PRIVACY_PACKAGES.contains(trimmed) ||
                BLACKLISTED_PRIVACY_PACKAGES.any { trimmed.startsWith(it) }
    }

    fun isChatApp(packageName: String): Boolean {
        val trimmed = packageName.trim()
        return trimmed == PACKAGE_WHATSAPP || trimmed == PACKAGE_WHATSAPP_BUSINESS
    }

    fun isPaymentNotification(channelId: String?, category: String?): Boolean {
        val channelMatch = !channelId.isNullOrBlank() && channelId.contains("payment", ignoreCase = true)
        val categoryMatch = !category.isNullOrBlank() && (
            category.equals("payment", ignoreCase = true) ||
            category.equals("transaction", ignoreCase = true) ||
            category.contains("payment", ignoreCase = true)
        )
        return channelMatch || categoryMatch
    }

    fun isPaymentChannel(channelId: String?): Boolean = isPaymentNotification(channelId, null)

    /** Privacy line shown next to the list of watched apps. */
    const val PRIVACY_STATEMENT: String = "We never read personal chats, emails or OTP messages."

    /** Names of the apps this app reads, for the read-only "Apps we read" list. */
    fun watchedAppsForDisplay(): List<String> = listOf(
        "Google Pay",
        "PhonePe",
        "Paytm",
        "BHIM",
        "Supermoney",
        "Amazon Pay",
        "CRED",
        "MobiKwik",
        "Freecharge",
        "Navi",
        "Samsung Wallet",
        "WhatsApp (Payments)",
        "Bank SMS"
    )

    /** Maps a captured package name to a clean user-facing app name. */
    fun appDisplayName(packageName: String): String = when (packageName.trim()) {
        PACKAGE_GPAY -> "Google Pay"
        PACKAGE_BHIM -> "BHIM"
        PACKAGE_SUPERMONEY -> "Supermoney"
        PACKAGE_PHONEPE -> "PhonePe"
        PACKAGE_PAYTM -> "Paytm"
        PACKAGE_AMAZON_PAY -> "Amazon Pay"
        PACKAGE_CRED -> "CRED"
        PACKAGE_MOBIKWIK -> "MobiKwik"
        PACKAGE_FREECHARGE -> "Freecharge"
        PACKAGE_NAVI -> "Navi"
        PACKAGE_SAMSUNG_WALLET -> "Samsung Wallet"
        PACKAGE_WHATSAPP, PACKAGE_WHATSAPP_BUSINESS -> "WhatsApp"
        PACKAGE_DEFAULT_SMS, PACKAGE_GOOGLE_MESSAGES, PACKAGE_SAMSUNG_MESSAGES -> "Bank SMS"
        else -> if (packageName.isNotBlank() && packageName != "manual") packageName else "Payment App"
    }

    fun isSmsPackage(packageName: String): Boolean {
        return SMS_PACKAGES.contains(packageName.trim())
    }

    fun isOtpMessage(text: String): Boolean {
        return com.financeautomation.core.utils.OtpClassifier.isOtp(text)
    }

    fun isOtpOrAuth(text: String): Boolean {
        return isOtpMessage(text)
    }
}
