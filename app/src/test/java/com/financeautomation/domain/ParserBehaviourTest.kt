package com.financeautomation.domain

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.RegexPatterns
import com.financeautomation.domain.usecase.DropReason
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.domain.usecase.WordingClass
import com.financeautomation.service.NotificationProcessingOutcome
import com.financeautomation.service.NotificationProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T3: Parser behavior and notification filter tests.
 * Validates real regex heuristics and ParseNotificationUseCase / NotificationProcessor rules
 * with invented text.
 */
class ParserBehaviourTest {

    private lateinit var useCase: ParseNotificationUseCase

    @Before
    fun setUp() {
        useCase = ParseNotificationUseCase()
    }

    @Test
    fun testPaidRs450ToAshaStores_isDebit450() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Google Pay",
            text = "Paid Rs 450 to Asha Stores"
        )

        assertTrue("Expected Success, got $result", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(450.0, success.transaction.totalAmount, 0.001)
        assertTrue("Must be classified as debit", success.isDebit)
        assertFalse("Must not be credit", success.isCredit)
        assertEquals(WordingClass.SUCCESS, success.wording)
    }

    @Test
    fun testAmountWithIndianCommaSeparators_parsedCorrectly() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Google Pay",
            text = "Rs 1,23,456.50 debited"
        )

        assertTrue("Expected Success, got $result", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(123456.50, success.transaction.totalAmount, 0.001)
        assertTrue("Must be classified as debit", success.isDebit)
    }

    @Test
    fun testAshaPaidYouRs500_isNotDebit() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Google Pay",
            text = "Asha paid you Rs 500"
        )

        assertTrue("Expected Success, got $result", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(500.0, success.transaction.totalAmount, 0.001)
        assertFalse("Must NOT be a debit when paid to you", success.isDebit)
        assertTrue("Must be a credit when paid to you", success.isCredit)
    }

    @Test
    fun testYouReceivedRs500FromKabir_isCredit() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Google Pay",
            text = "You received Rs 500 from Kabir"
        )

        assertTrue("Expected Success, got $result", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(500.0, success.transaction.totalAmount, 0.001)
        assertTrue("Must be classified as credit", success.isCredit)
        assertFalse("Must not be debit", success.isDebit)
    }

    @Test
    fun testOtpMessage_isRejected() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_DEFAULT_SMS,
            title = "AX-HDFCBK",
            text = "Your OTP is 123456 for online purchase at Merchant"
        )

        assertTrue("OTP message must be dropped, got $result", result is NotificationParseResult.Dropped)
        val dropped = result as NotificationParseResult.Dropped
        assertEquals(DropReason.OTP_MESSAGE, dropped.reason)
    }

    @Test
    fun testRefundInitiatedWithAmount_classedAsCreditRefund() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Google Pay",
            text = "Refund of Rs 200 initiated for your order"
        )

        assertTrue("Expected Success for refund with amount, got $result", result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals(200.0, success.transaction.totalAmount, 0.001)
        assertTrue("Refund must be classified as credit", success.isCredit)
        assertFalse("Refund must not be debit", success.isDebit)
    }

    @Test
    fun testMandateNotice_isDropped() {
        val result = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Google Pay",
            text = "Your mandate for Rs 500 will be debited on 25 Nov"
        )

        assertTrue("Mandate notice must be dropped, got $result", result is NotificationParseResult.Dropped)
        val dropped = result as NotificationParseResult.Dropped
        assertEquals(DropReason.MANDATE_NOTICE, dropped.reason)
    }

    @Test
    fun testSenderHeaders_acceptedBanksAndRejectedContacts() {
        // Valid DLT bank sender headers accepted
        assertTrue("HDFCBK must be accepted bank sender", RegexPatterns.isBankSenderId("HDFCBK"))
        assertTrue("AX-HDFCBK-S must be accepted bank sender", RegexPatterns.isBankSenderId("AX-HDFCBK-S"))
        assertTrue("VM-SBIUPI-S must be accepted bank sender", RegexPatterns.isBankSenderId("VM-SBIUPI-S"))

        // Personal contact names rejected before reading message text
        assertFalse("Contact name RAHUL must be rejected", RegexPatterns.isBankSenderId("RAHUL"))
        assertFalse("Contact name DAD must be rejected", RegexPatterns.isBankSenderId("DAD"))
        assertFalse("Contact name MOM must be rejected", RegexPatterns.isBankSenderId("MOM"))

        // When personal contact name is SMS sender, notification is dropped without reading text
        val contactSmsResult = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_DEFAULT_SMS,
            title = "RAHUL",
            text = "Paid Rs 500 for dinner"
        )
        assertTrue("Personal contact SMS must be dropped, got $contactSmsResult", contactSmsResult is NotificationParseResult.Dropped)
        assertEquals(DropReason.NOT_WHITELISTED, (contactSmsResult as NotificationParseResult.Dropped).reason)

        // When bank sender SMS arrives, financial transaction is processed
        val bankSmsResult = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_DEFAULT_SMS,
            title = "HDFCBK",
            text = "Rs 450 debited from A/c XX1234 on 22-Nov-26"
        )
        assertTrue("Bank SMS debit must be parsed, got $bankSmsResult", bankSmsResult is NotificationParseResult.Success)
        assertEquals(450.0, (bankSmsResult as NotificationParseResult.Success).transaction.totalAmount, 0.001)
    }

    @Test
    fun testWhatsAppNonPaymentNotification_droppedWithNoParseCall() = runBlocking {
        val processor = NotificationProcessor(
            parseUseCase = useCase
        )

        // A regular chat message from WhatsApp (no payment channel / category)
        // Even if the text contains transaction keywords ("Paid Rs 500 to Asha"),
        // it must be dropped at the chat guard before parsing.
        val outcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_WHATSAPP,
            title = "Kabir",
            text = "Paid Rs 500 to Asha",
            channelId = "chat_messages",
            category = null
        )

        assertTrue("Outcome must be Ignored, got $outcome", outcome is NotificationProcessingOutcome.Ignored)
        val ignored = outcome as NotificationProcessingOutcome.Ignored
        assertTrue("Must indicate non-payment chat notification dropped", ignored.reason.contains("Non-payment chat notification dropped"))

        // Also verify ParseNotificationUseCase itself drops non-payment WhatsApp notifications as BLACKLISTED
        val directResult = useCase(
            sourcePackage = PackageWhitelist.PACKAGE_WHATSAPP,
            title = "Kabir",
            text = "Paid Rs 500 to Asha",
            channelId = null,
            category = null
        )
        assertTrue("Direct WhatsApp chat notification must be dropped as BLACKLISTED", directResult is NotificationParseResult.Dropped)
        assertEquals(DropReason.BLACKLISTED, (directResult as NotificationParseResult.Dropped).reason)
    }
}
