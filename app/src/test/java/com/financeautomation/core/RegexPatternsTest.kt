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
}
