package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MonthBurnSummaryUseCaseTest {

    private val useCase = MonthBurnSummaryUseCase()
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, s: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, s, 0, kolkata).toInstant().toEpochMilli()

    private fun txn(category: String, share: Double, ts: Long, status: TransactionStatus = TransactionStatus.PERSONAL) =
        Transaction(
            id = "t$ts$category$share", timestamp = ts, dateSerial = 0, timeSerial = 0.0, account = "SBI",
            merchant = "m", category = category, totalAmount = share, myShare = share, owedToMe = 0.0, status = status,
            reviewState = com.financeautomation.core.model.ReviewState.CONFIRMED
        )

    @Test
    fun burnAndPerDayAverageForTheCurrentMonth() {
        val result = useCase(
            transactions = listOf(txn("Food", 300.0, at(2026, 11, 2)), txn("Food", 200.0, at(2026, 11, 10))),
            categories = emptyList(),
            waitingCount = 0,
            nowMillis = at(2026, 11, 10)
        )
        assertEquals(2026, result.year)
        assertEquals(11, result.month)
        assertEquals(500.0, result.livingBurn, 0.0)
        assertEquals(10, result.daysElapsed)
        assertEquals(50.0, result.perDayAverage, 0.0)
    }

    @Test
    fun usesTheLivingBurnRule() {
        val categories = listOf(Category("1", "Rent", isExcludedFromLivingBurn = true))
        val result = useCase(
            transactions = listOf(
                txn("Food", 100.0, at(2026, 11, 3)),
                txn("Savings", 500.0, at(2026, 11, 3)),
                txn("Transfer", 500.0, at(2026, 11, 3)),
                txn("Rent", 500.0, at(2026, 11, 3)),
                txn("Food", 900.0, at(2026, 11, 3), status = TransactionStatus.VOID),
                txn("Food", 900.0, at(2026, 10, 31))
            ),
            categories = categories,
            waitingCount = 0,
            nowMillis = at(2026, 11, 5)
        )
        assertEquals(100.0, result.livingBurn, 0.0)
    }

    @Test
    fun monthStartAndEndFollowAsiaKolkata() {
        val txns = listOf(txn("Food", 10.0, at(2026, 11, 1, 0, 0, 0)), txn("Food", 20.0, at(2026, 11, 30, 23, 59, 59)),
            txn("Food", 99.0, at(2026, 12, 1, 0, 0, 0)))
        // 2026-11-30 23:00 IST is still November in Kolkata even though it is 17:30 UTC on the same day
        val result = useCase(txns, emptyList(), 0, at(2026, 11, 30, 23, 0, 0))
        assertEquals(30.0, result.livingBurn, 0.0)
        assertEquals(30, result.daysElapsed)
        // 2026-12-01 00:00 IST rolls the widget to December
        val december = useCase(txns, emptyList(), 0, at(2026, 12, 1, 0, 0, 0))
        assertEquals(12, december.month)
        assertEquals(99.0, december.livingBurn, 0.0)
        assertEquals(1, december.daysElapsed)
    }

    @Test
    fun emptyMonthHasZeroBurnAndZeroAverage() {
        val result = useCase(emptyList(), emptyList(), 0, at(2026, 11, 10))
        assertEquals(0.0, result.livingBurn, 0.0)
        assertEquals(0.0, result.perDayAverage, 0.0)
    }

    @Test
    fun syncStateIsUpToDateOrWaitingWithTheCount() {
        assertEquals(WidgetSyncState.UpToDate, useCase(emptyList(), emptyList(), 0, at(2026, 11, 10)).syncState)
        assertEquals(WidgetSyncState.Waiting(3), useCase(emptyList(), emptyList(), 3, at(2026, 11, 10)).syncState)
    }

    @Test
    fun theSharedDailyAverageRuleIsZeroWithoutBurnOrDays() {
        assertEquals(0.0, LivingBurn.dailyAverage(0.0, 5), 0.0)
        assertEquals(0.0, LivingBurn.dailyAverage(100.0, 0), 0.0)
        assertEquals(25.0, LivingBurn.dailyAverage(100.0, 4), 0.0)
    }
}
