package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Transaction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pure Kotlin date range formatter for travel ledgers (Stitch v3 Trips).
 * Derives trip date span from first and last transaction timestamps:
 * - Empty trip: "Active travel ledger" or "Past travel ledger"
 * - Active trip: "Started <date>"
 * - Past trip on single day: "<date>"
 * - Past trip across multiple days/months: "<start> - <end>"
 */
object TripDateRangeFormatter {

    fun format(transactions: List<Transaction>, isActive: Boolean): String {
        if (transactions.isEmpty()) {
            return if (isActive) "Active travel ledger" else "Past travel ledger"
        }
        val first = transactions.minOf { it.timestamp }
        val last = transactions.maxOf { it.timestamp }
        val sdf = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
        val sdfShort = SimpleDateFormat("d MMM", Locale.ENGLISH)

        return if (isActive) {
            "Started ${sdf.format(Date(first))}"
        } else {
            val cal1 = Calendar.getInstance().apply { timeInMillis = first }
            val cal2 = Calendar.getInstance().apply { timeInMillis = last }
            if (cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
            ) {
                sdf.format(Date(first))
            } else if (cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)) {
                "${sdfShort.format(Date(first))} - ${sdf.format(Date(last))}"
            } else {
                "${sdf.format(Date(first))} - ${sdf.format(Date(last))}"
            }
        }
    }
}
