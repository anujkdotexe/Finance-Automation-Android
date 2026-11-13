package com.financeautomation.domain

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.domain.usecase.DropReason
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.service.NotificationProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-1: payment app notifications need success wording and a direction verb; promotional text is ignored.
 */
class PaymentAlertFilterTest {

    private val useCase = ParseNotificationUseCase()
    private val sms = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES

    private fun parse(pkg: String, title: String, text: String) = useCase(sourcePackage = pkg, title = title, text = text)

    private fun assertCaptured(pkg: String, title: String, text: String, amount: Double, credit: Boolean = false) {
        val r = parse(pkg, title, text)
        assertTrue("expected a capture for [$title] [$text] but got $r", r is NotificationParseResult.Success)
        r as NotificationParseResult.Success
        assertEquals(amount, r.transaction.totalAmount, 0.001)
        assertEquals(credit, r.isCredit)
    }

    private fun assertDroppedFor(pkg: String, title: String, text: String, reasonName: String) {
        val r = parse(pkg, title, text)
        assertTrue("expected Dropped($reasonName) for [$title] [$text] but got $r", r is NotificationParseResult.Dropped)
        assertEquals(reasonName, (r as NotificationParseResult.Dropped).reason.name)
    }

    // ---- real looking captures stay working

    @Test
    fun googlePayPaidIsCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_GPAY, "Paid ₹349 to Swiggy", "UPI transaction ID 412345678901", 349.0)
        assertCaptured(PackageWhitelist.PACKAGE_GPAY, "You paid ₹1,250.50 to Reliance Fresh", "Google Pay", 1250.5)
    }

    @Test
    fun phonePeIsCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_PHONEPE, "Payment Successful", "Paid ₹750 to Cafe Coffee Day. Txn ID T2411120955", 750.0)
        assertCaptured(PackageWhitelist.PACKAGE_PHONEPE, "Money sent", "Sent ₹500 to Rohan Mehta", 500.0)
    }

    @Test
    fun paytmIsCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_PAYTM, "Paid Rs.250 to Ola Cabs", "Payment successful", 250.0)
    }

    @Test
    fun bhimIsCaptured() {
        assertCaptured(
            PackageWhitelist.PACKAGE_BHIM, "BHIM",
            "Rs 500.00 debited from A/c XX1234 on 12-11-26 to VPA ravi@okaxis. UPI Ref 412345678901", 500.0
        )
    }

    @Test
    fun supermoneyPaymentSuccessfulIsCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_SUPERMONEY, "Payment successful", "Payment of ₹899 to Amazon Pay successful", 899.0)
    }

    @Test
    fun receivedMoneyIsCapturedAsCredit() {
        assertCaptured(PackageWhitelist.PACKAGE_PHONEPE, "Money received", "Received ₹1,000 from Aman Verma", 1000.0, credit = true)
    }

    @Test
    fun paymentInProgressWithoutAmountStillSurfacesTheFallbackPrompt() {
        val r = parse(PackageWhitelist.PACKAGE_PAYTM, "Payment Processing", "Transaction in progress. Please check your order history.")
        assertTrue(r is NotificationParseResult.FallbackPrompt)
    }

    // ---- C-1: promotions and noise are ignored

    @Test
    fun promotionalNotificationsAreDropped() {
        val promos = listOf(
            PackageWhitelist.PACKAGE_GPAY to ("Get cashback" to "Get up to ₹100 cashback on your next payment"),
            PackageWhitelist.PACKAGE_PHONEPE to ("Exclusive offer" to "Win ₹500 reward. Scratch card waiting for you"),
            PackageWhitelist.PACKAGE_PAYTM to ("Bill due" to "Your electricity bill of ₹1,240 is due on 15 Nov"),
            PackageWhitelist.PACKAGE_GPAY to ("Reminder" to "Reminder: pay ₹899 before the due date to avoid late fee"),
            PackageWhitelist.PACKAGE_SUPERMONEY to ("Refer and earn" to "Refer a friend and earn ₹100. Offer valid till Friday"),
            PackageWhitelist.PACKAGE_PAYTM to ("Recharge" to "Mobile recharge offer: Rs 199 plan with cashback ₹20")
        )
        for ((pkg, content) in promos) {
            assertDroppedFor(pkg, content.first, content.second, "PROMOTIONAL")
        }
    }

    @Test
    fun noSuccessWordingIsDropped() {
        assertDroppedFor(PackageWhitelist.PACKAGE_PHONEPE, "Your order", "Your order of Rs 500 is being prepared", "NO_SUCCESS_WORDING")
    }

    @Test
    fun unknownDirectionIsIgnoredNotTreatedAsDebit() {
        assertDroppedFor(PackageWhitelist.PACKAGE_PAYTM, "Transaction", "Transaction successful for Rs 300", "UNKNOWN_DIRECTION")
    }

    @Test
    fun aRealPaymentWithPromoWordsInTheTailIsStillCaptured() {
        assertCaptured(
            PackageWhitelist.PACKAGE_GPAY, "Paid ₹500 to Electricity Board",
            "Bill payment successful. Earn rewards on your next payment", 500.0
        )
    }

    @Test
    fun dropCodesAreCountedPerReason() {
        assertEquals(PipelineCode.DROPPED_OTP, NotificationProcessor.pipelineCodeFor(DropReason.OTP_MESSAGE))
        assertEquals(PipelineCode.DROPPED_PROMO, NotificationProcessor.pipelineCodeFor(DropReason.PROMOTIONAL))
        assertEquals(PipelineCode.DROPPED_NO_SUCCESS_WORDING, NotificationProcessor.pipelineCodeFor(DropReason.NO_SUCCESS_WORDING))
        assertEquals(PipelineCode.DROPPED_UNKNOWN_DIRECTION, NotificationProcessor.pipelineCodeFor(DropReason.UNKNOWN_DIRECTION))
        assertEquals(PipelineCode.DROPPED_BLACKLIST, NotificationProcessor.pipelineCodeFor(DropReason.BLACKLISTED))
    }

    // ---- completed payments that contain a soft promo word are still captured

    @Test
    fun billPaymentSuccessfulIsCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_PAYTM, "Bill payment", "Your electricity bill payment of Rs 899 is successful", 899.0)
    }

    @Test
    fun rechargeSuccessfulIsCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_PHONEPE, "Recharge", "Recharge of Rs 299 successful", 299.0)
    }

    @Test
    fun emiPaidSuccessfullyIsCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_GPAY, "EMI", "EMI of Rs 4,500 paid successfully", 4500.0)
    }

    @Test
    fun paymentToAWaterBillDepartmentIsCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_BHIM, "BHIM", "Payment of Rs 150 to Water Bill Dept successful", 150.0)
    }

    @Test
    fun aRealPaymentThatAdvertisesCashbackIsStillCaptured() {
        assertCaptured(PackageWhitelist.PACKAGE_GPAY, "Paid Rs 349 to Swiggy", "Earn cashback on your next payment", 349.0)
    }

    // ---- pure promotions with an amount are still dropped

    @Test
    fun cashbackOfferWithAnAmountIsDropped() {
        assertDroppedFor(PackageWhitelist.PACKAGE_PAYTM, "Offer", "Get Rs 100 cashback offer on your next recharge", "PROMOTIONAL")
        assertDroppedFor(PackageWhitelist.PACKAGE_GPAY, "Scratch card", "You won a scratch card worth Rs 50", "PROMOTIONAL")
        assertDroppedFor(PackageWhitelist.PACKAGE_PHONEPE, "Voucher", "Voucher of Rs 200 is waiting. Refer a friend to claim", "PROMOTIONAL")
    }

    @Test
    fun billDueReminderWithAnAmountIsDropped() {
        assertDroppedFor(PackageWhitelist.PACKAGE_PAYTM, "Reminder", "Your electricity bill of Rs 1,240 is due tomorrow", "PROMOTIONAL")
        assertDroppedFor(PackageWhitelist.PACKAGE_GPAY, "EMI due", "Your EMI of Rs 4,500 is due on 5 Dec. Pay now", "PROMOTIONAL")
        assertDroppedFor(PackageWhitelist.PACKAGE_SUPERMONEY, "Loan", "Pre-approved loan of Rs 50,000 is waiting for you", "PROMOTIONAL")
    }

    // ---- diagnostics counters hold codes only, never text

    @Test
    fun droppedReasonsAreCountedWithoutAnyText() = kotlinx.coroutines.runBlocking {
        com.financeautomation.core.utils.PipelineTrail.resetForTesting()
        val processor = NotificationProcessor()
        processor.process(PackageWhitelist.PACKAGE_GPAY, "Get cashback", "Get up to Rs 100 cashback on your next payment")
        processor.process(PackageWhitelist.PACKAGE_PHONEPE, "Your order", "Your order of Rs 777 is being prepared")
        processor.process(PackageWhitelist.PACKAGE_PAYTM, "Transaction", "Transaction successful for Rs 888")
        processor.process(sms, "HDFCBK", "482910 is your OTP for payment of Rs 555. Do not share.")

        val events = com.financeautomation.core.utils.PipelineTrail.bufferedEventsForTesting()
        val codes = events.map { it.code }
        assertTrue(codes.contains(PipelineCode.DROPPED_PROMO.name))
        assertTrue(codes.contains(PipelineCode.DROPPED_NO_SUCCESS_WORDING.name))
        assertTrue(codes.contains(PipelineCode.DROPPED_UNKNOWN_DIRECTION.name))
        assertTrue(codes.contains(PipelineCode.DROPPED_OTP.name))
        for (e in events) {
            assertEquals(null, e.a)
            assertEquals(null, e.b)
            val dump = "${e.stage} ${e.code}"
            assertFalse(dump.contains("777") || dump.contains("888") || dump.contains("555") || dump.contains("482910"))
        }
        com.financeautomation.core.utils.PipelineTrail.resetForTesting()
    }
}
