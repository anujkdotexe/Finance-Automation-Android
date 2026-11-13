package com.financeautomation.service

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.domain.model.NotificationSimulatorPresets
import com.financeautomation.domain.usecase.DropReason
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSimulatorTest {

    private val parser = ParseNotificationUseCase(ownPackageName = "com.financeautomation")

    private val processor = NotificationProcessor(
        pendingCaptureDao = null,
        handledNotificationDao = null,
        accountDao = null,
        preferenceDao = null,
        transactionDao = null,
        ownPackageName = "com.financeautomation"
    )

    @Test
    fun testAllSixValidPresetsParseToExpectedAmountAndMerchant() {
        val validPresets = NotificationSimulatorPresets.PRESETS.filter { it.expectedAmount != null }
        assertEquals(6, validPresets.size)

        for (preset in validPresets) {
            val result = parser(
                sourcePackage = preset.sourcePackage,
                title = preset.title,
                text = preset.text
            )

            assertTrue(
                "Preset ${preset.id} failed to parse successfully: $result",
                result is NotificationParseResult.Success
            )
            val success = result as NotificationParseResult.Success
            assertEquals(
                "Preset ${preset.id} amount mismatch",
                preset.expectedAmount!!,
                success.transaction.totalAmount,
                0.001
            )
            assertEquals(
                "Preset ${preset.id} merchant mismatch",
                preset.expectedMerchant,
                success.transaction.merchant
            )
        }
    }

    @Test
    fun testUnreadableAmountYieldsFallbackPromptWithNeedsAmountFlag() {
        val unreadablePreset = NotificationSimulatorPresets.PRESETS.first { it.id == "unreadable_amount" }
        val result = parser(
            sourcePackage = unreadablePreset.sourcePackage,
            title = unreadablePreset.title,
            text = unreadablePreset.text
        )

        assertTrue(
            "Expected FallbackPrompt for unreadable amount, got $result",
            result is NotificationParseResult.FallbackPrompt
        )
        val fallback = result as NotificationParseResult.FallbackPrompt
        assertTrue(
            "Expected flags to contain NEEDS_AMOUNT, got ${fallback.flags}",
            fallback.flags.contains("NEEDS_AMOUNT")
        )
    }

    @Test
    fun testBlockedMessagingAppsDroppedWithBlacklistedReason() = runBlocking {
        val blockedPackages = listOf(
            PackageWhitelist.PACKAGE_WHATSAPP,
            PackageWhitelist.PACKAGE_TELEGRAM,
            PackageWhitelist.PACKAGE_SIGNAL
        )

        for (pkg in blockedPackages) {
            // Verify ParseNotificationUseCase drops with BLACKLISTED
            val parseResult = parser(
                sourcePackage = pkg,
                title = "Friend",
                text = "Sent you Rs 500"
            )
            assertTrue(
                "Expected Dropped for $pkg, got $parseResult",
                parseResult is NotificationParseResult.Dropped
            )
            assertEquals(
                "Expected DropReason.BLACKLISTED for $pkg",
                DropReason.BLACKLISTED,
                (parseResult as NotificationParseResult.Dropped).reason
            )

            // Verify NotificationProcessor drops / ignores with Ignored
            val processResult = processor.process(
                sourcePackage = pkg,
                title = "Friend",
                text = "Sent you Rs 500"
            )
            assertTrue(
                "Expected NotificationProcessingOutcome.Ignored for $pkg, got $processResult",
                processResult is NotificationProcessingOutcome.Ignored
            )
        }
    }

    @Test
    fun testOtpMessagesDroppedWithOtpMessageReason() = runBlocking {
        val otpTexts = listOf(
            "Your OTP for payment is 482910. Do not share this with anyone.",
            "Use verification code 903124 to authorize your transaction.",
            "847291 is your one time password to approve payment of Rs 1000."
        )

        for (text in otpTexts) {
            // Verify ParseNotificationUseCase drops with OTP_MESSAGE
            val parseResult = parser(
                sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
                title = "AX-HDFCBK",
                text = text
            )
            assertTrue(
                "Expected Dropped for OTP: $text, got $parseResult",
                parseResult is NotificationParseResult.Dropped
            )
            assertEquals(
                "Expected DropReason.OTP_MESSAGE for OTP: $text",
                DropReason.OTP_MESSAGE,
                (parseResult as NotificationParseResult.Dropped).reason
            )

            // Verify NotificationProcessor drops / ignores with Ignored
            val processResult = processor.process(
                sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
                title = "AX-HDFCBK",
                text = text
            )
            assertTrue(
                "Expected NotificationProcessingOutcome.Ignored for OTP, got $processResult",
                processResult is NotificationProcessingOutcome.Ignored
            )
        }
    }
}

