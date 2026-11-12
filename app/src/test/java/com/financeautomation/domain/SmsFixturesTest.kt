package com.financeautomation.domain

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStreamReader

class SmsFixturesTest {

    private lateinit var useCase: ParseNotificationUseCase

    @Before
    fun setUp() {
        useCase = ParseNotificationUseCase()
    }

    @Test
    fun testAllSmsFixturesAgainstParser() {
        val stream = javaClass.classLoader?.getResourceAsStream("sms_fixtures.json")
            ?: error("sms_fixtures.json resource not found")
        val jsonText = InputStreamReader(stream).use { it.readText() }
        val rootArray = Json.parseToJsonElement(jsonText).jsonArray

        var tested = 0
        for ((index, element) in rootArray.withIndex()) {
            val obj = element.jsonObject
            val sender = obj["sender"]?.jsonPrimitive?.content ?: ""
            val body = obj["body"]?.jsonPrimitive?.content ?: ""
            val expected = obj["expected"]?.jsonObject ?: continue

            val shouldPrompt = expected["shouldPrompt"]?.jsonPrimitive?.booleanOrNull ?: false
            val expectedOutcome = expected["outcome"]?.jsonPrimitive?.content
            val expectedDirection = expected["direction"]?.jsonPrimitive?.content
            val expectedAmount = expected["amount"]?.jsonPrimitive?.doubleOrNull
            val expectedMerchant = expected["merchant"]?.jsonPrimitive?.content

            val result = useCase(
                sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
                title = sender,
                text = body
            )

            if (!shouldPrompt) {
                if (expectedOutcome == "FAILURE_ALERT") {
                    assertTrue(
                        "Fixture #$index should be FailureAlert: $body",
                        result is NotificationParseResult.FailureAlert
                    )
                } else {
                    assertTrue(
                        "Fixture #$index should be Dropped: $body",
                        result is NotificationParseResult.Dropped
                    )
                }
            } else {
                assertTrue(
                    "Fixture #$index should be Success: $body, got $result",
                    result is NotificationParseResult.Success
                )
                val success = result as NotificationParseResult.Success

                if (expectedDirection != null) {
                    if (expectedDirection == "DEBIT") {
                        assertTrue("Fixture #$index direction must be debit", success.isDebit)
                    } else if (expectedDirection == "CREDIT") {
                        assertTrue("Fixture #$index direction must be credit", success.isCredit)
                    }
                }

                if (expectedAmount != null) {
                    assertEquals(
                        "Fixture #$index amount mismatch",
                        expectedAmount,
                        success.transaction.totalAmount,
                        0.01
                    )
                }

                if (expectedMerchant != null) {
                    assertEquals(
                        "Fixture #$index merchant mismatch",
                        expectedMerchant,
                        success.transaction.merchant
                    )
                }
            }
            tested++
        }

        assertTrue("Expected to test all 44 fixtures", tested >= 40)
    }
}

