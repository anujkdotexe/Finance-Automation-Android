package com.financeautomation.core

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.RegexPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexPatternsTest {

    @Test
    fun testAmountExtractionStandard() {
        val text1 = "Paid Rs. 1,234.50 to Starbucks"
        assertEquals(1234.50, requireNotNull(RegexPatterns.extractAmount(text1)), 0.001)

        val text2 = "Debited by INR 500.00 for grocery"
        assertEquals(500.00, requireNotNull(RegexPatterns.extractAmount(text2)), 0.001)

        val text3 = "Spent ₹99 at tea stall"
        assertEquals(99.0, requireNotNull(RegexPatterns.extractAmount(text3)), 0.001)
    }

    @Test
    fun testMerchantExtraction() {
        val text1 = "Paid to Swiggy on 12-Nov"
        assertEquals("Swiggy", RegexPatterns.extractMerchant(text1))

        val text2 = "Paid to merchant@okaxis ref: 123456789012"
        assertEquals("merchant@okaxis", RegexPatterns.extractMerchant(text2))
    }

    @Test
    fun testAccountExtraction() {
        val text = "A/c ending 1234 debited by INR 500"
        val acct = RegexPatterns.extractAccount(text)
        assertNotNull(acct)
        assertEquals("A/c XX1234", acct)
    }

    @Test
    fun testAccountExtractionWithAmountPreceding() {
        val text = "Paid Rs 349.00 to Swiggy using Bank A/c ending 1234."
        val acct = RegexPatterns.extractAccount(text)
        assertNotNull(acct)
        assertEquals("Bank XX1234", acct)
    }

    @Test
    fun testAccountExtractionPrepositionCleaning() {
        val text1 = "using Bank A/c ending 1234 debited by 50."
        assertEquals("Bank XX1234", RegexPatterns.extractAccount(text1))

        val text2 = "from HDFC Bank Account ending 5678"
        assertEquals("HDFC Bank XX5678", RegexPatterns.extractAccount(text2))

        val text3 = "SBI A/c XX1234 debited"
        assertEquals("SBI XX1234", RegexPatterns.extractAccount(text3))
    }

    @Test
    fun testAccountExtractionFallbackPatterns() {
        val text1 = "Paid Rs 500 ending with 1234"
        assertEquals("A/c XX1234", RegexPatterns.extractAccount(text1))

        val text2 = "Paid Rs 500 XX5678"
        assertEquals("A/c XX5678", RegexPatterns.extractAccount(text2))

        val text3 = "Paid Rs 500 ...9876"
        assertEquals("A/c XX9876", RegexPatterns.extractAccount(text3))
    }

    @Test
    fun testAccountExtractionDoesNotMatchAmounts() {
        assertNull(RegexPatterns.extractAccount("Paid Rs 349.00 to Swiggy"))
        assertNull(RegexPatterns.extractAccount("Paid INR 500 to Cafe Coffee Day"))
        assertNull(RegexPatterns.extractAccount("Spent ₹99 at tea stall"))
    }

    @Test
    fun testDirectionFlags() {
        assertTrue(RegexPatterns.isDebit("INR 450 debited from A/c"))
        assertFalse(RegexPatterns.isDebit("INR 5000 credited to A/c"))

        assertTrue(RegexPatterns.isCredit("INR 1200 received from Alice"))
        assertFalse(RegexPatterns.isCredit("INR 200 spent on fuel"))
    }

    @Test
    fun testOtpSuppression() {
        val otpText = "Your one-time password (OTP) is 456789. Do not share this with anyone."
        assertTrue(PackageWhitelist.isOtpMessage(otpText))

        val normalTxn = "Paid Rs 350 to Cafe Coffee Day via UPI Ref 112233445566"
        assertFalse(PackageWhitelist.isOtpMessage(normalTxn))
    }

    @Test
    fun testWhitelistAndBlacklist() {
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_GPAY))
        assertTrue(PackageWhitelist.isWhitelisted(PackageWhitelist.PACKAGE_PHONEPE))
        assertFalse(PackageWhitelist.isWhitelisted("com.random.app"))

        assertTrue(PackageWhitelist.isBlacklisted("com.whatsapp"))
        assertTrue(PackageWhitelist.isBlacklisted("org.telegram.messenger"))
        assertFalse(PackageWhitelist.isBlacklisted(PackageWhitelist.PACKAGE_GPAY))
    }

    @Test
    fun testFinancialKeywordsRegexMatchesNewCandidatesAndEnforcesWordBoundaries() {
        // Words added in P1-8 keyword gate
        assertTrue("acct", RegexPatterns.hasFinancialKeywords("Acct XXX234 Dr. INR 1,499.00"))
        assertTrue("dr.", RegexPatterns.hasFinancialKeywords("Dr. INR 500 on 15/09/26"))
        assertTrue("dr", RegexPatterns.hasFinancialKeywords("INR 500 Dr to Demo Mart"))
        assertTrue("cr.", RegexPatterns.hasFinancialKeywords("Cr. INR 500 on 15/09/26"))
        assertTrue("cr", RegexPatterns.hasFinancialKeywords("INR 500 Cr from Demo Mart"))
        assertTrue("debit", RegexPatterns.hasFinancialKeywords("debit of INR 500 processed"))
        assertTrue("debits", RegexPatterns.hasFinancialKeywords("INR 500 debits on 12/03"))
        assertTrue("credit", RegexPatterns.hasFinancialKeywords("credit of INR 500 received"))
        assertTrue("credits", RegexPatterns.hasFinancialKeywords("INR 500 credits on 12/03"))
        assertTrue("ac", RegexPatterns.hasFinancialKeywords("INR 500 from ac XX1234"))
        assertTrue("a/c no", RegexPatterns.hasFinancialKeywords("INR 500 from A/c no 1234"))
        assertTrue("payment of", RegexPatterns.hasFinancialKeywords("payment of Rs 500 done"))
        assertTrue("successfully paid", RegexPatterns.hasFinancialKeywords("successfully paid Rs 200 via Autopay"))
        assertTrue("upi ref", RegexPatterns.hasFinancialKeywords("upi ref 12345678"))
        assertTrue("upi:", RegexPatterns.hasFinancialKeywords("UPI: 600000000002"))

        // Word boundary tests: Dr. Rao is allowed through the gate (matches dr.) but dropped later
        assertTrue("Dr. Rao allowed through gate", RegexPatterns.hasFinancialKeywords("Dr. Rao presents exciting offers on electronics"))

        // Word boundary tests: cream, decree, acre must NOT match
        assertFalse("cream must not match", RegexPatterns.hasFinancialKeywords("buy cold cream at 20% discount"))
        assertFalse("decree must not match", RegexPatterns.hasFinancialKeywords("royal decree announced"))
        assertFalse("acre must not match", RegexPatterns.hasFinancialKeywords("buy 5 acre land today"))
        assertFalse("discredited must not match", RegexPatterns.hasFinancialKeywords("discredited news source"))
        assertFalse("indebited must not match", RegexPatterns.hasFinancialKeywords("indebited party details"))
    }

    @Test
    fun testHindiAndMarathiRechargePatternRecognition() {
        // Z4: Hindi recharge confirmation
        val hiText = "रिचार्ज सफल रहा!\nप्लान का नाम: 899.00 \nJio नंबर : 9876543210\nट्रांज़ैक्शन आईडी : BR123456"
        assertEquals(899.0, RegexPatterns.extractAmount(hiText)!!, 0.001)
        assertTrue("Hindi confirmation", RegexPatterns.hasConfirmation(hiText))
        assertTrue("Hindi debit confirmation", RegexPatterns.hasDebitConfirmation(hiText))
        assertTrue("Hindi payment successful", RegexPatterns.isPaymentSuccessful(hiText))
        assertEquals("Jio", RegexPatterns.extractBiller("JioPay-S", hiText))

        // Z4: Marathi recharge confirmation
        val mrText = "रिचार्ज यशस्वी! \nप्लॅन चे नाव : 899.00 \nJio नंबर : 9876543210\nट्रान्झॅक्शन आयडी : BR123456"
        assertEquals(899.0, RegexPatterns.extractAmount(mrText)!!, 0.001)
        assertTrue("Marathi confirmation", RegexPatterns.hasConfirmation(mrText))
        assertTrue("Marathi debit confirmation", RegexPatterns.hasDebitConfirmation(mrText))
        assertTrue("Marathi payment successful", RegexPatterns.isPaymentSuccessful(mrText))
        assertEquals("Jio", RegexPatterns.extractBiller("JioPay-S", mrText))

        // English recharge confirmation
        val engText = "Recharge Successful !\nPlan Name : 899.00\nJio Number : 9876543210\nTransaction ID : BR123456"
        assertEquals(899.0, RegexPatterns.extractAmount(engText)!!, 0.001)
        assertTrue("English confirmation", RegexPatterns.hasConfirmation(engText))
        assertTrue("English debit confirmation", RegexPatterns.hasDebitConfirmation(engText))
        assertTrue("English payment successful", RegexPatterns.isPaymentSuccessful(engText))
        assertEquals("Jio", RegexPatterns.extractBiller("JioPay-S", engText))
    }

    @Test
    fun testHyphenatedBankHeadersAndContactNameRejection() {
        // Z1: DLT headers with channel suffix or operator prefix must be accepted
        val validHeaders = listOf(
            "JioPay-S", "JIOPAY-S", "CANBNK-S", "HDFCBK-S", "SBIBNK-S", "blnkit-S", "iPaytm-S",
            "VM-HDFCBK", "AX-SBIUPI", "JD-PAYTMB", "JX-CANBNK-S", "HDFCBK", "5676766", "56161"
        )
        for (header in validHeaders) {
            assertTrue("Header $header must be accepted as bank sender", RegexPatterns.isBankSenderId(header))
        }

        // Z1: Hyphenated personal contact names and numbers must be rejected
        val rejectedContacts = listOf(
            "Mary-Jane", "Jean-Luc", "Anne-Marie", "Smith-Jones", "Rahul-Sharma", "Ali-Khan",
            "Rahul", "Mom", "Ravi Kumar", "ravi kumar", "+919812345678", "9812345678", ""
        )
        for (contact in rejectedContacts) {
            assertFalse("Contact $contact must be rejected", RegexPatterns.isBankSenderId(contact))
        }
    }
}
