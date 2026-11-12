package com.financeautomation.service

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.data.local.dao.HandledNotificationDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.HandledNotificationEntity
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProcessorTest {

    private val processor = NotificationProcessor(
        pendingCaptureDao = null,
        handledNotificationDao = null,
        accountDao = null,
        preferenceDao = null,
        ownPackageName = "com.financeautomation"
    )

    private class FakePendingCaptureDao : PendingCaptureDao {
        val captures = mutableMapOf<String, PendingCaptureEntity>()
        override suspend fun insert(capture: PendingCaptureEntity) { captures[capture.id] = capture }
        override suspend fun update(capture: PendingCaptureEntity) { captures[capture.id] = capture }
        override suspend fun getById(id: String): PendingCaptureEntity? = captures[id]
        override suspend fun getByNotificationKey(key: String): PendingCaptureEntity? = captures.values.lastOrNull { it.notificationKey == key }
        override suspend fun getByState(state: String): List<PendingCaptureEntity> = captures.values.filter { it.state == state }
        override fun getWaitingCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(captures.values.filter { it.state == "WAITING" })
        override suspend fun getWaitingCaptures(): List<PendingCaptureEntity> = captures.values.filter { it.state == "WAITING" }
        override suspend fun getExpiredWaitingCaptures(cutoffTime: Long): List<PendingCaptureEntity> =
            captures.values.filter { it.state == "WAITING" && it.timestamp < cutoffTime }
        override suspend fun getAwaitingSuccessCaptures(): List<PendingCaptureEntity> = captures.values.filter { it.state == "AWAITING_SUCCESS" }
        override suspend fun findSimilarByAmountAndTime(amount: Double, minTime: Long, maxTime: Long): List<PendingCaptureEntity> =
            captures.values.filter { it.amount == amount && it.timestamp in minTime..maxTime }
        override suspend fun updateState(id: String, state: String, updatedAt: Long) {
            captures[id]?.let { captures[id] = it.copy(state = state, updatedAt = updatedAt) }
        }
        override suspend fun resetShownToWaiting(updatedAt: Long): Int = 0
        override suspend fun deleteById(id: String) { captures.remove(id) }
        override suspend fun pruneOldCompletedCaptures(cutoffTime: Long) {}
    }

    private class FakeHandledNotificationDao : HandledNotificationDao {
        val handled = mutableMapOf<String, HandledNotificationEntity>()
        override suspend fun insert(handled: HandledNotificationEntity) { this.handled[handled.key] = handled }
        override suspend fun get(key: String, postTime: Long): HandledNotificationEntity? = handled[key]?.takeIf { it.postTime == postTime }
        override suspend fun getLatestByKey(key: String): HandledNotificationEntity? = handled[key]
        override suspend fun pruneOlderThan(cutoffTime: Long) { handled.entries.removeIf { it.value.handledAt < cutoffTime } }
    }

    private class FakeTestTransactionDao : TransactionDao {
        val txns = mutableMapOf<String, TransactionEntity>()
        override fun getTransactionsFlow(): Flow<List<TransactionEntity>> = flowOf(txns.values.toList())
        override fun getNeedsReviewFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override fun getConfirmedUnsyncedFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override suspend fun getById(id: String): TransactionEntity? = txns[id]
        override suspend fun getBySheetRowIndex(rowIndex: Int): TransactionEntity? = txns.values.firstOrNull { it.sheetRowIndex == rowIndex }
        override suspend fun getAllBySheetRowIndex(rowIndex: Int): List<TransactionEntity> = txns.values.filter { it.sheetRowIndex == rowIndex }
        override suspend fun getRecentSimilar(minTimestamp: Long, maxTimestamp: Long, minAmount: Double, maxAmount: Double): List<TransactionEntity> = emptyList()
        override suspend fun insert(transaction: TransactionEntity) { txns[transaction.id] = transaction }
        override suspend fun insertAll(transactions: List<TransactionEntity>) { transactions.forEach { insert(it) } }
        override suspend fun update(transaction: TransactionEntity) { txns[transaction.id] = transaction }
        override suspend fun delete(transaction: TransactionEntity) { txns.remove(transaction.id) }
        override suspend fun deleteById(id: String) { txns.remove(id) }
        override suspend fun voidTransaction(id: String, voidTitle: String) {}
        override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {}
        override suspend fun updateReviewState(id: String, reviewState: String, category: String?) {}
    }

    @Test
    fun process_selfNotification_dropped() = runBlocking {
        val result = processor.process(
            sourcePackage = "com.financeautomation",
            title = "Finance Automation",
            text = "Self alert"
        )
        assertTrue(result is NotificationProcessingOutcome.Ignored)
        assertEquals("Self notification dropped", (result as NotificationProcessingOutcome.Ignored).reason)
    }

    @Test
    fun process_blacklistedPackage_dropped() = runBlocking {
        val result = processor.process(
            sourcePackage = "com.whatsapp",
            title = "Friend",
            text = "Paid you 500"
        )
        assertTrue(result is NotificationProcessingOutcome.Ignored)
        assertTrue((result as NotificationProcessingOutcome.Ignored).reason.contains("Blacklisted privacy package"))
    }

    @Test
    fun process_nonWhitelistedPackage_dropped() = runBlocking {
        val result = processor.process(
            sourcePackage = "com.random.game",
            title = "Game Alert",
            text = "Daily coins reward"
        )
        assertTrue(result is NotificationProcessingOutcome.Ignored)
        assertTrue((result as NotificationProcessingOutcome.Ignored).reason.contains("Non-whitelisted package"))
    }

    @Test
    fun process_otpAlert_suppressed() = runBlocking {
        val result = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Bank OTP",
            text = "Your one-time password OTP is 482910. Do not share this with anyone."
        )
        assertTrue(result is NotificationProcessingOutcome.Ignored)
        assertEquals("OTP or auth alert suppressed", (result as NotificationProcessingOutcome.Ignored).reason)
    }

    @Test
    fun testWaitingCapturesExpireAfter24Hours_toNeedsReview() = runBlocking {
        val pDao = FakePendingCaptureDao()
        val hDao = FakeHandledNotificationDao()
        val tDao = FakeTestTransactionDao()

        val proc = NotificationProcessor(
            pendingCaptureDao = pDao,
            handledNotificationDao = hDao,
            transactionDao = tDao
        )

        val now = 100_000_000L
        val staleCapture = PendingCaptureEntity(
            id = "cap_stale_1",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 350.0,
            payee = "Swiggy",
            inferredAccount = "Cash",
            timestamp = now - (25 * 3600_000L), // 25 hours old
            kind = "DEBIT",
            flags = "",
            state = "WAITING",
            notificationKey = "key_stale",
            contentHash = "hash1",
            updatedAt = now - (25 * 3600_000L)
        )
        pDao.insert(staleCapture)

        proc.expireStaleWaitingCaptures(now = now)

        // Capture state must become DONE
        val capAfter = pDao.getById("cap_stale_1")
        assertNotNull(capAfter)
        assertEquals(PendingCaptureEntity.STATE_DONE, capAfter!!.state)

        // Transaction must be inserted into local DB in NEEDS_REVIEW
        assertEquals(1, tDao.txns.size)
        val txn = tDao.txns.values.first()
        assertEquals(350.0, txn.totalAmount, 0.001)
        assertEquals("Swiggy", txn.merchant)
        assertEquals(CategoryConstants.CATEGORY_OTHER, txn.category)
        assertEquals(ReviewState.NEEDS_REVIEW.name, txn.reviewState)
    }

    @Test
    fun testExpiredCaptureWithNullOrZeroAmount_marksNeedsReviewAndCreatesNoTransaction() = runBlocking {
        val pDao = FakePendingCaptureDao()
        val hDao = FakeHandledNotificationDao()
        val tDao = FakeTestTransactionDao()

        val proc = NotificationProcessor(
            pendingCaptureDao = pDao,
            handledNotificationDao = hDao,
            transactionDao = tDao
        )

        val now = 100_000_000L
        val staleCapture = PendingCaptureEntity(
            id = "cap_null_amt",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = null,
            payee = "Unknown Payee",
            inferredAccount = "Cash",
            timestamp = now - (25 * 3600_000L),
            kind = "DEBIT",
            flags = PendingCaptureEntity.FLAG_NEEDS_AMOUNT,
            state = "WAITING",
            notificationKey = "key_null",
            contentHash = "hash_null",
            updatedAt = now - (25 * 3600_000L)
        )
        pDao.insert(staleCapture)

        proc.expireStaleWaitingCaptures(now = now)

        // Capture state must become NEEDS_REVIEW, NOT DONE
        val capAfter = pDao.getById("cap_null_amt")
        assertNotNull(capAfter)
        assertEquals(PendingCaptureEntity.STATE_NEEDS_REVIEW, capAfter!!.state)

        // Zero rupee transaction must NOT be inserted into ledger
        assertEquals(0, tDao.txns.size)
    }

    @Test
    fun testExpiredCreditCapture_marksNeedsReviewAndCreatesNoTransaction() = runBlocking {
        val pDao = FakePendingCaptureDao()
        val hDao = FakeHandledNotificationDao()
        val tDao = FakeTestTransactionDao()

        val proc = NotificationProcessor(
            pendingCaptureDao = pDao,
            handledNotificationDao = hDao,
            transactionDao = tDao
        )

        val now = 100_000_000L
        val staleCredit = PendingCaptureEntity(
            id = "cap_credit_1",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 1200.0,
            payee = "Friend",
            inferredAccount = "Cash",
            timestamp = now - (25 * 3600_000L),
            kind = PendingCaptureEntity.KIND_CREDIT,
            flags = "",
            state = "WAITING",
            notificationKey = "key_credit",
            contentHash = "hash_credit",
            updatedAt = now - (25 * 3600_000L)
        )
        pDao.insert(staleCredit)

        proc.expireStaleWaitingCaptures(now = now)

        // Credit capture must become NEEDS_REVIEW and must NEVER become a debit expense
        val capAfter = pDao.getById("cap_credit_1")
        assertNotNull(capAfter)
        assertEquals(PendingCaptureEntity.STATE_NEEDS_REVIEW, capAfter!!.state)

        // Must NOT create a debit transaction
        assertEquals(0, tDao.txns.size)
    }

    @Test
    fun testAtomicWrite_insertsBothHandledNotificationAndPendingCapture() = runBlocking {
        val pDao = FakePendingCaptureDao()
        val hDao = FakeHandledNotificationDao()
        var transactionBlockRan = false

        val proc = NotificationProcessor(
            pendingCaptureDao = pDao,
            handledNotificationDao = hDao,
            runInTransaction = { block ->
                transactionBlockRan = true
                block()
            }
        )

        val outcome = proc.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Paid to Starbucks",
            text = "Paid Rs 250 to Starbucks",
            postTime = 1700000000000L,
            sbnKey = "starbucks_key_1"
        )

        assertTrue(outcome is NotificationProcessingOutcome.Captured)
        assertTrue(transactionBlockRan)
        assertEquals(1, pDao.captures.size)
        assertEquals(1, hDao.handled.size)
    }
}


