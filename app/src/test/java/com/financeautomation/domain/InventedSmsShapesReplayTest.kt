package com.financeautomation.domain

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.RegexPatterns
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.service.NotificationPreFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

class InventedSmsShapesReplayTest {

    private val useCase = ParseNotificationUseCase()
    private val smsPackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES

    // Legacy regex before P1-8 fix
    private val oldKeywordsRegex = Regex(
        """(?i)\b(debited|credited|withdrawn|transferred|txn|a/c|account|spent|paid\s+thru|paid\s+to|received)\b"""
    )

    @Test
    fun testReplayInventedSmsShapesKeywordGateAllMessages() {
        val stream = javaClass.classLoader?.getResourceAsStream("invented_sms_shapes.json")
            ?: error("invented_sms_shapes.json resource not found in test resources")
        val jsonText = InputStreamReader(stream).use { it.readText() }
        val rootObj = Json.parseToJsonElement(jsonText).jsonObject
        val messages = rootObj["messages"]?.jsonArray ?: error("Missing messages array")

        val realTxnExpected = setOf("DEBIT", "CREDIT", "REVIEW_REFUND")
        var realTxnCount = 0
        var realTxnDroppedAtGateCount = 0
        var negativeCount = 0
        var negativeDroppedAtGateCount = 0
        var negativePassedGateCount = 0

        for (elem in messages) {
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
            val sender = obj["sender"]?.jsonPrimitive?.content.orEmpty()
            val expected = obj["expected"]?.jsonPrimitive?.content.orEmpty()
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
            val category = obj["category"]?.jsonPrimitive?.content.orEmpty()

            val passesGate = NotificationPreFilter.shouldReadFullText(smsPackage, sender, text)

            if (expected in realTxnExpected) {
                realTxnCount++
                if (!passesGate) {
                    realTxnDroppedAtGateCount++
                }
                assertTrue(
                    "Real transaction fixture $id ($category) must pass keyword gate: text='$text'",
                    passesGate
                )
            } else {
                negativeCount++
                if (passesGate) {
                    negativePassedGateCount++
                } else {
                    negativeDroppedAtGateCount++
                }
            }
        }

        // W1: Complete 34-shape per-message replay assertions
        assertEquals("Total fixtures in test set", 34, messages.size)
        assertEquals("Real debit/credit/refund fixtures count", 16, realTxnCount)
        assertEquals("Zero real DEBIT or CREDIT fixtures dropped at KEYWORD_GATE", 0, realTxnDroppedAtGateCount)

        // Non-financial negatives (I14, I18, I25, I26, I30, I31, I32) dropped at gate
        assertTrue("Some negative non-financial messages dropped at gate", negativeDroppedAtGateCount > 0)
        assertTrue("Some negative messages with financial tokens pass gate to be dropped downstream", negativePassedGateCount > 0)

        // Specific negative gate drop checks
        assertFalse("Telecom recharge non-bank sender I31 dropped at gate",
            NotificationPreFilter.shouldReadFullText(smsPackage, "JIOINF", "Your daily data limit of 1.5 GB is exhausted."))
        assertFalse("Non-bank sender I32 dropped at gate",
            NotificationPreFilter.shouldReadFullText(smsPackage, "VI-OFFER", "Special recharge offer: get 5GB free today."))
    }

    @Test
    fun testOldRegexComparisonDemonstratesMissedDebitRecovery() {
        val stream = javaClass.classLoader?.getResourceAsStream("invented_sms_shapes.json")
            ?: error("invented_sms_shapes.json resource not found in test resources")
        val jsonText = InputStreamReader(stream).use { it.readText() }
        val rootObj = Json.parseToJsonElement(jsonText).jsonObject
        val messages = rootObj["messages"]?.jsonArray ?: error("Missing messages array")

        val realTxnExpected = setOf("DEBIT", "CREDIT", "REVIEW_REFUND")
        var oldRegexMisses = 0
        var newRegexMisses = 0

        for (elem in messages) {
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
            val expected = obj["expected"]?.jsonPrimitive?.content.orEmpty()
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()

            if (expected in realTxnExpected) {
                if (!oldKeywordsRegex.containsMatchIn(text)) {
                    oldRegexMisses++
                    // Specifically, I03 (Acct ... Dr. ... UPI:) was dropped by old regex
                    assertEquals("I03", id)
                }
                if (!RegexPatterns.hasFinancialKeywords(text)) {
                    newRegexMisses++
                }
            }
        }

        // W2: Proves old regex dropped I03 (1 miss of 16), while new regex has 0 misses
        assertEquals("Old regex dropped exactly 1 real transaction fixture (I03)", 1, oldRegexMisses)
        assertEquals("New regex recovered all misses: 0 dropped among real transactions", 0, newRegexMisses)
    }

    @Test
    fun testWordBoundaryTokensDebitsCreditsAcAndAcNo() {
        // V1 token verification
        assertTrue("debits token", RegexPatterns.hasFinancialKeywords("Your acct debits INR 500"))
        assertTrue("credits token", RegexPatterns.hasFinancialKeywords("Your acct credits INR 500"))
        assertTrue("ac token", RegexPatterns.hasFinancialKeywords("INR 500 from ac XX1234"))
        assertTrue("a/c no token", RegexPatterns.hasFinancialKeywords("INR 500 from A/c no 1234"))

        // Negatives: word boundaries must prevent false substrings
        assertFalse("pacemaker must not match ac", RegexPatterns.hasFinancialKeywords("pacemaker checkup scheduled"))
        assertFalse("back must not match ac", RegexPatterns.hasFinancialKeywords("come back tomorrow"))
        assertFalse("cream must not match cr.", RegexPatterns.hasFinancialKeywords("buy cold cream at 20% discount"))
        assertFalse("decree must not match cr.", RegexPatterns.hasFinancialKeywords("royal decree announced"))
        assertFalse("acre must not match cr.", RegexPatterns.hasFinancialKeywords("buy 5 acre land today"))
    }

    @Test
    fun testDrRaoAllowedThroughGateButDroppedDownstream() {
        val promoText = "Dr. Rao presents exciting offers on electronics. Visit store today."
        val sender = "HDFCBK"

        // Gate level: matches 'dr.' so allowed through initial keyword gate
        val passesGate = NotificationPreFilter.shouldReadFullText(smsPackage, sender, promoText)
        assertTrue("Dr. Rao matches dr. so it passes keyword gate", passesGate)

        // V4: Downstream parser level: must be dropped as non-transaction / promotional without amount
        val parseResult = useCase(
            sourcePackage = smsPackage,
            title = sender,
            text = promoText
        )
        assertTrue(
            "Dr. Rao must be dropped downstream by parser",
            parseResult is NotificationParseResult.Dropped
        )
    }

    @Test
    fun testAcTokenWideningPassesGateButDroppedDownstream() {
        // W4: ac token allows non-transaction message with 'ac' through gate, but parser safely drops it downstream
        val acPromoText = "Ac service discount: get 20% off on your split ac repair. Book today on DemoApp."
        val sender = "HDFCBK"

        val passesGate = NotificationPreFilter.shouldReadFullText(smsPackage, sender, acPromoText)
        assertTrue("ac token allows message through keyword gate", passesGate)

        val parseResult = useCase(
            sourcePackage = smsPackage,
            title = sender,
            text = acPromoText
        )
        assertTrue(
            "Ac promo message must be dropped downstream by parser",
            parseResult is NotificationParseResult.Dropped
        )
    }
}
