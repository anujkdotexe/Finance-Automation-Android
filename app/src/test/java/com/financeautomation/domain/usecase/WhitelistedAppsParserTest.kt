package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.PackageWhitelist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhitelistedAppsParserTest {

    private lateinit var parseNotificationUseCase: ParseNotificationUseCase

    @Before
    fun setUp() {
        parseNotificationUseCase = ParseNotificationUseCase()
    }

    @Test
    fun whitelistRejectsDebugShellAndTestPackages() {
        assertFalse(PackageWhitelist.isWhitelisted("com.android.shell"))
        assertFalse(PackageWhitelist.isWhitelisted("com.financeautomation.test"))
        assertFalse(PackageWhitelist.isWhitelisted("com.financeautomation.debug"))
        assertFalse(PackageWhitelist.isWhitelisted(""))
        assertFalse(PackageWhitelist.isWhitelisted("com.unknown.app"))
    }

    @Test
    fun whitelistAcceptsOnlyGenuineFinancialPackages() {
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_GPAY))
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_BHIM))
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_SUPERMONEY))
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_PHONEPE))
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_PAYTM))
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_DEFAULT_SMS))
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_GOOGLE_MESSAGES))
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_SAMSUNG_MESSAGES))
    }

    @Test
    fun blacklistedMessagingAndEmailPackagesAreRejected() {
        assertTrue(PackageWhitelist.isBlacklisted("com.whatsapp"))
        assertTrue(PackageWhitelist.isBlacklisted("com.whatsapp.w4b"))
        assertTrue(PackageWhitelist.isBlacklisted("org.telegram.messenger"))
        assertTrue(PackageWhitelist.isBlacklisted("org.thoughtcrime.securesms"))
        assertTrue(PackageWhitelist.isBlacklisted("com.facebook.orca"))
        assertTrue(PackageWhitelist.isBlacklisted("com.instagram.android"))
        assertTrue(PackageWhitelist.isBlacklisted("com.discord"))
        assertTrue(PackageWhitelist.isBlacklisted("com.microsoft.office.outlook"))
        assertTrue(PackageWhitelist.isBlacklisted("com.google.android.gm"))

        assertFalse(PackageWhitelist.isWhitelisted("com.whatsapp"))
        assertFalse(PackageWhitelist.isWhitelisted("org.telegram.messenger"))
        assertFalse(PackageWhitelist.isWhitelisted("org.thoughtcrime.securesms"))

        val parseResult = parseNotificationUseCase(
            sourcePackage = "com.whatsapp",
            title = "Mom",
            text = "Sent you Rs 500",
            timestamp = 1700000000000L
        )
        assertTrue(parseResult is NotificationParseResult.Dropped)
        assertEquals(DropReason.BLACKLISTED, (parseResult as NotificationParseResult.Dropped).reason)
    }

    @Test
    fun otpAlertsAreIdentifiedAndDropped() {
        assertTrue(PackageWhitelist.isOtpMessage("Your OTP for HDFC NetBanking is 482910. Do not share with anyone."))
        assertTrue(PackageWhitelist.isOtpMessage("Use 123456 as verification code for login."))
        assertTrue(PackageWhitelist.isOtpOrAuth("482910 is your authentication code"))

        val parseResult = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "HDFCBK",
            text = "Your OTP for transaction of Rs 500.00 is 948102. Valid for 10 mins.",
            timestamp = 1700000000000L
        )
        assertTrue(parseResult is NotificationParseResult.Dropped)
        assertEquals(DropReason.OTP_MESSAGE, (parseResult as NotificationParseResult.Dropped).reason)
    }

    @Test
    fun parseGooglePayDebitNotification() {
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Paid to Blue Tokai Coffee",
            text = "Paid Rs 340.00 to Blue Tokai Coffee Roasters using HDFC Bank account **1234",
            timestamp = 1700000000000L
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(340.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isDebit)
        assertFalse(success.isCredit)
        assertTrue(success.transaction.merchant.contains("Blue Tokai"))
    }

    @Test
    fun parsePhonePeDebitNotification() {
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_PHONEPE,
            title = "Payment Successful",
            text = "Paid Rs 850 to Swiggy via PhonePe UPI",
            timestamp = 1700000000000L
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(850.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isDebit)
    }

    @Test
    fun parsePaytmDebitNotification() {
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_PAYTM,
            title = "Money Sent",
            text = "Sent Rs 120.00 to Chai Point successfully",
            timestamp = 1700000000000L
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(120.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isDebit)
    }

    @Test
    fun parseBhimDebitNotification() {
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_BHIM,
            title = "BHIM UPI",
            text = "Debited Rs 500.00 from Account XX5678 to Merchant",
            timestamp = 1700000000000L
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(500.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isDebit)
    }

    @Test
    fun parseSupermoneyDebitNotification() {
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_SUPERMONEY,
            title = "Payment Successful",
            text = "Rs 250 debited for order #49102",
            timestamp = 1700000000000L
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(250.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isDebit)
    }

    @Test
    fun parseBankSmsDebitNotification() {
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "HDFCBK",
            text = "Rs 1499.00 debited from a/c **4321 on 15-Nov-26 at Amazon India. Info: UPI/123456/Retail",
            timestamp = 1700000000000L
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(1499.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isDebit)
    }

    @Test
    fun unreadableAmountReturnsFallbackPrompt() {
        val result = parseNotificationUseCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Payment Successful",
            text = "Paid to merchant via UPI",
            timestamp = 1700000000000L
        )
        assertTrue(result is NotificationParseResult.FallbackPrompt)
        val prompt = result as NotificationParseResult.FallbackPrompt
        assertEquals("DEBIT", prompt.kind)
        assertEquals("NEEDS_AMOUNT", prompt.flags)
    }
}
