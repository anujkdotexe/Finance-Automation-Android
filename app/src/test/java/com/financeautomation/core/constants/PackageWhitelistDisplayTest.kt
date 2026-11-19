package com.financeautomation.core.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageWhitelistDisplayTest {

    /** Display name to the packages it stands for. */
    private val displayToPackages: Map<String, Set<String>> = mapOf(
        "Google Pay" to setOf(PackageWhitelist.PACKAGE_GPAY),
        "PhonePe" to setOf(PackageWhitelist.PACKAGE_PHONEPE),
        "Paytm" to setOf(PackageWhitelist.PACKAGE_PAYTM),
        "BHIM" to setOf(PackageWhitelist.PACKAGE_BHIM),
        "Supermoney" to setOf(PackageWhitelist.PACKAGE_SUPERMONEY),
        "Amazon Pay" to setOf(PackageWhitelist.PACKAGE_AMAZON_PAY),
        "CRED" to setOf(PackageWhitelist.PACKAGE_CRED),
        "MobiKwik" to setOf(PackageWhitelist.PACKAGE_MOBIKWIK),
        "Freecharge" to setOf(PackageWhitelist.PACKAGE_FREECHARGE),
        "Navi" to setOf(PackageWhitelist.PACKAGE_NAVI),
        "Samsung Wallet" to setOf(PackageWhitelist.PACKAGE_SAMSUNG_WALLET),
        "WhatsApp (Payments)" to setOf(PackageWhitelist.PACKAGE_WHATSAPP, PackageWhitelist.PACKAGE_WHATSAPP_BUSINESS),
        "Bank SMS" to PackageWhitelist.SMS_PACKAGES
    )

    @Test
    fun listIsExactlyTheRegistryAppsInOrder() {
        assertEquals(
            listOf(
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
            ),
            PackageWhitelist.watchedAppsForDisplay()
        )
    }

    @Test
    fun everyDisplayNameMapsToWhitelistedPackages() {
        for (name in PackageWhitelist.watchedAppsForDisplay()) {
            val packages = displayToPackages[name]
            assertTrue("$name has a package mapping", packages != null && packages.isNotEmpty())
            assertTrue("$name packages are whitelisted", PackageWhitelist.WHITELISTED_FINANCIAL_PACKAGES.containsAll(packages!!))
        }
    }

    @Test
    fun everyWhitelistedPackageIsCoveredByADisplayName() {
        val covered = PackageWhitelist.watchedAppsForDisplay().flatMap { displayToPackages.getValue(it) }.toSet()
        assertEquals(PackageWhitelist.WHITELISTED_FINANCIAL_PACKAGES, covered)
    }

    @Test
    fun noBlacklistedAppIsListed() {
        val forbiddenNames = listOf("telegram", "signal", "messenger", "instagram", "discord", "outlook", "gmail")
        for (name in PackageWhitelist.watchedAppsForDisplay()) {
            assertFalse("$name must not be a blacklisted app", forbiddenNames.any { name.lowercase().contains(it) })
            val packages = displayToPackages.getValue(name)
            assertTrue(packages.none { PackageWhitelist.isBlacklisted(it) })
        }
    }

    @Test
    fun privacyStatementIsExact() {
        assertEquals("We never read personal chats, emails or OTP messages.", PackageWhitelist.PRIVACY_STATEMENT)
    }

    @Test
    fun registryHasNoDuplicatePackageIds() {
        val ids = PackageWhitelist.REGISTRY.map { it.packageId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun chatAppDetectionAndChannelGating() {
        assertTrue(PackageWhitelist.isChatApp(PackageWhitelist.PACKAGE_WHATSAPP))
        assertTrue(PackageWhitelist.isChatApp(PackageWhitelist.PACKAGE_WHATSAPP_BUSINESS))
        assertFalse(PackageWhitelist.isChatApp(PackageWhitelist.PACKAGE_GPAY))

        assertTrue(PackageWhitelist.isPaymentChannel("payments"))
        assertTrue(PackageWhitelist.isPaymentChannel("whatsapp_payments_channel"))
        assertTrue(PackageWhitelist.isPaymentNotification(null, "payment"))
        assertTrue(PackageWhitelist.isPaymentNotification(null, "transaction"))
        assertTrue(PackageWhitelist.isPaymentNotification("chats", "payment"))
        assertFalse(PackageWhitelist.isPaymentChannel("individual_chat"))
        assertFalse(PackageWhitelist.isPaymentChannel("group_chat"))
        assertFalse(PackageWhitelist.isPaymentChannel(null))
        assertFalse(PackageWhitelist.isPaymentChannel(""))
        assertFalse(PackageWhitelist.isPaymentNotification("individual_chat", "social"))
        assertFalse(PackageWhitelist.isPaymentNotification(null, null))
    }

    @Test
    fun perAppToggleWorks() {
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_PHONEPE))
        PackageWhitelist.setPackageEnabled(PackageWhitelist.PACKAGE_PHONEPE, false)
        assertFalse(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_PHONEPE))
        assertFalse(PackageWhitelist.isPackageEnabled(PackageWhitelist.PACKAGE_PHONEPE))

        PackageWhitelist.setPackageEnabled(PackageWhitelist.PACKAGE_PHONEPE, true)
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_PHONEPE))
        assertTrue(PackageWhitelist.isPackageEnabled(PackageWhitelist.PACKAGE_PHONEPE))
    }

    @Test
    fun userWatchedAppGenericHandling() {
        val testApp = "com.custom.mybank"
        assertFalse(PackageWhitelist.isWhitelisted(testApp))
        assertFalse(PackageWhitelist.isGenericWatchedApp(testApp))

        PackageWhitelist.addWatchedApp(testApp)
        assertTrue(PackageWhitelist.isWhitelisted(testApp))
        assertTrue(PackageWhitelist.isGenericWatchedApp(testApp))

        PackageWhitelist.removeWatchedApp(testApp)
        assertFalse(PackageWhitelist.isWhitelisted(testApp))
        assertFalse(PackageWhitelist.isGenericWatchedApp(testApp))
    }

    @Test
    fun appDisplayNameMapsKnownPackages() {
        assertEquals("Google Pay", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_GPAY))
        assertEquals("BHIM", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_BHIM))
        assertEquals("Supermoney", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_SUPERMONEY))
        assertEquals("PhonePe", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_PHONEPE))
        assertEquals("Paytm", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_PAYTM))
        assertEquals("Amazon Pay", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_AMAZON_PAY))
        assertEquals("CRED", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_CRED))
        assertEquals("WhatsApp", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_WHATSAPP))
        assertEquals("Bank SMS", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_GOOGLE_MESSAGES))
        assertEquals("Bank SMS", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_DEFAULT_SMS))
        assertEquals("Bank SMS", PackageWhitelist.appDisplayName(PackageWhitelist.PACKAGE_SAMSUNG_MESSAGES))
    }
}
