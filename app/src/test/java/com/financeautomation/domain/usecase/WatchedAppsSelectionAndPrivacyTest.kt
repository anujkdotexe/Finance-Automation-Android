package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.model.ReviewState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves "Watch another app" picker rules:
 * - Blocked personal chat, mail, social, and browser apps never appear in the available list.
 * - An app chosen through "Watch another app" routes to Needs Review only.
 * - The privacy note is prominently defined and displayed.
 */
class WatchedAppsSelectionAndPrivacyTest {

    private val parseUseCase = ParseNotificationUseCase()

    @After
    fun tearDown() {
        PackageWhitelist.removeWatchedApp("com.sbi.lotusintouch")
    }

    @Test
    fun blockedAppsNeverAppearInAvailableList() {
        val installed = listOf(
            "org.telegram.messenger" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal",
            "com.google.android.gm" to "Gmail",
            "com.android.chrome" to "Chrome",
            "org.mozilla.firefox" to "Firefox",
            "com.whatsapp" to "WhatsApp",
            "com.google.android.apps.nbu.paisa.user" to "Google Pay",
            "com.sbi.lotusintouch" to "SBI YONO",
            "com.canarabank.ai1" to "Canara ai1"
        )

        val available = PackageWhitelist.getAvailableAppsToWatch(installed)
        val availablePackageIds = available.map { it.first }

        // Blocked apps must NEVER appear
        assertFalse("Telegram must be blocked", availablePackageIds.contains("org.telegram.messenger"))
        assertFalse("Signal must be blocked", availablePackageIds.contains("org.thoughtcrime.securesms"))
        assertFalse("Gmail must be blocked", availablePackageIds.contains("com.google.android.gm"))
        assertFalse("Chrome must be blocked", availablePackageIds.contains("com.android.chrome"))
        assertFalse("Firefox must be blocked", availablePackageIds.contains("org.mozilla.firefox"))

        // Already whitelisted apps must not appear in "Watch another app"
        assertFalse("Built-in WhatsApp must not appear", availablePackageIds.contains("com.whatsapp"))
        assertFalse("Built-in GPay must not appear", availablePackageIds.contains("com.google.android.apps.nbu.paisa.user"))

        // Banking apps are allowed
        assertTrue("SBI YONO should be available", availablePackageIds.contains("com.sbi.lotusintouch"))
        assertTrue("Canara ai1 should be available", availablePackageIds.contains("com.canarabank.ai1"))
    }

    @Test
    fun chosenAppRoutesToNeedsReviewOnly() {
        val customAppPkg = "com.sbi.lotusintouch"
        PackageWhitelist.addWatchedApp(customAppPkg)
        assertTrue(PackageWhitelist.isGenericWatchedApp(customAppPkg))

        val result = parseUseCase(
            sourcePackage = customAppPkg,
            title = "SBI Alert",
            text = "Debited INR 1,250.00 from A/C XX1234 to Merchant Store on 18-Nov-26",
            timestamp = 1700000000000L
        )

        assertTrue("Generic watched app notification should be parsed successfully", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals("Chosen app must route to NEEDS_REVIEW only", ReviewState.NEEDS_REVIEW, success.transaction.reviewState)
        assertEquals(1250.00, success.transaction.totalAmount, 0.001)
    }

    @Test
    fun privacyNoteIsShownAndMatchesSpecification() {
        assertEquals("We never read personal chats, emails or OTP messages.", PackageWhitelist.PRIVACY_STATEMENT)

        val stringsXml = File("src/main/res/values/strings.xml").readText()
        assertTrue("strings.xml must contain privacy note", stringsXml.contains("payment_apps_privacy_note"))
        assertTrue("strings.xml privacy note must mention personal chats or emails",
            stringsXml.contains("We never read personal chats, emails or OTP messages")
        )

        val screenSource = File("src/main/java/com/financeautomation/presentation/screens/drawer/CapturePermissionsScreen.kt").readText()
        assertTrue("CapturePermissionsScreen must render privacy note",
            screenSource.contains("payment_apps_privacy_note") || screenSource.contains("PRIVACY_STATEMENT")
        )
    }
}
