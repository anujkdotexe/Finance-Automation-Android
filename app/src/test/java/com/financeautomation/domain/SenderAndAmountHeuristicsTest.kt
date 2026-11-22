package com.financeautomation.domain

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.RegexPatterns
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.service.NotificationPreFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R2-4 and R2-18: only bank style sender ids are read, and the amount next to the debit verb wins. */
class SenderAndAmountHeuristicsTest {

    private val sms = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES
    private val parser = ParseNotificationUseCase()

    @Test
    fun realBankHeadersAndShortCodesAreAccepted() {
        for (id in listOf("VM-HDFCBK", "AX-SBIUPI", "JD-PAYTMB", "JX-CANBNK-S", "5676766", "56161", "JioPay-S")) {
            assertTrue("$id should be a bank sender", RegexPatterns.isBankSenderId(id))
        }
    }

    @Test
    fun contactNamesAndPersonalNumbersAreRejected() {
        for (id in listOf("Rahul", "Mom", "Ravi Kumar", "ravi kumar", "+919812345678", "9812345678", "98123 45678", "", "  ", "Mary-Jane", "Jean-Luc", "Anne-Marie", "Smith-Jones", "Rahul-Sharma", "Dr-Rao", "Mr-Sharma", "Rahul-K", "Mom-S", "Raj-Mo", "MOM", "BOSS", "RAHUL", "DAD", "ABC", "HDFCBK")) {
            assertFalse("$id must not be read", RegexPatterns.isBankSenderId(id))
        }
    }

    @Test
    fun theParserAndThePreFilterAgree() {
        val friendText = "paid to you Rs 500 for dinner, received?"
        for (name in listOf("Rahul", "Mom", "Ravi Kumar", "Rahul-K", "Dr-Rao")) {
            assertFalse(NotificationPreFilter.shouldReadFullText(sms, name, friendText))
            val r = parser(sourcePackage = sms, title = name, text = friendText)
            assertTrue("$name -> $r", r is NotificationParseResult.Dropped)
        }
        val bank = "Rs 649.00 debited from A/c XX1234 to VPA shop@okaxis"
        for (header in listOf("VM-HDFCBK", "AX-SBIUPI", "JD-PAYTMB", "5676766", "JioPay-S")) {
            assertTrue("prefilter $header", NotificationPreFilter.shouldReadFullText(sms, header, bank))
            val pr = parser(sourcePackage = sms, title = header, text = bank)
            assertTrue("parse $header -> $pr", pr is NotificationParseResult.Success)
        }
    }

    @Test
    fun aBalanceOrLimitBeforeTheDebitDoesNotWin() {
        assertEquals(450.0, RegexPatterns.extractAmount("Avl Bal Rs 12,000.00. Rs 450 debited at XYZ")!!, 0.001)
        assertEquals(349.0, RegexPatterns.extractAmount("Available limit Rs 15000 left. Rs 349 spent on card XX1234 at SWIGGY")!!, 0.001)
        assertEquals(200.0, RegexPatterns.extractAmount("Bal INR 5,000.00; UPI debit of Rs 200 to a shop")!!, 0.001)
        assertEquals(120.0, RegexPatterns.extractAmount("Rs 120 paid to a shop. Balance Rs 9,999")!!, 0.001)
    }

    @Test
    fun theFirstAmountStillWorksWhenThereIsOnlyOne() {
        assertEquals(500.0, RegexPatterns.extractAmount("Rs 500 payment received")!!, 0.001)
        assertNull(RegexPatterns.extractAmount("no amount here"))
    }

    @Test
    fun toAndAtInsideWordsAreNotMerchantMarkers() {
        assertNotEquals("fare", RegexPatterns.extractMerchant("Rs 200 for auto fare"))
        assertNotEquals("studio", RegexPatterns.extractMerchant("Rs 300 spent on photo studio"))
        assertEquals("Swiggy", RegexPatterns.extractMerchant("Paid Rs 349 to Swiggy using Bank A/c ending 1234"))
    }

    @Test
    fun customerCareEmailIsNotTheMerchant() {
        val text = "Contact care@hdfcbank.com if not you. Rs 300 debited to ravi@okaxis"
        assertEquals("ravi@okaxis", RegexPatterns.extractMerchant(text))
        assertNull(RegexPatterns.VPA_REGEX.find("write to support@hdfcbank.com today")?.groupValues?.get(1))
    }
}
