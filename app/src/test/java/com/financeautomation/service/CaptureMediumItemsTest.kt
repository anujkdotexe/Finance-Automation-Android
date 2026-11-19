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
            text = "Recharge successful of Rs 899.00 for Jio number 9876543210. Plan Name: 899. Ref: JIO12345.",
            sbnKey = "0|sms|401",
            postTime = t0
        )
        assertTrue("English recharge alert should be captured", r1 is NotificationProcessingOutcome.Captured)
        assertEquals(1, captureDao.captures.size)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "JioPay-S",
            text = "जियो नंबर 9876543210 के लिए रु 899.00 का रिचार्ज सफल रहा। प्लान का नाम: 899. Ref: JIO12345.",
            sbnKey = "0|sms|402",
            postTime = t0 + 5_000L
        )
        assertTrue("Hindi recharge alert must merge", r2 is NotificationProcessingOutcome.Merged)
        assertEquals(1, captureDao.captures.size)

        val r3 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "JioPay-S",
            text = "जिओ नंबर 9876543210 साठी रु 899.00 चा रिचार्ज यशस्वी झाला. प्लॅन चे नाव: 899. Ref: JIO12345.",
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
    fun duplicateTextRepeatedWithinAlertWindowDroppedAsDuplicate() = runBlocking {
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
            postTime = t0 + 60_000L // 1 minute later (< 3 min window)
        )
        assertTrue("Duplicate alert within window must be dropped", r2 is NotificationProcessingOutcome.Ignored)
        assertEquals("Only one capture should exist in DB", 1, captureDao.captures.size)
    }

    @Test
    fun secondIdenticalPaymentOutsideWindowCapturedDistinctly() = runBlocking {
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
        assertTrue("First payment should be captured", r1 is NotificationProcessingOutcome.Captured)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = text,
            sbnKey = "0|gpay|503",
            postTime = t0 + 3600_000L // 1 hour later (> 3 min window)
        )
        assertTrue("Second identical payment after 1 hour must be captured", r2 is NotificationProcessingOutcome.Captured)
        assertEquals("Two distinct captures should exist in DB", 2, captureDao.captures.size)
    }

    @Test
    fun appNotificationThenBankSms4MinutesLaterGivesOneRowWithAppPayeeAndBankAccount() = runBlocking {
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
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹350.00 to Swiggy",
            sbnKey = "0|gpay|601",
            postTime = t0
        )
        assertTrue("App notification should be captured", r1 is NotificationProcessingOutcome.Captured)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 350.00 has been DEBITED to your account XXX1824 on 15/03/2026. Dial 1930 to report cyber fraud - Canara Bank",
            sbnKey = "0|sms|602",
            postTime = t0 + 240_000L // 4 minutes later (within 10-minute cross-package window)
        )
        assertTrue("Cross-package bank SMS within 10m must merge", r2 is NotificationProcessingOutcome.Merged)
        assertEquals("Only one row must exist in DB", 1, captureDao.captures.size)

        val capture = captureDao.captures.values.first()
        assertEquals("Payee must be preserved as Swiggy", "Swiggy", capture.payee)
        assertTrue("Account hint must be enriched from bank SMS", capture.detectedAccountHint?.contains("1824") == true)
    }

    @Test
    fun bankSms20MinutesAfterConfirmedRowMergesIntoConfirmed() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val txDao = FakeTestTransactionDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao,
            transactionDao = txDao
        )

        val t0 = 1713600000000L
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹400.00 to Swiggy",
            sbnKey = "0|gpay|701",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)
        val initialCap = (r1 as NotificationProcessingOutcome.Captured).capture

        // Mark capture as DONE (confirmed by user)
        val confirmedCap = initialCap.copy(state = PendingCaptureEntity.STATE_DONE)
        captureDao.update(confirmedCap)

        // Seed confirmed transaction in txDao with user note
        val initialTxn = com.financeautomation.data.local.entity.TransactionEntity(
            id = confirmedCap.id,
            timestamp = t0,
            dateSerial = 46100,
            timeSerial = 0.5,
            account = "Other",
            merchant = "Swiggy",
            tripTag = "Dinner with friends",
            category = "Food & Drink",
            totalAmount = 400.0,
            companions = "Personal",
            myShare = 400.0,
            owedToMe = 0.0,
            status = "Personal",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            reviewState = "CONFIRMED",
            syncState = "PENDING"
        )
        txDao.insert(initialTxn)

        // Bank SMS arrives 20 minutes later (within the 30-minute window)
        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 400.00 has been DEBITED to your account XXX1824 on 15/03/2026. Dial 1930 to report cyber fraud - Canara Bank",
            sbnKey = "0|sms|702",
            postTime = t0 + 20 * 60_000L
        )
        assertTrue("Bank SMS 20m later must merge into confirmed", r2 is NotificationProcessingOutcome.Merged)
        assertEquals("No second row must be created", 1, captureDao.captures.size)

        val updatedTxn = txDao.getById(confirmedCap.id)!!
        assertEquals("User note / tripTag must NOT be overwritten", "Dinner with friends", updatedTxn.tripTag)
        assertEquals("Amount must NOT change", 400.0, updatedTxn.totalAmount, 0.001)
        assertEquals("Merchant must NOT change", "Swiggy", updatedTxn.merchant)
        assertEquals("Confirmed transaction row in DB must remain completely identical to before merge", initialTxn, updatedTxn)
    }

    @Test
    fun bankSms3HoursAfterConfirmedRowGivesSeparateRow() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val txDao = FakeTestTransactionDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao,
            transactionDao = txDao
        )

        val t0 = 1713600000000L
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹400.00 to Swiggy",
            sbnKey = "0|gpay|801",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)
        val initialCap = (r1 as NotificationProcessingOutcome.Captured).capture

        val confirmedCap = initialCap.copy(state = PendingCaptureEntity.STATE_DONE)
        captureDao.update(confirmedCap)

        val initialTxn = com.financeautomation.data.local.entity.TransactionEntity(
            id = confirmedCap.id,
            timestamp = t0,
            dateSerial = 46100,
            timeSerial = 0.5,
            account = "Other",
            merchant = "Swiggy",
            tripTag = "Dinner with friends",
            category = "Food & Drink",
            totalAmount = 400.0,
            companions = "Personal",
            myShare = 400.0,
            owedToMe = 0.0,
            status = "Personal",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            reviewState = "CONFIRMED",
            syncState = "PENDING"
        )
        txDao.insert(initialTxn)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 400.00 has been DEBITED to your account XXX1824 on 15/03/2026. Dial 1930 to report cyber fraud - Canara Bank",
            sbnKey = "0|sms|802",
            postTime = t0 + 3 * 3600_000L
        )
        assertTrue("Bank SMS 3h later must NOT merge into confirmed row", r2 is NotificationProcessingOutcome.Captured)
        assertEquals("A new capture row must be created", 2, captureDao.captures.size)
    }

    @Test
    fun extractReferenceIdRejectsFalseWordsWithoutDigits() {
        // Words like payment, successful, transaction must return null as reference id
        val text1 = "UPI payment of Rs 500 successful"
        assertEquals(null, RegexPatterns.extractReferenceId(text1))

        val text2 = "txn successful on 12-May"
        assertEquals(null, RegexPatterns.extractReferenceId(text2))

        val text3 = "UPI transaction completed"
        assertEquals(null, RegexPatterns.extractReferenceId(text3))

        // Legitimate reference IDs with >= 4 digits must be extracted
        val text4 = "Paid Rs 500. UPI Ref 123456789012"
        assertEquals("123456789012", RegexPatterns.extractReferenceId(text4))

        val text5 = "TXN ID - E2601020RLFNFN has been processed"
        assertEquals("E2601020RLFNFN", RegexPatterns.extractReferenceId(text5))
    }

    @Test
    fun sameMerchantSameAmountTwoHoursApartDifferentReferenceIdsGivesTwoRows() = runBlocking {
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
            text = "Paid thru UPI Rs. 20.00 to Chai Point. Ref 111111222222 - Canara Bank",
            sbnKey = "0|sms|901",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 20.00 to Chai Point. Ref 333333444444 - Canara Bank",
            sbnKey = "0|sms|902",
            postTime = t0 + 2 * 3600_000L // 2 hours later
        )
        assertTrue("Different reference IDs 2 hours apart must NOT merge", r2 is NotificationProcessingOutcome.Captured)
        assertEquals("Two distinct rows must exist in DB", 2, captureDao.captures.size)
    }

    @Test
    fun sameMerchantSameAmountTwoHoursApartNoReferenceIdsAndPayeeInSecondMessageGivesTwoRows() = runBlocking {
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
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹20.00 to Chai Point",
            sbnKey = "0|gpay|911",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)
        val cap1 = (r1 as NotificationProcessingOutcome.Captured).capture
        captureDao.update(cap1.copy(state = PendingCaptureEntity.STATE_DONE))

        // Second payment 2 hours later with specific payee in message
        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 20.00 to Chai Point on 15/03/2026 - Canara Bank",
            sbnKey = "0|sms|912",
            postTime = t0 + 2 * 3600_000L
        )
        assertTrue("Second payment with specific payee must NOT be swallowed into confirmed", r2 is NotificationProcessingOutcome.Captured)
        assertEquals("Must produce two separate rows", 2, captureDao.captures.size)
    }

    @Test
    fun genericBankSmsAfterAppNotificationMergesOnceAndSecondGenericSmsMakesRow() = runBlocking {
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
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹350.00 to Swiggy",
            sbnKey = "0|gpay|921",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)
        val cap1 = (r1 as NotificationProcessingOutcome.Captured).capture
        captureDao.update(cap1.copy(state = PendingCaptureEntity.STATE_DONE))

        // First generic bank SMS within 30 minutes merges into confirmed capture
        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 350.00 has been DEBITED to your account XXX1824 on 15/03/2026. Avail Bal: INR 5,000. Dial 1930 to report cyber fraud - Canara Bank",
            sbnKey = "0|sms|922",
            postTime = t0 + 20 * 60_000L // 20 minutes later
        )
        assertTrue("First generic bank SMS within 20 minutes must merge into confirmed", r2 is NotificationProcessingOutcome.Merged)
        assertEquals("Still only one capture in DB", 1, captureDao.captures.size)

        // Second generic bank SMS arrives later for a different transaction
        val r3 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 350.00 has been DEBITED to your account XXX1824 on 15/03/2026. Avail Bal: INR 4,650. Dial 1930 to report cyber fraud - Canara Bank",
            sbnKey = "0|sms|923",
            postTime = t0 + 25 * 60_000L // 25 minutes later
        )
        assertTrue("Second generic bank SMS must NOT be swallowed a second time; makes a row", r3 is NotificationProcessingOutcome.Captured)
        assertEquals("Two captures must exist in DB", 2, captureDao.captures.size)
    }

    @Test
    fun genericBankSmsThreeHoursAfterConfirmedTransactionMakesARow() = runBlocking {
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
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹350.00 to Swiggy",
            sbnKey = "0|gpay|941",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)
        val cap1 = (r1 as NotificationProcessingOutcome.Captured).capture
        captureDao.update(cap1.copy(state = PendingCaptureEntity.STATE_DONE))

        // Generic bank SMS arriving 3 hours later (outside the 30-minute window) must NOT merge
        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 350.00 has been DEBITED to your account XXX1824 on 15/03/2026. Avail Bal: INR 5,000. Dial 1930 to report cyber fraud - Canara Bank",
            sbnKey = "0|sms|942",
            postTime = t0 + 3 * 3600_000L // 3 hours later
        )
        assertTrue("Generic bank SMS 3 hours later must NOT merge into confirmed; creates separate row", r2 is NotificationProcessingOutcome.Captured)
        assertEquals("Must produce two captures in DB", 2, captureDao.captures.size)
    }

    @Test
    fun mergingIntoNeedsReviewRowNeverOverwritesUserEditedPayeeOrCategoryOrNotes() = runBlocking {
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
            text = "Paid thru UPI Rs. 250.00 to Swiggy on 15/03/2026 from A/c *1824 - Canara Bank",
            sbnKey = "0|sms|931",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)
        val initialCap = (r1 as NotificationProcessingOutcome.Captured).capture

        // Simulate user editing payee in Needs Review screen
        val userEditedCap = initialCap.copy(
            payee = "Custom Swiggy Order #123",
            state = PendingCaptureEntity.STATE_NEEDS_REVIEW
        )
        captureDao.update(userEditedCap)

        // Second generic bank SMS arrives within 180s
        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 250.00 has been DEBITED to your account XXX1824 on 15/03/2026. Dial 1930 to report cyber fraud - Canara Bank",
            sbnKey = "0|sms|932",
            postTime = t0 + 60_000L
        )
        assertTrue(r2 is NotificationProcessingOutcome.Merged)

        val merged = captureDao.getById(initialCap.id)!!
        assertEquals("User-edited payee must NEVER be overwritten", "Custom Swiggy Order #123", merged.payee)
    }

    @Test
    fun sameAmountDifferentPayeesDifferentReferenceIdsGiveTwoRows() = runBlocking {
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
            text = "Paid thru UPI Rs. 500.00 to Alpha Tech on 15/03/2026 from A/c *1824. Ref 111111222222 - Canara Bank",
            sbnKey = "0|sms|801",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 500.00 to Beta Bakery on 15/03/2026 from A/c *1824. Ref 333333444444 - Canara Bank",
            sbnKey = "0|sms|802",
            postTime = t0 + 60_000L
        )
        assertTrue("Different reference IDs and payees must NOT merge", r2 is NotificationProcessingOutcome.Captured)
        assertEquals("Must produce two separate rows", 2, captureDao.captures.size)
    }

    @Test
    fun sameReferenceId5HoursApartMerges() = runBlocking {
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
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹600.00 to Fresh Fruits. UPI Ref 987654321012",
            sbnKey = "0|gpay|901",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)

        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "An amount of INR 600.00 has been DEBITED to your account XXX1824. UPI Ref 987654321012 - Canara Bank",
            sbnKey = "0|sms|902",
            postTime = t0 + 5 * 3600_000L // 5 hours later
        )
        assertTrue("Equal reference ID must merge even 5 hours apart", r2 is NotificationProcessingOutcome.Merged)
        assertEquals("Only one row must exist in DB", 1, captureDao.captures.size)
    }

    @Test
    fun paymentGatewayAggregatorReceiptDroppedWithoutBankDebit() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao
        )

        val r = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "EASEBZ-S",
            text = "Dear Customer, Thank you for making a successful transaction. Your transaction with TXN ID - E2601020RLFNFN has been successfully processed. Amount deducted is Rs. 199.0 -Easebuzz",
            sbnKey = "0|sms|1001",
            postTime = 1713600000000L
        )
        assertTrue("Aggregator receipt must be dropped without creating capture", r is NotificationProcessingOutcome.Ignored)
        assertEquals(0, captureDao.captures.size)
    }

    @Test
    fun mergeDecisionExecutesInsideDatabaseTransaction() = runBlocking {
        val handledDao = FakeTestHandledDao()
        val captureDao = FakeTestCaptureDao()
        val parser = ParseNotificationUseCase()
        var transactionExecuted = false

        val processor = NotificationProcessor(
            parseUseCase = parser,
            pendingCaptureDao = captureDao,
            handledNotificationDao = handledDao,
            runInTransaction = { block ->
                transactionExecuted = true
                block()
            }
        )

        val t0 = 1713600000000L
        val r1 = processor.process(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Paid ₹100.00 to Chai Point",
            sbnKey = "0|gpay|950",
            postTime = t0
        )
        assertTrue(r1 is NotificationProcessingOutcome.Captured)
        assertTrue("Insert must execute in transaction", transactionExecuted)

        transactionExecuted = false
        val r2 = processor.process(
            sourcePackage = "com.google.android.apps.messaging",
            title = "CANBNK-S",
            text = "Paid thru UPI Rs. 100.00 to Chai Point. Ref 999888 - Canara Bank",
            sbnKey = "0|sms|951",
            postTime = t0 + 10_000L
        )
        assertTrue("Second alert must merge into first", r2 is NotificationProcessingOutcome.Merged)
        assertTrue("Merge must execute in transaction", transactionExecuted)
    }

    private class FakeTestTransactionDao : com.financeautomation.data.local.dao.TransactionDao {
        val txns = ConcurrentHashMap<String, com.financeautomation.data.local.entity.TransactionEntity>()
        override fun getTransactionsFlow(): Flow<List<com.financeautomation.data.local.entity.TransactionEntity>> = emptyFlow()
        override fun getNeedsReviewFlow(): Flow<List<com.financeautomation.data.local.entity.TransactionEntity>> = emptyFlow()
        override fun getConfirmedUnsyncedFlow(): Flow<List<com.financeautomation.data.local.entity.TransactionEntity>> = emptyFlow()
        override fun getAccountTransactionCounts(from: Long, to: Long): Flow<List<com.financeautomation.data.local.dao.AccountTransactionCount>> = emptyFlow()
        override suspend fun getById(id: String): com.financeautomation.data.local.entity.TransactionEntity? = txns[id]
        override suspend fun getBySheetRowIndex(rowIndex: Int): com.financeautomation.data.local.entity.TransactionEntity? = null
        override suspend fun getAllBySheetRowIndex(rowIndex: Int): List<com.financeautomation.data.local.entity.TransactionEntity> = emptyList()
        override suspend fun getRecentSimilar(minTimestamp: Long, maxTimestamp: Long, minAmount: Double, maxAmount: Double): List<com.financeautomation.data.local.entity.TransactionEntity> =
            txns.values.filter { it.totalAmount in minAmount..maxAmount && it.timestamp in minTimestamp..maxTimestamp }
        override suspend fun insert(transaction: com.financeautomation.data.local.entity.TransactionEntity) { txns[transaction.id] = transaction }
        override suspend fun insertAll(transactions: List<com.financeautomation.data.local.entity.TransactionEntity>) { transactions.forEach { txns[it.id] = it } }
        override suspend fun update(transaction: com.financeautomation.data.local.entity.TransactionEntity) { txns[transaction.id] = transaction }
        override suspend fun delete(transaction: com.financeautomation.data.local.entity.TransactionEntity) { txns.remove(transaction.id) }
        override suspend fun deleteById(id: String) { txns.remove(id) }
        override suspend fun voidTransaction(id: String, voidTitle: String) {}
        override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {}
        override suspend fun updateReviewState(id: String, reviewState: String, category: String?) {}
        override suspend fun updateAccountName(oldName: String, newName: String): Int = 0
        override suspend fun updateCategoryName(oldName: String, newName: String): Int = 0
        override suspend fun updateCompanionName(oldName: String, newName: String): Int = 0
        override suspend fun getAllForYear(startMillis: Long, endMillis: Long): List<com.financeautomation.data.local.entity.TransactionEntity> = emptyList()
        override suspend fun resetSyncStateForRequeue(id: String) {}
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
