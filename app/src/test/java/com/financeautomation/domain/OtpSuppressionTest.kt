package com.financeautomation.domain

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-2: "do not share" boilerplate on real bank alerts must not suppress them, while real OTP messages still are.
 */
class OtpSuppressionTest {

    private val useCase = ParseNotificationUseCase()
    private val sms = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES

    private fun parse(pkg: String, title: String, text: String) = useCase(sourcePackage = pkg, title = title, text = text)

    private fun assertCaptured(pkg: String, title: String, text: String, amount: Double) {
        val r = parse(pkg, title, text)
        assertTrue("expected a capture for [$title] [$text] but got $r", r is NotificationParseResult.Success)
        assertEquals(amount, (r as NotificationParseResult.Success).transaction.totalAmount, 0.001)
    }

    private fun assertDroppedFor(pkg: String, title: String, text: String, reasonName: String) {
        val r = parse(pkg, title, text)
        assertTrue("expected Dropped($reasonName) for [$title] [$text] but got $r", r is NotificationParseResult.Dropped)
        assertEquals(reasonName, (r as NotificationParseResult.Dropped).reason.name)
    }


    @Test
    fun bankDebitAlertsEndingWithDoNotShareBoilerplateAreCaptured() {
        assertCaptured(
            sms, "HDFCBK",
            "Rs 649.00 debited from A/c XX1234 on 12-11-26 to VPA netflix@okaxis (UPI Ref 412345678901). Do not share OTP/PIN with anyone. -HDFC Bank",
            649.0
        )
        assertCaptured(
            sms, "ICICIB",
            "INR 1,200.00 spent on Card XX4455 at AMAZON on 12-Nov. NEVER share OTP, PIN or CVV with anyone. Not you? Call 18002662",
            1200.0
        )
        assertCaptured(
            sms, "SBIUPI",
            "Your A/c X1234 debited Rs.350 on 12/11 by UPI trf to Rajesh Kumar. Do not share OTP with anyone.",
            350.0
        )
    }

    @Test
    fun doNotShareAloneDoesNotSuppress() {
        assertCaptured(sms, "AXISBK", "Do not share your password with anyone. Rs 100 debited from A/c XX9876 at Zomato", 100.0)
    }

    @Test
    fun realOtpMessagesAreStillDropped() {
        val otps = listOf(
            "482910 is your OTP for payment of Rs 500 at Amazon. Do not share it with anyone.",
            "Use 123456 as your verification code for the transaction of Rs 2,000.",
            "Your one-time password is 654321. Rs 500 will be debited from your account after you enter it.",
            "OTP: 556677. Do not share. Valid for 10 minutes."
        )
        for (text in otps) {
            assertDroppedFor(sms, "HDFCBK", text, "OTP_MESSAGE")
        }
    }

    @Test
    fun otpWordWithoutANumericCodeIsNotTreatedAsOtp() {
        assertFalse(PackageWhitelist.isOtpMessage("Never share OTP with anyone. Rs 100 debited from A/c XX1111"))
    }
}
