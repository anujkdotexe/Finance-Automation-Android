package com.financeautomation.service

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class NotificationListenerServiceTest {

    private lateinit var fakeRepository: FakeTransactionRepository
    private lateinit var processor: NotificationProcessor

    @Before
    fun setUp() {
        fakeRepository = FakeTransactionRepository()
        processor = NotificationProcessor(
            repository = fakeRepository,
            parseUseCase = ParseNotificationUseCase(ownPackageName = "com.financeautomation"),
            ownPackageName = "com.financeautomation"
        )
    }

    @Test
    fun testWhitelistedGooglePayDebitIsCreatedWithHeadsUpData() = runBlocking {
        val outcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Payment to Starbucks",
            text = "Paid Rs 350.00 to Starbucks using SBI. UPI Ref: 112233445566",
            postTime = 1730811600000L
        )

        assertTrue(outcome is NotificationProcessingOutcome.Created)
        val created = outcome as NotificationProcessingOutcome.Created
        assertEquals(350.00, created.transaction.totalAmount, 0.001)
        assertEquals("Starbucks", created.transaction.merchant)
        assertEquals(ReviewState.NEEDS_REVIEW, created.transaction.reviewState)
        assertEquals(SyncState.PENDING, created.transaction.syncState)
        assertFalse(created.isPossibleDuplicate)

        // Verify Heads-Up notification specification
        val headsUp = created.headsUpNotificationData
        assertTrue(headsUp.title.contains("350.00"))
        assertTrue(headsUp.title.contains("Starbucks"))
        assertEquals(CategoryConstants.CATEGORY_FOOD_AND_DRINK, headsUp.category1)
        assertEquals(CategoryConstants.CATEGORY_GROCERIES, headsUp.category2)
        assertEquals("Details / Split", headsUp.detailsActionTitle)
        assertEquals(created.transaction.id, headsUp.transactionId)

        // Verify persistence in repository
        val stored = fakeRepository.getTransactionById(created.transaction.id)
        assertNotNull(stored)
        assertEquals(350.00, stored?.totalAmount ?: 0.0, 0.001)
    }

    @Test
    fun testWhitelistedPhonePeDebitIsCreated() = runBlocking {
        val outcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_PHONEPE,
            title = "Payment Successful",
            text = "Debited INR 500 to Reliance Fresh. Txn Ref: 998877665544",
            postTime = 1730811600000L
        )

        assertTrue(outcome is NotificationProcessingOutcome.Created)
        val created = outcome as NotificationProcessingOutcome.Created
        assertEquals(500.00, created.transaction.totalAmount, 0.001)
        assertEquals("Reliance Fresh", created.transaction.merchant)
    }

    @Test
    fun testWhitelistedBankSmsAlertIsCreated() = runBlocking {
        val outcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "SBI Alert",
            text = "A/c 1234 debited for INR 1,200.00 on 05-Nov to DMart. Ref: 445566778899",
            postTime = 1730811600000L
        )

        assertTrue(outcome is NotificationProcessingOutcome.Created)
        val created = outcome as NotificationProcessingOutcome.Created
        assertEquals(1200.00, created.transaction.totalAmount, 0.001)
        assertEquals("DMart", created.transaction.merchant)
    }

    @Test
    fun testBlacklistedMessagingAppIsIgnored() = runBlocking {
        val whatsappOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_WHATSAPP,
            title = "Friend",
            text = "Paid Rs 500 for lunch"
        )
        assertTrue(whatsappOutcome is NotificationProcessingOutcome.Ignored)

        val telegramOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_TELEGRAM,
            title = "Group",
            text = "Paid Rs 1000 for dinner"
        )
        assertTrue(telegramOutcome is NotificationProcessingOutcome.Ignored)

        val signalOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_SIGNAL,
            title = "Contact",
            text = "Paid Rs 250"
        )
        assertTrue(signalOutcome is NotificationProcessingOutcome.Ignored)
    }

    @Test
    fun testOtpAnd2FaSuppressionIsIgnored() = runBlocking {
        val otpOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "Bank OTP Alert",
            text = "Your OTP is 456789. Do not share this secret code with anyone."
        )
        assertTrue(otpOutcome is NotificationProcessingOutcome.Ignored)

        val authCodeOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_DEFAULT_SMS,
            title = "Security Verification",
            text = "Verification code: 889900 for online transaction."
        )
        assertTrue(authCodeOutcome is NotificationProcessingOutcome.Ignored)
    }

    @Test
    fun testCreditInflowIsIgnoredForAutoExpenseLogging() = runBlocking {
        val creditOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Money Received",
            text = "Credited INR 5,000.00 from John Doe via UPI Ref 778899001122"
        )
        assertTrue(creditOutcome is NotificationProcessingOutcome.Ignored)
        assertEquals(0, fakeRepository.getAllTransactions().size)
    }

    @Test
    fun testDualAlertDeduplicationMergesWithoutCreatingDuplicate() = runBlocking {
        // First alert: Google Pay payment notification
        val firstOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Payment to CCD",
            text = "Paid Rs 250.00 to Cafe Coffee Day. UPI Ref: 123456789012",
            postTime = 1730811600000L
        )
        assertTrue(firstOutcome is NotificationProcessingOutcome.Created)
        assertEquals(1, fakeRepository.getAllTransactions().size)

        // Second alert: Bank SMS debit alert for identical payment arriving 10 seconds later
        val secondOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "Bank Alert",
            text = "A/c debited INR 250.00 on 05-Nov. Ref: 123456789012",
            postTime = 1730811610000L
        )

        // Must merge into existing transaction without inserting a second row
        assertTrue(secondOutcome is NotificationProcessingOutcome.Merged)
        assertEquals(1, fakeRepository.getAllTransactions().size)
    }

    @Test
    fun testCrossSourceSettleWindowFlagsPossibleDuplicate() = runBlocking {
        val baseTime = 1730811600000L

        // First alert: Payment
        val firstOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Payment Successful",
            text = "Paid Rs 400.00 to Supermart",
            postTime = baseTime
        )
        assertTrue(firstOutcome is NotificationProcessingOutcome.Created)
        assertFalse((firstOutcome as NotificationProcessingOutcome.Created).isPossibleDuplicate)

        // Second alert: SMS arriving 20 seconds later with matching amount
        val secondOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_DEFAULT_SMS,
            title = "Debit Alert",
            text = "Card debited INR 400.00 at Supermart",
            postTime = baseTime + 20_000L
        )

        // Should flag as possible duplicate rather than silently dropping
        assertTrue(secondOutcome is NotificationProcessingOutcome.Created)
        val secondCreated = secondOutcome as NotificationProcessingOutcome.Created
        assertTrue(secondCreated.isPossibleDuplicate)
        assertTrue(secondCreated.headsUpNotificationData.text.contains("Possible duplicate"))
    }

    @Test
    fun testFallbackPromptWhenAmountCannotBeExtracted() = runBlocking {
        val fallbackOutcome = processor.process(
            sourcePackage = PackageWhitelist.PACKAGE_PAYTM,
            title = "Payment Notification",
            text = "Your transaction is pending confirmation."
        )
        assertTrue(fallbackOutcome is NotificationProcessingOutcome.FallbackPrompt)
        val fallback = fallbackOutcome as NotificationProcessingOutcome.FallbackPrompt
        assertEquals("Payment detected, tap to enter amount", fallback.message)
    }

    // In-memory test double for TransactionRepository
    private class FakeTransactionRepository : TransactionRepository {
        private val storage = ConcurrentHashMap<String, Transaction>()
        private val flow = MutableStateFlow<List<Transaction>>(emptyList())

        fun getAllTransactions(): List<Transaction> = storage.values.toList()

        override fun getTransactions(): Flow<List<Transaction>> = flow.asStateFlow()

        override fun getNeedsReview(): Flow<List<Transaction>> {
            val reviewFlow = MutableStateFlow<List<Transaction>>(emptyList())
            reviewFlow.value = storage.values.filter { it.reviewState == ReviewState.NEEDS_REVIEW }
            return reviewFlow
        }

        override suspend fun getTransactionById(id: String): Transaction? = storage[id]

        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            storage[transaction.id] = transaction
            flow.value = storage.values.toList()
            return Result.Success(Unit)
        }

        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            storage[transaction.id] = transaction
            flow.value = storage.values.toList()
            return Result.Success(Unit)
        }

        override suspend fun voidTransaction(id: String): Result<Unit> {
            val existing = storage[id] ?: return Result.Error(IllegalArgumentException("Not found"))
            val voided = existing.copy(
                myShare = 0.0,
                owedToMe = 0.0,
                status = TransactionStatus.VOID,
                syncState = SyncState.VOID
            )
            storage[id] = voided
            flow.value = storage.values.toList()
            return Result.Success(Unit)
        }

        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> {
            val epsilon = 0.01
            return storage.values.filter {
                Math.abs(it.totalAmount - amount) < epsilon
            }
        }

        override suspend fun syncPendingTransactions(): Result<Int> {
            val pending = storage.values.filter { it.syncState == SyncState.PENDING && it.reviewState == ReviewState.CONFIRMED }
            pending.forEach { txn ->
                storage[txn.id] = txn.copy(syncState = SyncState.SYNCED)
            }
            flow.value = storage.values.toList()
            return Result.Success(pending.size)
        }
    }
}
