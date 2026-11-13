package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecurringDetectorTest {

    private val detector = RecurringDetector()
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)
    private val now = today.atTime(12, 0).atZone(kolkata).toInstant().toEpochMilli()

    private fun day(daysAgo: Long): Long = today.minusDays(daysAgo).atTime(9, 0).atZone(kolkata).toInstant().toEpochMilli()

    private var counter = 0

    private fun txn(
        merchant: String,
        amount: Double,
        daysAgo: Long,
        account: String = "HDFC",
        category: String = "Subscriptions",
        status: TransactionStatus = TransactionStatus.PERSONAL
    ) = Transaction(
        id = "t${counter++}", timestamp = day(daysAgo), dateSerial = 0, timeSerial = 0.0, account = account,
        merchant = merchant, category = category, totalAmount = amount, myShare = amount, owedToMe = 0.0, status = status
    )

    private fun detect(
        txns: List<Transaction>,
        subs: List<Subscription> = emptyList(),
        dismissed: Set<String> = emptySet()
    ) = detector.detect(txns, subs, dismissed, now)

    private fun sub(name: String, keywords: String = "") = Subscription(
        id = "s", name = name, amount = 100.0, frequency = SubscriptionFrequency.MONTHLY, anchorEpochDay = 0L,
        account = "HDFC", keywords = keywords
    )

    @Test
    fun monthlyPaymentIsDetectedWithTheLatestDetails() {
        val result = detect(
            listOf(
                txn("Netflix", 649.0, 92, account = "SBI"),
                txn("Netflix", 649.0, 62, account = "SBI"),
                txn("Netflix", 649.0, 31, account = "HDFC", category = "Entertainment"),
                txn("Netflix", 649.0, 1, account = "HDFC", category = "Entertainment")
            )
        )
        assertEquals(1, result.size)
        val s = result[0]
        assertEquals("Netflix", s.name)
        assertEquals(649.0, s.amount, 0.0)
        assertEquals(SubscriptionFrequency.MONTHLY, s.frequency)
        assertEquals(today.minusDays(1).toEpochDay(), s.anchorEpochDay)
        assertEquals("HDFC", s.account)
        assertEquals("Entertainment", s.category)
    }

    @Test
    fun weeklyPaymentIsDetected() {
        val result = detect(listOf(txn("Milk Subscription", 120.0, 21), txn("Milk Subscription", 120.0, 14), txn("Milk Subscription", 120.0, 7)))
        assertEquals(SubscriptionFrequency.WEEKLY, result.single().frequency)
    }

    @Test
    fun yearlyPaymentNeedsOnlyTwoOccurrencesAndLooksBeyondSixMonths() {
        val result = detect(listOf(txn("Domain Renewal", 899.0, 366), txn("Domain Renewal", 899.0, 1)))
        assertEquals(SubscriptionFrequency.YEARLY, result.single().frequency)
        assertEquals(899.0, result.single().amount, 0.0)
    }

    @Test
    fun monthlyAndWeeklyNeedThreeOccurrences() {
        assertTrue(detect(listOf(txn("Gym", 1000.0, 31), txn("Gym", 1000.0, 1))).isEmpty())
        assertTrue(detect(listOf(txn("Milk", 120.0, 14), txn("Milk", 120.0, 7))).isEmpty())
    }

    @Test
    fun gapToleranceIsThreeFourAndSevenDays() {
        // monthly gaps of 26 and 34 are inside 30 +/- 4
        assertEquals(1, detect(listOf(txn("Gym", 1000.0, 60), txn("Gym", 1000.0, 34), txn("Gym", 1000.0, 0))).size)
        // 35 is outside
        assertTrue(detect(listOf(txn("Gym", 1000.0, 65), txn("Gym", 1000.0, 30), txn("Gym", 1000.0, 0))).isEmpty())
        // weekly gaps of 4 and 10 are inside 7 +/- 3, 11 is outside
        assertEquals(1, detect(listOf(txn("Milk", 120.0, 14), txn("Milk", 120.0, 10), txn("Milk", 120.0, 0))).size)
        assertTrue(detect(listOf(txn("Milk", 120.0, 11), txn("Milk", 120.0, 0), txn("Milk", 120.0, 22))).isEmpty())
        // yearly 358 and 372 are inside, 373 is outside
        assertEquals(1, detect(listOf(txn("Insurance", 5000.0, 358), txn("Insurance", 5000.0, 0))).size)
        assertTrue(detect(listOf(txn("Insurance", 5000.0, 373), txn("Insurance", 5000.0, 0))).isEmpty())
    }

    @Test
    fun irregularPaymentsAreRejected() {
        val result = detect(
            listOf(txn("Zomato", 300.0, 100), txn("Zomato", 300.0, 71), txn("Zomato", 300.0, 20), txn("Zomato", 300.0, 3))
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun amountsMustMatchWithinOnePercent() {
        // 1 percent apart is fine
        assertEquals(1, detect(listOf(txn("Gym", 1000.0, 60), txn("Gym", 1005.0, 30), txn("Gym", 1009.0, 0))).size)
        // 5 percent apart and only 3 occurrences is not a varying bill
        assertTrue(detect(listOf(txn("Gym", 1000.0, 60), txn("Gym", 1050.0, 30), txn("Gym", 1000.0, 0))).isEmpty())
    }

    @Test
    fun aVaryingBillNeedsFourOccurrencesInABand() {
        val bill = listOf(txn("Electricity", 800.0, 91), txn("Electricity", 1100.0, 61), txn("Electricity", 950.0, 31), txn("Electricity", 1000.0, 1))
        val found = detect(bill)
        assertEquals(1, found.size)
        assertEquals(SubscriptionFrequency.MONTHLY, found[0].frequency)
        assertEquals(975.0, found[0].amount, 0.0)
        // the same bill with only three occurrences is not reported
        assertTrue(detect(bill.drop(1)).isEmpty())
        // a wild swing is outside the band
        val wild = listOf(txn("Electricity", 100.0, 91), txn("Electricity", 5000.0, 61), txn("Electricity", 950.0, 31), txn("Electricity", 1000.0, 1))
        assertTrue(detect(wild).isEmpty())
    }

    @Test
    fun descriptionsAreGroupedIgnoringCaseDigitsAndSpaces() {
        val result = detect(
            listOf(
                txn("NETFLIX 1234", 649.0, 61),
                txn("netflix 98 76", 649.0, 31),
                txn("Netflix", 649.0, 1)
            )
        )
        assertEquals(1, result.size)
        assertEquals("netflix", result[0].key)
    }

    @Test
    fun voidRowsAreIgnored() {
        val result = detect(
            listOf(
                txn("Gym", 1000.0, 61),
                txn("Gym", 1000.0, 31, status = TransactionStatus.VOID),
                txn("Gym", 1000.0, 1)
            )
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun sameDayDuplicatesCountOnce() {
        val result = detect(listOf(txn("Gym", 1000.0, 31), txn("Gym", 1000.0, 31), txn("Gym", 1000.0, 1)))
        assertTrue("two distinct days only", result.isEmpty())
    }

    @Test
    fun paymentsOlderThanSixMonthsDoNotCountForMonthly() {
        val result = detect(listOf(txn("Gym", 1000.0, 250), txn("Gym", 1000.0, 220), txn("Gym", 1000.0, 190), txn("Gym", 1000.0, 1)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun alreadyTrackedSubscriptionsAreSkippedByNameOrKeyword() {
        val gym = listOf(txn("Cult Fit Gym", 1000.0, 61), txn("Cult Fit Gym", 1000.0, 31), txn("Cult Fit Gym", 1000.0, 1))
        assertEquals(1, detect(gym).size)
        assertTrue(detect(gym, subs = listOf(sub("cult fit"))).isEmpty())
        assertTrue(detect(gym, subs = listOf(sub("Fitness", keywords = "gym, yoga"))).isEmpty())
        assertEquals(1, detect(gym, subs = listOf(sub("Netflix"))).size)
    }

    @Test
    fun dismissedKeysAreSkipped() {
        val gym = listOf(txn("Cult Fit Gym", 1000.0, 61), txn("Cult Fit Gym", 1000.0, 31), txn("Cult Fit Gym", 1000.0, 1))
        val key = detect(gym).single().key
        assertTrue(detect(gym, dismissed = setOf(key)).isEmpty())
    }

    @Test
    fun results_areSortedByName() {
        val result = detect(
            listOf(txn("Zed", 10.0, 61), txn("Zed", 10.0, 31), txn("Zed", 10.0, 1),
                txn("Alpha", 20.0, 61), txn("Alpha", 20.0, 31), txn("Alpha", 20.0, 1))
        )
        assertEquals(listOf("Alpha", "Zed"), result.map { it.name })
    }

    @Test
    fun dismissedPreferenceStoresAndReturnsKeys() = runBlocking {
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        assertTrue(prefs.getDismissedRecurring().isEmpty())

        prefs.addDismissedRecurring("netflix")
        prefs.addDismissedRecurring("cultfitgym")
        prefs.addDismissedRecurring("netflix")
        prefs.addDismissedRecurring("  ")
        prefs.addDismissedRecurring("a,b")

        assertEquals(setOf("netflix", "cultfitgym"), prefs.getDismissedRecurring())
        assertEquals("cultfitgym,netflix", prefs.getPreference("dismissed_recurring"))
    }
}
