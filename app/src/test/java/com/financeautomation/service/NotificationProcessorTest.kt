package com.financeautomation.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProcessorTest {

    private val processor = NotificationProcessor(
        pendingCaptureDao = null,
        handledNotificationDao = null,
        accountDao = null,
        preferenceDao = null,
        ownPackageName = "com.financeautomation"
    )

    @Test
    fun process_selfNotification_dropped() = runBlocking {
        val result = processor.process(
            sourcePackage = "com.financeautomation",
            title = "Finance Automation",
            text = "Self alert"
        )
        assertTrue(result is NotificationProcessingOutcome.Ignored)
        assertEquals("Self notification dropped", (result as NotificationProcessingOutcome.Ignored).reason)
    }

    @Test
    fun process_blacklistedPackage_dropped() = runBlocking {
        val result = processor.process(
            sourcePackage = "com.whatsapp",
            title = "Friend",
            text = "Paid you 500"
        )
        assertTrue(result is NotificationProcessingOutcome.Ignored)
        assertTrue((result as NotificationProcessingOutcome.Ignored).reason.contains("Blacklisted privacy package"))
    }

    @Test
    fun process_nonWhitelistedPackage_dropped() = runBlocking {
        val result = processor.process(
            sourcePackage = "com.random.game",
            title = "Game Alert",
            text = "Daily coins reward"
        )
        assertTrue(result is NotificationProcessingOutcome.Ignored)
        assertTrue((result as NotificationProcessingOutcome.Ignored).reason.contains("Non-whitelisted package"))
    }

    @Test
    fun process_otpAlert_suppressed() = runBlocking {
        val result = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Bank OTP",
            text = "Your one-time password OTP is 482910. Do not share this with anyone."
        )
        assertTrue(result is NotificationProcessingOutcome.Ignored)
        assertEquals("OTP or auth alert suppressed", (result as NotificationProcessingOutcome.Ignored).reason)
    }
}

