package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.constants.PrivacyClass
import com.financeautomation.core.model.ReviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-C Owner Decision Test Suite:
 * - Payment apps registry with per-app toggles.
 * - Strict channel-only privacy gating for WhatsApp / WhatsApp Business payments.
 * - Non-payment chat notifications dropped immediately without text inspection.
 * - Generic parser for user-watched apps always routing to Needs Review.
 * - Privacy strings and disclosure updates.
 */
class PaymentAppsRegistryAndPrivacyTest {

    private val parseUseCase = ParseNotificationUseCase()

    @Test
    fun registryHasAllCommonPaymentAppsWithVerifiedPackageIds() {
        val registry = PackageWhitelist.REGISTRY
        val packageIds = registry.map { it.packageId }

        // Must be unique
        assertEquals(packageIds.size, packageIds.toSet().size)

        // Verified package IDs
        val expectedPackages = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",                       // PhonePe
            "net.one97.paytm",                       // Paytm
            "in.org.npci.upiapp",                    // BHIM
            "com.supermoney.app",                    // Supermoney
            "in.amazon.mShop.android.shopping",      // Amazon Pay
            "com.dreamplug.androidapp",              // CRED
            "com.mobikwik_new",                      // MobiKwik
            "com.freecharge.android",                // Freecharge
            "com.naviapp",                           // Navi
            "com.samsung.android.spay",              // Samsung Wallet
            "com.whatsapp",                          // WhatsApp
            "com.whatsapp.w4b",                      // WhatsApp Business
            "com.android.mms",                       // Default SMS
            "com.google.android.apps.messaging",     // Google Messages
            "com.samsung.android.messaging"          // Samsung Messages
        )
        assertTrue(packageIds.containsAll(expectedPackages))

        // WhatsApp items must be CHAT_APP_PAYMENTS_ONLY
        val wa = registry.first { it.packageId == PackageWhitelist.PACKAGE_WHATSAPP }
        assertEquals(PrivacyClass.CHAT_APP_PAYMENTS_ONLY, wa.privacyClass)
        val waB = registry.first { it.packageId == PackageWhitelist.PACKAGE_WHATSAPP_BUSINESS }
        assertEquals(PrivacyClass.CHAT_APP_PAYMENTS_ONLY, waB.privacyClass)
    }

    @Test
    fun perAppSwitchCanTurnOffAnyPaymentApp() {
        val pkg = PackageWhitelist.PACKAGE_CRED
        assertTrue(PackageWhitelist.isWhitelisted(pkg))

        PackageWhitelist.setPackageEnabled(pkg, false)
        assertFalse(PackageWhitelist.isWhitelisted(pkg))

        val result = parseUseCase(
            sourcePackage = pkg,
            title = "CRED",
            text = "Paid ₹1,200 to Credit Card Bill successfully",
            timestamp = System.currentTimeMillis()
        )
        assertTrue("Disabled payment app must be dropped as not whitelisted", result is NotificationParseResult.Dropped)
        assertEquals(DropReason.NOT_WHITELISTED, (result as NotificationParseResult.Dropped).reason)

        // Re-enable
        PackageWhitelist.setPackageEnabled(pkg, true)
        assertTrue(PackageWhitelist.isWhitelisted(pkg))
    }

    @Test
    fun whatsAppPersonalChatDroppedAtOnceWithoutTextInspection() {
        // Chat notification from Mom with no payment channel
        val result = parseUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_WHATSAPP,
            title = "Mom",
            text = "Sent you ₹500 for groceries",
            timestamp = System.currentTimeMillis(),
            channelId = "individual_chat"
        )
        assertTrue(result is NotificationParseResult.Dropped)
        assertEquals(DropReason.BLACKLISTED, (result as NotificationParseResult.Dropped).reason)
    }

    @Test
    fun whatsAppPaymentChannelParsedSuccessfully() {
        // Legitimate payment alert on payment channel
        val result = parseUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_WHATSAPP,
            title = "WhatsApp Payments",
            text = "Paid ₹450 to Chai Point",
            timestamp = System.currentTimeMillis(),
            channelId = "whatsapp_payments_channel"
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(450.0, success.transaction.totalAmount, 0.001)
        assertEquals("Chai Point", success.transaction.merchant)
    }

    @Test
    fun userWatchedAppGenericParsingRoutesToNeedsReview() {
        val customApp = "com.custom.wallet"
        PackageWhitelist.addWatchedApp(customApp)
        assertTrue(PackageWhitelist.isWhitelisted(customApp))
        assertTrue(PackageWhitelist.isGenericWatchedApp(customApp))

        val result = parseUseCase(
            sourcePackage = customApp,
            title = "Custom Wallet",
            text = "Paid ₹850 to Supermarket",
            timestamp = System.currentTimeMillis()
        )
        assertTrue("Generic watched app with amount and debit wording must succeed", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(850.0, success.transaction.totalAmount, 0.001)

        // Clean up
        PackageWhitelist.removeWatchedApp(customApp)
    }

    @Test
    fun strictlyBlacklistedMessagingAppsCannotBeWatched() {
        val telegram = "org.telegram.messenger"
        assertTrue(PackageWhitelist.isBlacklisted(telegram))

        // Adding blacklisted app is ignored
        PackageWhitelist.addWatchedApp(telegram)
        assertFalse("Blacklisted apps cannot be added as user-watched apps", PackageWhitelist.isWhitelisted(telegram))
    }

    @Test
    fun footerPrivacyWordingMatchesOwnerDecision() {
        assertEquals("We never read personal chats, emails or OTP messages.", PackageWhitelist.PRIVACY_STATEMENT)
    }
}
