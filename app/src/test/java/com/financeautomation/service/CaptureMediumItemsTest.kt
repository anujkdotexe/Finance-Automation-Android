package com.financeautomation.service

import com.financeautomation.core.utils.RegexPatterns
import com.financeautomation.data.local.dao.HandledNotificationDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.entity.HandledNotificationEntity
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class CaptureMediumItemsTest {

    @Test
    fun amountExtractionRejectsAccountBalanceAndDoesNotPickBalanceAsFirstMatch() {
        // SMS with both transaction amount and account balance
        val sms1 = "A/c *1234 debited by Rs 450.00 on 12-May. Avail Bal: Rs 15,230.50"
        val amount1 = RegexPatterns.extractAmount(sms1)
        assertEquals(450.0, amount1!!, 0.001)

        // Text where balance appears first, but payment is after
        val sms2 = "Limit Rs 50,000. Paid Rs 2,500 at Store."
        val amount2 = RegexPatterns.extractAmount(sms2)
        assertEquals(2500.0, amount2!!, 0.001)

        // Text where amount is labelled with bal after it
        val sms3 = "Rs 15,000 is your Avail Bal"
        val amount3 = RegexPatterns.extractAmount(sms3)
        // Should NOT pick 15,000 as a payment amount
        assertTrue("Balance amount followed by 'is your Avail Bal' must not be extracted as payment", amount3 == null)
    }

    @Test
    fun merchantExtractionExtractsFromRahulAndRejectsUserUpiAddress() {
        // 1. "from Rahul" -> payee should be "Rahul", not "Unknown Payee"
        val text1 = "Money received Rs 500 from Rahul"
        val merchant1 = RegexPatterns.extractMerchant(text1)
        assertEquals("Rahul", merchant1)

        val text2 = "Received Rs. 1,000.00 from Priya Sharma on 10-Nov"
        val merchant2 = RegexPatterns.extractMerchant(text2)
        assertEquals("Priya Sharma", merchant2)

        // 2. User's own UPI address in "using" or "into" must not be returned as merchant
        val text3 = "Paid Rs 349 to Swiggy using user@okhdfcbank on 15-Nov"
        val merchant3 = RegexPatterns.extractMerchant(text3)
        assertEquals("Swiggy", merchant3)
        assertNotEquals("user@okhdfcbank", merchant3)
    }

    @Test
    fun replayedActiveNotificationOnListenerReconnectIsDroppedAsDuplicate() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao
        )

        // 1. Process notification for the first time
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹250 to Fresh Fruits",
            sbnKey = "0|com.google.android.apps.nbu.paisa.user|101|null|10001",
            postTime = 1713600000000L
        )
        assertTrue("First alert should be captured", r1 is NotificationProcessingOutcome.Captured)

        // 2. Listener reconnects and replays active notification from shade
        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹250 to Fresh Fruits",
            sbnKey = "0|com.google.android.apps.nbu.paisa.user|101|null|10001",
            postTime = 1713600000000L
        )
        assertTrue("Replayed alert must be dropped as duplicate, got: $r2", r2 is NotificationProcessingOutcome.Ignored)
        assertEquals("Only one capture should exist in DB", 1, captureDao.captures.size)
    }

    @Test
    fun reversalCreditThroughNotificationProcessorLandsInNeedsReviewWithFlagAndCreditKind() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao
        )

        val outcome = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 250.00 has been REVERSED to your account XXX234 on 13/03/26. Total Avail.bal INR 4,350.00 - Canara Bank",
            sbnKey = "0|sms|201",
            postTime = 1713600000000L
        )

        assertTrue("Reversal credit must be captured", outcome is NotificationProcessingOutcome.Captured)
        val capture = (outcome as NotificationProcessingOutcome.Captured).capture
        assertEquals("Must land in STATE_NEEDS_REVIEW", PendingCaptureEntity.STATE_NEEDS_REVIEW, capture.state)
        assertEquals("Kind must be KIND_CREDIT (inflow)", PendingCaptureEntity.KIND_CREDIT, capture.kind)
        assertTrue("Flags must contain FLAG_REFUND_REVERSAL", capture.flags.contains(PendingCaptureEntity.FLAG_REFUND_REVERSAL))
        assertEquals("Amount must match", 250.0, capture.amount!!, 0.001)
    }

    @Test
    fun sameAppSmsPairWithin180sWithCompatiblePayeesMergesAndEnrichesPayee() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao
        )

        val t0 = 1713600000000L
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 450.00 has been DEBITED to your account XXX1824 on 15/03/2026. Total Avail.bal INR 4,100.00.Dial 1930 to report cyber fraud - Canara Bank",
            sbnKey = "0|sms|101",
            postTime = t0
        )
        assertTrue("Generic debit should be captured", r1 is NotificationProcessingOutcome.Captured)
        assertEquals(1, captureDao.captures.size)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 450.00 to Swiggy on 15/03/2026 from A/c *1824. Ref 123456789012 - Canara Bank",
            sbnKey = "0|sms|102",
            postTime = t0 + 60_000L
        )
        assertTrue("Second SMS within 180s with compatible payee must merge", r2 is NotificationProcessingOutcome.Merged)
        assertEquals("Exactly one row must exist in DB after merge", 1, captureDao.captures.size)

        val merged = captureDao.captures.values.first()
        assertEquals("Enriched payee must be Swiggy", "Swiggy", merged.payee)
        assertEquals(450.0, merged.amount!!, 0.001)
    }

    @Test
    fun sameAppSmsPairWithin180sWithIncompatiblePayeesDoNotMerge() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao
        )

        val t0 = 1713600000000L
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 500.00 to Alpha Tech on 15/03/2026 from A/c *1824. Ref 111111 - Canara Bank",
            sbnKey = "0|sms|201",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 500.00 to Beta Bakery on 15/03/2026 from A/c *1824. Ref 222222 - Canara Bank",
            sbnKey = "0|sms|202",
            postTime = t0 + 60_000L
        )
        assertTrue("Different payees of same amount must NOT merge", r2 is NotificationProcessingOutcome.Captured)
        assertEquals("Two distinct rows must exist in DB", 2, captureDao.captures.size)
    }

    @Test
    fun sameAppSmsPairExceeding180sWindowDoNotMerge() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao
        )

        val t0 = 1713600000000L
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 500.00 to Store Alpha on 15/03/2026 from A/c *1824. Ref 111111 - Canara Bank",
            sbnKey = "0|sms|301",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 500.00 to Store Alpha on 15/03/2026 from A/c *1824. Ref 333333 - Canara Bank",
            sbnKey = "0|sms|302",
            postTime = t0 + 240_000L // 4 minutes later (> 180s)
        )
        assertTrue("Payment after 4 minutes must NOT merge", r2 is NotificationProcessingOutcome.Captured)
        assertEquals("Two distinct rows must exist in DB", 2, captureDao.captures.size)
    }

    @Test
    fun z2RechargeLanguageTripletFromSameBillerCollapsesIntoSingleCapture() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao
        )

        val t0 = 1713600000000L
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "JioPay-S",
            text = "Recharge successful of Rs 899.00 for Jio number 9876543210. Plan Name: 899. Ref: JIO111.",
            sbnKey = "0|sms|401",
            postTime = t0
        )
        assertTrue("English recharge alert should be captured", r1 is NotificationProcessingOutcome.Captured)
        assertEquals(1, captureDao.captures.size)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "JioPay-S",
            text = "जियो नंबर 9876543210 के लिए रु 899.00 का रिचार्ज सफल रहा। प्लान का नाम: 899. Ref: JIO222.",
            sbnKey = "0|sms|402",
            postTime = t0 + 5_000L
        )
        assertTrue("Hindi recharge alert must merge", r2 is NotificationProcessingOutcome.Merged)
        assertEquals(1, captureDao.captures.size)

        val r3 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "JioPay-S",
            text = "जिओ नंबर 9876543210 साठी रु 899.00 चा रिचार्ज यशस्वी झाला. प्लॅन चे नाव: 899. Ref: JIO333.",
            sbnKey = "0|sms|403",
            postTime = t0 + 10_000L
        )
        assertTrue("Marathi recharge alert must merge", r3 is NotificationProcessingOutcome.Merged)
        assertEquals("All 3 language alerts must collapse into exactly 1 row", 1, captureDao.captures.size)

        val capture = captureDao.captures.values.first()
        assertEquals(899.0, capture.amount!!, 0.001)
        assertEquals("Jio", capture.payee)
    }

    @Test
    fun exactDuplicateTextRepeatedWithin24hDroppedAsContentHashDuplicate() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao
        )

        val t0 = 1713600000000L
        val text = "Paid ₹250 to Fresh Fruits"
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = text,
            sbnKey = "0|gpay|501",
            postTime = t0
        )
        assertTrue("First alert should be captured", r1 is NotificationProcessingOutcome.Captured)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = text,
            sbnKey = "0|gpay|502",
            postTime = t0 + 3600_000L // 1 hour later
        )
        assertTrue("Exact duplicate text within 24h must be dropped", r2 is NotificationProcessingOutcome.Ignored)
        assertEquals("Only one capture should exist in DB", 1, captureDao.captures.size)
    }

    private class FakeTestHandledDao : HandledNotificationDao {
        val handled = ConcurrentHashMap<String, HandledNotificationEntity>()
        override suspend fun insert(handled: HandledNotificationEntity) {
            this.handled["${handled.key}|${handled.postTime}"] = handled
        }
        override suspend fun get(key: String, postTime: Long): HandledNotificationEntity? =
            handled["$key|$postTime"]
        override suspend fun getLatestByKey(key: String): HandledNotificationEntity? =
            handled.values.filter { it.key == key }.maxByOrNull { it.postTime }
        override suspend fun getByContentHash(hash: String, since: Long): HandledNotificationEntity? =
            handled.values.firstOrNull { it.contentHash == hash && it.handledAt >= since }
        override suspend fun pruneOlderThan(cutoffTime: Long) {
            handled.entries.removeIf { it.value.handledAt < cutoffTime }
        }
    }

    private class FakeTestCaptureDao : PendingCaptureDao {
        val captures = ConcurrentHashMap<String, PendingCaptureEntity>()
        override fun getWaitingCapturesFlow(): Flow<List<PendingCaptureEntity>> = emptyFlow()
        override fun getNeedsReviewCapturesFlow(): Flow<List<PendingCaptureEntity>> = emptyFlow()
        override fun getNewAccountCapturesFlow(): Flow<List<PendingCaptureEntity>> = emptyFlow()
        override suspend fun insert(capture: PendingCaptureEntity) { captures[capture.id] = capture }
        override suspend fun update(capture: PendingCaptureEntity) { captures[capture.id] = capture }
        override suspend fun getById(id: String): PendingCaptureEntity? = captures[id]
        override suspend fun getByNotificationKey(key: String): PendingCaptureEntity? =
            captures.values.firstOrNull { it.notificationKey == key }
        override suspend fun getByContentHash(hash: String, since: Long): PendingCaptureEntity? =
            captures.values.firstOrNull { it.contentHash == hash && it.timestamp >= since }
        override suspend fun getByState(state: String): List<PendingCaptureEntity> =
            captures.values.filter { it.state == state }
        override suspend fun getWaitingCaptures(): List<PendingCaptureEntity> =
            captures.values.filter { it.state == "WAITING" }
        override suspend fun getExpiredWaitingCaptures(cutoffTime: Long): List<PendingCaptureEntity> = emptyList()
        override suspend fun getAwaitingSuccessCaptures(): List<PendingCaptureEntity> = emptyList()
        override suspend fun findSimilarByAmountAndTime(amount: Double, minTime: Long, maxTime: Long): List<PendingCaptureEntity> =
            captures.values.filter { it.amount == amount && it.timestamp in minTime..maxTime }
        override suspend fun updateState(id: String, state: String, updatedAt: Long) {
            captures[id]?.let { captures[id] = it.copy(state = state, updatedAt = updatedAt) }
        }
        override suspend fun resetShownToWaiting(updatedAt: Long): Int = 0
        override suspend fun deleteById(id: String) { captures.remove(id) }
        override suspend fun pruneOldCompletedCaptures(cutoffTime: Long) {}
    }
}
