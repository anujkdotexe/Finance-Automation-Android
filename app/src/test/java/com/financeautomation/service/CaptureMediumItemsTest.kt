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
