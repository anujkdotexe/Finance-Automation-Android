package com.financeautomation.service

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-3: the sender and keyword filter runs on the title and a short text prefix before any full text is read. */
class NotificationPreFilterTest {

    private val sms = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES

    @Test
    fun isAllowedSenderRejectsPersonalSmsAndAcceptsBankSmsAndPaymentApps() {
        assertFalse(NotificationPreFilter.isAllowedSender(sms, "+919812345678"))
        assertFalse(NotificationPreFilter.isAllowedSender(sms, "John Doe"))
        assertTrue(NotificationPreFilter.isAllowedSender(sms, "VM-HDFCBK"))
        assertTrue(NotificationPreFilter.isAllowedSender(sms, "AX-SBIINB"))
        assertTrue(NotificationPreFilter.isAllowedSender(PackageWhitelist.PACKAGE_GPAY, "Swiggy"))
    }

    @Test
    fun smsFromAPersonalNumberIsNotReadFurther() {
        assertFalse(NotificationPreFilter.shouldReadFullText(sms, "+919812345678", "Hi, paid you Rs 500 for lunch"))
        assertFalse(NotificationPreFilter.shouldReadFullText(sms, "98123 45678", "debited Rs 100"))
    }

    @Test
    fun smsWithoutFinancialKeywordsIsNotReadFurther() {
        assertFalse(NotificationPreFilter.shouldReadFullText(sms, "JIOINF", "Your plan expires tomorrow. Recharge now"))
    }

    @Test
    fun bankSmsIsReadFurther() {
        assertTrue(NotificationPreFilter.shouldReadFullText(sms, "VM-HDFCBK", "Rs 649 debited from A/c XX1234 to VPA netflix@okaxis"))
    }

    @Test
    fun paymentAppsAreAlwaysReadFurther() {
        assertTrue(NotificationPreFilter.shouldReadFullText(PackageWhitelist.PACKAGE_GPAY, "Paid ₹349 to Swiggy", ""))
    }

    @Test
    fun onlyAShortPrefixOfTheTextIsInspected() {
        assertEquals(400, NotificationPreFilter.PREFIX_LENGTH)
        val longNoise = "x".repeat(2000) + " debited"
        assertFalse(NotificationPreFilter.shouldReadFullText(sms, "BANK", longNoise))
    }

    @Test
    fun parserChecksThePersonalSenderBeforeLookingAtTheMessageText() {
        // A personal number sending text that looks like an OTP must be rejected as not whitelisted,
        // not classified (and therefore read) as an OTP message.
        val r = ParseNotificationUseCase()(sourcePackage = sms, title = "+919812345678", text = "Your OTP is 482910 for Rs 500")
        assertTrue(r is NotificationParseResult.Dropped)
        assertEquals("NOT_WHITELISTED", (r as NotificationParseResult.Dropped).reason.name)
    }
}
