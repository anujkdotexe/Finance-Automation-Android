package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for TripDateRangeFormatter (Stitch v3 Trips):
 * - Empty trip (active vs past)
 * - Active trip with expenses ("Started <date>")
 * - Past trip on a single day ("<date>")
 * - Past trip spanning two months ("<start> - <end>")
 */
class TripDateRangeFormatterTest {

    private fun createTx(timestamp: Long): Transaction = Transaction(
        id = "tx_${timestamp}",
        timestamp = timestamp,
        dateSerial = 46300,
        timeSerial = 0.5,
        totalAmount = 500.0,
        myShare = 500.0,
        owedToMe = 0.0,
        merchant = "Cafe",
        category = "Food & Drink",
        account = "UPI",
        tripTag = "Goa2026",
        companions = "Personal",
        status = TransactionStatus.PERSONAL
    )

    private fun makeTimestamp(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    @Test
    fun emptyTripReturnsStandardLedgerLabels() {
        val activeResult = TripDateRangeFormatter.format(emptyList(), isActive = true)
        assertEquals("Active travel ledger", activeResult)

        val pastResult = TripDateRangeFormatter.format(emptyList(), isActive = false)
        assertEquals("Past travel ledger", pastResult)
    }

    @Test
    fun activeTripWithTransactionsShowsStartedDate() {
        val t1 = makeTimestamp(2026, Calendar.OCTOBER, 10)
        val t2 = makeTimestamp(2026, Calendar.OCTOBER, 15)
        val txs = listOf(createTx(t1), createTx(t2))

        val result = TripDateRangeFormatter.format(txs, isActive = true)
        assertEquals("Started 10 Oct 2026", result)
    }

    @Test
    fun pastTripOnSingleDayShowsSingleDate() {
        val t1 = makeTimestamp(2026, Calendar.OCTOBER, 12, 10)
        val t2 = makeTimestamp(2026, Calendar.OCTOBER, 12, 18)
        val txs = listOf(createTx(t1), createTx(t2))

        val result = TripDateRangeFormatter.format(txs, isActive = false)
        assertEquals("12 Oct 2026", result)
    }

    @Test
    fun pastTripSpanningTwoMonthsShowsFullDateRange() {
        val t1 = makeTimestamp(2026, Calendar.OCTOBER, 25)
        val t2 = makeTimestamp(2026, Calendar.NOVEMBER, 5)
        val txs = listOf(createTx(t1), createTx(t2))

        val result = TripDateRangeFormatter.format(txs, isActive = false)
        assertEquals("25 Oct - 5 Nov 2026", result)
    }
}
