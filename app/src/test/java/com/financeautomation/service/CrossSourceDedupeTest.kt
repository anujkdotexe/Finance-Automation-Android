package com.financeautomation.service

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.MerchantSimilarity
import com.financeautomation.testutil.FakePendingCaptureDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-4: a payment alert is merged with another source only when the payee is compatible, not on amount alone. */
class CrossSourceDedupeTest {

    private val now = 1_800_000_000_000L

    private fun processor(dao: FakePendingCaptureDao) = NotificationProcessor(pendingCaptureDao = dao)

    @Test
    fun twoPaymentsOfTheSameAmountToDifferentPayeesWithin90SecondsAreNotMerged() = runBlocking {
        val dao = FakePendingCaptureDao()
        val p = processor(dao)

        p.process(PackageWhitelist.PACKAGE_GPAY, "Paid ₹349 to Swiggy", "UPI transaction", postTime = now, sbnKey = "k1")
        val second = p.process(PackageWhitelist.PACKAGE_PHONEPE, "Payment Successful", "Paid ₹349 to Zomato", postTime = now + 30_000L, sbnKey = "k2")

        assertTrue("second payment must be its own capture but was $second", second is NotificationProcessingOutcome.Captured)
        assertEquals(2, dao.captures.size)
    }

    @Test
    fun theSamePaymentReportedByTwoAppsIsStillMerged() = runBlocking {
        val dao = FakePendingCaptureDao()
        val p = processor(dao)

        p.process(PackageWhitelist.PACKAGE_GPAY, "Paid ₹349 to Swiggy", "UPI transaction", postTime = now, sbnKey = "k1")
        val second = p.process(PackageWhitelist.PACKAGE_PHONEPE, "Payment Successful", "Paid ₹349 to Swiggy Instamart", postTime = now + 30_000L, sbnKey = "k2")

        assertTrue(second is NotificationProcessingOutcome.Merged)
        assertEquals(1, dao.captures.size)
    }

    @Test
    fun anAlertWithoutAReadablePayeeMergesWithTheNamedOne() = runBlocking {
        val dao = FakePendingCaptureDao()
        val p = processor(dao)

        p.process(PackageWhitelist.PACKAGE_GPAY, "Paid ₹349 to Swiggy", "UPI transaction", postTime = now, sbnKey = "k1")
        val second = p.process(PackageWhitelist.PACKAGE_PHONEPE, "Payment Successful", "Debited ₹349 from your account", postTime = now + 30_000L, sbnKey = "k2")

        assertTrue(second is NotificationProcessingOutcome.Merged)
    }

    @Test
    fun merchantSimilarityRules() {
        assertTrue(MerchantSimilarity.compatible("Swiggy", "swiggy@okaxis"))
        assertTrue(MerchantSimilarity.compatible("Cafe Coffee Day", "Coffee Day Bangalore"))
        assertTrue(MerchantSimilarity.compatible("Unknown Payee", "Zomato"))
        assertTrue(MerchantSimilarity.compatible("", "Zomato"))
        assertFalse(MerchantSimilarity.compatible("Swiggy", "Zomato"))
        assertFalse(MerchantSimilarity.compatible("ravi@okaxis", "meena@oksbi"))
    }
}
