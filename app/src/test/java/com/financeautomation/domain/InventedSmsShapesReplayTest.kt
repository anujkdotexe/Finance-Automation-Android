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

    @Test
    fun testReplayInventedSmsShapesKeywordGate() {
        val stream = javaClass.classLoader?.getResourceAsStream("invented_sms_shapes.json")
            ?: error("invented_sms_shapes.json resource not found in test resources")
        val jsonText = InputStreamReader(stream).use { it.readText() }
        val rootObj = Json.parseToJsonElement(jsonText).jsonObject
        val messages = rootObj["messages"]?.jsonArray ?: error("Missing messages array")

        val realTxnExpected = setOf("DEBIT", "CREDIT", "REVIEW_REFUND")
        var realTxnCount = 0
        var droppedAtGateCount = 0

        for (elem in messages) {
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
            val sender = obj["sender"]?.jsonPrimitive?.content.orEmpty()
            val expected = obj["expected"]?.jsonPrimitive?.content.orEmpty()
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
            val category = obj["category"]?.jsonPrimitive?.content.orEmpty()

            if (expected in realTxnExpected) {
                realTxnCount++
                val passesGate = NotificationPreFilter.shouldReadFullText(smsPackage, sender, text)
                if (!passesGate) {
                    droppedAtGateCount++
                }
                assertTrue(
                    "Fixture $id ($category) must pass keyword gate: text='$text'",
                    passesGate
                )
            }
        }

        assertTrue("Must test real transactions", realTxnCount > 0)
        assertEquals("Zero real DEBIT or CREDIT fixtures dropped at KEYWORD_GATE", 0, droppedAtGateCount)
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
}
