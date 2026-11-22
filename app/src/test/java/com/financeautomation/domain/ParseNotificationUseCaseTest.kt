package com.financeautomation.domain

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.domain.usecase.DropReason
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParseNotificationUseCaseTest {

    private lateinit var useCase: ParseNotificationUseCase

    @Before
    fun setUp() {
        useCase = ParseNotificationUseCase()
    }

    @Test
    fun parsesPaidYouAsCredit() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Rahul paid you",
            text = "Rahul paid you Rs 500"
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(500.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isCredit)
        assertFalse(success.isDebit)
    }

    @Test
    fun parsesSentYouAsCredit() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Payment received",
            text = "Sample Friend sent you Rs 350"
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(350.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isCredit)
        assertFalse(success.isDebit)
    }

    @Test
    fun parsesNormalPaidToMerchantAsDebit() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Paid Rs 120 to Sample Store",
            text = "Paid Rs 120 to Sample Store using Bank A/c ending 1234"
        )
        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(120.0, success.transaction.totalAmount, 0.001)
        assertTrue(success.isDebit)
        assertFalse(success.isCredit)
    }

    @Test
    fun testParseWhitelistedGooglePayTransaction() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Payment to Swiggy",
            text = "Paid Rs 349.00 to Swiggy using Bank A/c ending 1234. UPI Ref: 123456789012"
        )

        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(349.00, success.transaction.totalAmount, 0.001)
        assertEquals("Swiggy", success.transaction.merchant)
        assertTrue(success.isDebit)
    }

    @Test
    fun testParseWhitelistedPhonePeTransaction() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_PHONEPE,
            title = "Payment Successful",
            text = "Debited INR 750 to Cafe Coffee Day. Txn ID: 987654321098"
        )

        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(750.00, success.transaction.totalAmount, 0.001)
        assertEquals("Cafe Coffee Day", success.transaction.merchant)
    }

    @Test
    fun testParseWhitelistedSmsAlert() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "AX-BANKAL",
            text = "A/c 4321 debited for INR 1,500.00 on 05-Nov. Ref: 556677889900"
        )

        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(1500.00, success.transaction.totalAmount, 0.001)
    }

    @Test
    fun testNonWhitelistedPackageIsDropped() {
        val result = useCase(
            sourcePackage = "com.unrelated.game",
            title = "Game Alert",
            text = "Paid 50 coins"
        )

        assertTrue(result is NotificationParseResult.Dropped)
        val dropped = result as NotificationParseResult.Dropped
        assertEquals(DropReason.NOT_WHITELISTED, dropped.reason)
    }

    @Test
    fun testBlacklistedPrivacyAppIsDropped() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_WHATSAPP,
            title = "Mom",
            text = "Paid Rs 500 for groceries"
        )

        assertTrue(result is NotificationParseResult.Dropped)
        val dropped = result as NotificationParseResult.Dropped
        assertEquals(DropReason.BLACKLISTED, dropped.reason)
    }

    @Test
    fun testOtpAlertIsDropped() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "AX-BANKOT",
            text = "Your OTP is 987654. Do not share this verification code with anyone."
        )

        assertTrue(result is NotificationParseResult.Dropped)
        val dropped = result as NotificationParseResult.Dropped
        assertEquals(DropReason.OTP_MESSAGE, dropped.reason)
    }

    @Test
    fun testSelfNotificationIsDropped() {
        val result = useCase(
            sourcePackage = "com.financeautomation",
            title = "Expense Logged",
            text = "Paid Rs 100"
        )

        assertTrue(result is NotificationParseResult.Dropped)
        val dropped = result as NotificationParseResult.Dropped
        assertEquals(DropReason.SELF_NOTIFICATION, dropped.reason)
    }

    @Test
    fun testWhitelistedWithoutExtractableAmountSurfacesFallbackPrompt() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_PAYTM,
            title = "Payment Processing",
            text = "Transaction in progress. Please check your order history."
        )

        assertTrue(result is NotificationParseResult.FallbackPrompt)
        val fallback = result as NotificationParseResult.FallbackPrompt
        assertEquals("Payment detected, tap to enter amount", fallback.message)
    }

    @Test
    fun hostilePayeeWithBidiAndControlCharactersIsSanitizedAndCapped() {
        val longHostileMerchant = "A".repeat(120) + "\u202E\u0000Evil"
        val hostileText = "Paid ₹500.00 to $longHostileMerchant via UPI"
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Google Pay",
            text = hostileText
        )

        assertTrue(result is NotificationParseResult.Success)
        val txn = (result as NotificationParseResult.Success).transaction
        assertTrue("Merchant length must be capped at 80", txn.merchant.length <= 80)
        assertFalse("Must not contain bidi overrides", txn.merchant.contains("\u202E"))
        assertFalse("Must not contain null control character", txn.merchant.contains("\u0000"))
    }
}
