package com.financeautomation.core.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageWhitelistDisplayTest {

    /** Display name to the packages it stands for. */
    private val displayToPackages: Map<String, Set<String>> = mapOf(
        "Google Pay" to setOf(PackageWhitelist.PACKAGE_GPAY),
        "BHIM" to setOf(PackageWhitelist.PACKAGE_BHIM),
        "Supermoney" to setOf(PackageWhitelist.PACKAGE_SUPERMONEY),
        "PhonePe" to setOf(PackageWhitelist.PACKAGE_PHONEPE),
        "Paytm" to setOf(PackageWhitelist.PACKAGE_PAYTM),
        "Bank SMS" to PackageWhitelist.SMS_PACKAGES
    )

    @Test
    fun listIsExactlyTheSixAppsInOrder() {
        assertEquals(
            listOf("Google Pay", "BHIM", "Supermoney", "PhonePe", "Paytm", "Bank SMS"),
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
        val forbiddenNames = listOf("whatsapp", "telegram", "signal", "messenger", "instagram", "discord", "outlook", "gmail")
        for (name in PackageWhitelist.watchedAppsForDisplay()) {
            assertFalse("$name must not be a blacklisted app", forbiddenNames.any { name.lowercase().contains(it) })
            val packages = displayToPackages.getValue(name)
            assertTrue(packages.none { PackageWhitelist.isBlacklisted(it) })
        }
    }

    @Test
    fun privacyStatementIsExact() {
        assertEquals("We never read WhatsApp, Telegram or OTP messages.", PackageWhitelist.PRIVACY_STATEMENT)
    }
}
