package com.financeautomation.domain

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.RegexPatterns
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.usecase.DropReason
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.service.NotificationPreFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun testReplayInventedSmsShapesEndToEndEveryMessage() {
        val stream = javaClass.classLoader?.getResourceAsStream("invented_sms_shapes.json")
            ?: error("invented_sms_shapes.json resource not found in test resources")
        val jsonText = InputStreamReader(stream).use { it.readText() }
        val rootObj = Json.parseToJsonElement(jsonText).jsonObject
        val messages = rootObj["messages"]?.jsonArray ?: error("Missing messages array")

        assertEquals("Total fixtures in test set must be 34", 34, messages.size)

        var debitCount = 0
        var creditCount = 0
        var refundReversalCount = 0
        var mandateNoticeDropCount = 0
        var paymentRequestDropCount = 0
        var balanceHoldDropCount = 0
        var failureAlertCount = 0
        var otpDropCount = 0
        var promoDropCount = 0
        var unknownDirectionDropCount = 0
        var gateDropCount = 0

        for (elem in messages) {
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
            val sender = obj["sender"]?.jsonPrimitive?.content.orEmpty()
            val expected = obj["expected"]?.jsonPrimitive?.content.orEmpty()
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
            val category = obj["category"]?.jsonPrimitive?.content.orEmpty()

            val passesGate = NotificationPreFilter.shouldReadFullText(smsPackage, sender, text)

            when (id) {
                // Real Debits
                "I01", "I02", "I03", "I05", "I06", "I09", "I12", "I13", "I29", "I33", "I34" -> {
                    assertTrue("Fixture $id ($category) must pass gate", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture $id ($category) must parse as Success, got $parseResult", parseResult is NotificationParseResult.Success)
                    val success = parseResult as NotificationParseResult.Success
                    assertTrue("Fixture $id must be debit", success.isDebit)
                    assertFalse("Fixture $id must not be credit", success.isCredit)
                    val expectedAmt = obj["amount"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    if (expectedAmt != null) {
                        assertEquals("Fixture $id amount must match", expectedAmt, success.transaction.totalAmount, 0.001)
                    }
                    debitCount++
                }

                // Real Credits
                "I04", "I07", "I08", "I10", "I11" -> {
                    assertTrue("Fixture $id ($category) must pass gate", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture $id ($category) must parse as Success, got $parseResult", parseResult is NotificationParseResult.Success)
                    val success = parseResult as NotificationParseResult.Success
                    assertTrue("Fixture $id must be credit", success.isCredit)
                    assertFalse("Fixture $id must not be debit", success.isDebit)
                    val expectedAmt = obj["amount"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    if (expectedAmt != null) {
                        assertEquals("Fixture $id amount must match", expectedAmt, success.transaction.totalAmount, 0.001)
                    }
                    creditCount++
                }

                // Bank Reversal Credit -> Refund in Needs Review
                "I24" -> {
                    assertTrue("Fixture $id ($category) must pass gate", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture $id must parse as Success, got $parseResult", parseResult is NotificationParseResult.Success)
                    val success = parseResult as NotificationParseResult.Success
                    assertTrue("Fixture $id must be credit", success.isCredit)
                    assertEquals("Fixture $id category must be Refund", "Refund", success.transaction.category)
                    assertTrue("Fixture $id flags must contain REFUND_REVERSAL", success.flags.contains("REFUND_REVERSAL"))
                    assertEquals("Fixture $id amount", 250.00, success.transaction.totalAmount, 0.001)
                    refundReversalCount++
                }

                // Mandate / Autopay notices
                "I14" -> {
                    // I14 lacks financial keywords so it drops at gate
                    assertFalse("Fixture I14 lacks financial keywords and drops at gate", passesGate)
                    gateDropCount++
                }
                "I15", "I16", "I17" -> {
                    assertTrue("Fixture $id ($category) passes gate because of financial keywords", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture $id must drop downstream as MANDATE_NOTICE, got $parseResult",
                        parseResult is NotificationParseResult.Dropped && parseResult.reason == DropReason.MANDATE_NOTICE)
                    mandateNoticeDropCount++
                }
                "I18" -> {
                    // I18 lacks financial keywords so it drops at gate
                    assertFalse("Fixture I18 lacks financial keywords and drops at gate", passesGate)
                    gateDropCount++
                }

                // Payment Requests
                "I19", "I20" -> {
                    assertTrue("Fixture $id ($category) passes gate", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture $id must drop downstream as PAYMENT_REQUEST, got $parseResult",
                        parseResult is NotificationParseResult.Dropped && parseResult.reason == DropReason.PAYMENT_REQUEST)
                    paymentRequestDropCount++
                }

                // Balance Holds
                "I21", "I22" -> {
                    assertTrue("Fixture $id ($category) passes gate", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture $id must drop downstream as BALANCE_HOLD, got $parseResult",
                        parseResult is NotificationParseResult.Dropped && parseResult.reason == DropReason.BALANCE_HOLD)
                    balanceHoldDropCount++
                }

                // Failed / Wrong PIN
                "I23" -> {
                    assertTrue("Fixture $id ($category) passes gate", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture $id must return FailureAlert, got $parseResult", parseResult is NotificationParseResult.FailureAlert)
                    failureAlertCount++
                }

                // OTP
                "I25" -> {
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture I25 must drop as OTP_MESSAGE",
                        parseResult is NotificationParseResult.Dropped && parseResult.reason == DropReason.OTP_MESSAGE)
                    otpDropCount++
                }

                // Promotional / Non-financial
                "I26" -> {
                    assertFalse("Fixture I26 has no financial keywords and drops at gate", passesGate)
                    gateDropCount++
                }

                // Balance only (no direction)
                "I27" -> {
                    assertTrue("Fixture I27 passes gate", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture I27 must drop as UNKNOWN_DIRECTION, got $parseResult",
                        parseResult is NotificationParseResult.Dropped && parseResult.reason == DropReason.UNKNOWN_DIRECTION)
                    unknownDirectionDropCount++
                }

                // Bill Due Statement
                "I28" -> {
                    assertTrue("Fixture I28 passes gate", passesGate)
                    val parseResult = useCase(smsPackage, sender, text)
                    assertTrue("Fixture I28 must drop as PROMOTIONAL, got $parseResult",
                        parseResult is NotificationParseResult.Dropped && parseResult.reason == DropReason.PROMOTIONAL)
                    promoDropCount++
                }

                // Order info
                "I30" -> {
                    assertFalse("Fixture I30 drops at gate", passesGate)
                    gateDropCount++
                }

                // Non-bank senders
                "I31", "I32" -> {
                    assertFalse("Fixture $id drops at gate due to non-bank sender", passesGate)
                    gateDropCount++
                }

                else -> error("Unhandled message fixture id: $id")
            }
        }

        // Verify exact counts across all 34 messages
        assertEquals("Debit fixtures count", 11, debitCount)
        assertEquals("Credit fixtures count", 5, creditCount)
        assertEquals("Refund reversal count", 1, refundReversalCount)
        assertEquals("Mandate notice downstream drops", 3, mandateNoticeDropCount)
        assertEquals("Payment request downstream drops", 2, paymentRequestDropCount)
        assertEquals("Balance hold downstream drops", 2, balanceHoldDropCount)
        assertEquals("Failure alert count", 1, failureAlertCount)
        assertEquals("OTP drops", 1, otpDropCount)
        assertEquals("Promo drops", 1, promoDropCount)
        assertEquals("Unknown direction drops", 1, unknownDirectionDropCount)
        assertEquals("Gate drops count", 6, gateDropCount)

        val totalVerified = debitCount + creditCount + refundReversalCount + mandateNoticeDropCount +
            paymentRequestDropCount + balanceHoldDropCount + failureAlertCount + otpDropCount +
            promoDropCount + unknownDirectionDropCount + gateDropCount
        assertEquals("Every fixture of the 34 messages must be verified", 34, totalVerified)
    }

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

    @Test
    fun testExecutedRechargeWithPromotionalTextIsCapturedAsDebit() {
        // X1: F074 shape — executed recharge with amount must not be dropped as promotional
        val rechargeText = "Congratulations! With your recharge of Rs.979.0, you can now enjoy FREE access to 20 OTTs such as Demo OTT on Demo App."
        val sender = "ARTLTV-T"

        val passesGate = NotificationPreFilter.shouldReadFullText(smsPackage, sender, rechargeText)
        assertTrue("Executed recharge message must pass keyword gate", passesGate)

        val parseResult = useCase(
            sourcePackage = smsPackage,
            title = sender,
            text = rechargeText
        )
        assertTrue("Executed recharge must parse as Success, got $parseResult", parseResult is NotificationParseResult.Success)
        val success = parseResult as NotificationParseResult.Success
        assertTrue("Must be classified as debit", success.isDebit)
        assertFalse("Must not be credit", success.isCredit)
        assertEquals("Amount must match 979.0", 979.0, success.transaction.totalAmount, 0.001)
    }

    @Test
    fun testReversalCreditUsesCategoryRefundAndInitializesNeedsReview() {
        // X5: bank reversal credit uses CategoryConstants.CATEGORY_REFUND and FLAG_REFUND_REVERSAL
        val reversalText = "An amount of INR 250.00 has been REVERSED to your account XXX234 on 13/03/26. Total Avail.bal INR 4,350.00 - Canara Bank"
        val sender = "CANBNK-S"

        val parseResult = useCase(
            sourcePackage = smsPackage,
            title = sender,
            text = reversalText
        )
        assertTrue("Reversal credit must parse as Success", parseResult is NotificationParseResult.Success)
        val success = parseResult as NotificationParseResult.Success
        assertTrue("Must be credit", success.isCredit)
        assertEquals("Category must be CategoryConstants.CATEGORY_REFUND", CategoryConstants.CATEGORY_REFUND, success.transaction.category)
        assertTrue("Flags must contain FLAG_REFUND_REVERSAL", success.flags.contains(PendingCaptureEntity.FLAG_REFUND_REVERSAL))
    }

    @Test
    fun testExecutedBillAndRechargePaymentsCapturedAsDebitsInNeedsReviewAndRemindersDropped() {
        // 1. Executed recharge in English
        val engText = "Recharge Successful !\nPlan Name : 899.00\nJio Number : 9876543210\nTransaction ID : BR123456"
        assertTrue("English recharge passes gate", NotificationPreFilter.shouldReadFullText(smsPackage, "JioPay-S", engText))
        val engResult = useCase(smsPackage, "JioPay-S", engText)
        assertTrue("English recharge parses as Success", engResult is NotificationParseResult.Success)
        val engSuccess = engResult as NotificationParseResult.Success
        assertTrue("Must be debit", engSuccess.isDebit)
        assertEquals("Amount must be 899.0", 899.0, engSuccess.transaction.totalAmount, 0.001)
        assertEquals("Payee must be biller Jio", "Jio", engSuccess.transaction.merchant)

        // 2. Executed recharge in Hindi
        val hiText = "रिचार्ज सफल रहा!\nप्लान का नाम: 899.00 \nJio नंबर : 9876543210\nट्रांज़ैक्शन आईडी : BR123456"
        assertTrue("Hindi recharge passes gate", NotificationPreFilter.shouldReadFullText(smsPackage, "JioPay-S", hiText))
        val hiResult = useCase(smsPackage, "JioPay-S", hiText)
        assertTrue("Hindi recharge parses as Success", hiResult is NotificationParseResult.Success)
        val hiSuccess = hiResult as NotificationParseResult.Success
        assertTrue("Must be debit", hiSuccess.isDebit)
        assertEquals("Amount must be 899.0", 899.0, hiSuccess.transaction.totalAmount, 0.001)
        assertEquals("Payee must be biller Jio", "Jio", hiSuccess.transaction.merchant)

        // 3. Executed recharge in Marathi
        val mrText = "रिचार्ज यशस्वी! \nप्लॅन चे नाव : 899.00 \nJio नंबर : 9876543210\nट्रान्झॅक्शन आयडी : BR123456"
        assertTrue("Marathi recharge passes gate", NotificationPreFilter.shouldReadFullText(smsPackage, "JioPay-S", mrText))
        val mrResult = useCase(smsPackage, "JioPay-S", mrText)
        assertTrue("Marathi recharge parses as Success", mrResult is NotificationParseResult.Success)
        val mrSuccess = mrResult as NotificationParseResult.Success
        assertTrue("Must be debit", mrSuccess.isDebit)
        assertEquals("Amount must be 899.0", 899.0, mrSuccess.transaction.totalAmount, 0.001)
        assertEquals("Payee must be biller Jio", "Jio", mrSuccess.transaction.merchant)

        // 4. Executed bill payment with explicit biller
        val billText = "Bill payment of Rs. 1,450.00 received towards Bescom electricity bill. Txn ID: 98765"
        assertTrue("Executed bill payment passes gate", NotificationPreFilter.shouldReadFullText(smsPackage, "BESCOM-S", billText))
        val billResult = useCase(smsPackage, "BESCOM-S", billText)
        assertTrue("Bill payment parses as Success", billResult is NotificationParseResult.Success)
        val billSuccess = billResult as NotificationParseResult.Success
        assertTrue("Must be debit", billSuccess.isDebit)
        assertEquals("Amount must be 1450.0", 1450.0, billSuccess.transaction.totalAmount, 0.001)
        assertEquals("Payee must be Bescom", "Bescom", billSuccess.transaction.merchant)

        // 5. Expiring plan reminder without transaction (negative)
        val expiryReminderText = "वर्तमान रिचार्ज प्लान 20-Mar-26 18:17 Hrs को समाप्त हो रहा है"
        val expiryResult = useCase(smsPackage, "JIOPAY-S", expiryReminderText)
        assertTrue("Plan expiry reminder must be dropped downstream as UNKNOWN_DIRECTION or PROMOTIONAL",
            expiryResult is NotificationParseResult.Dropped &&
                (expiryResult.reason == DropReason.UNKNOWN_DIRECTION || expiryResult.reason == DropReason.PROMOTIONAL))

        // 6. Bill due notice without payment confirmation (negative)
        val billDueText = "Dear customer, your electricity bill of Rs. 1450 is due on 25-Mar-2026. Pay now to avoid disconnection."
        val billDueResult = useCase(smsPackage, "BESCOM-S", billDueText)
        assertTrue("Bill due notice without payment confirmation dropped",
            billDueResult is NotificationParseResult.Dropped)
    }
}
