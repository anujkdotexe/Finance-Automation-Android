package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Transaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        val sdf = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        val sdfShort = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        // Dates are always shown in the sheet zone, whatever the device zone is
        val day1: LocalDate = Instant.ofEpochMilli(first).atZone(ZONE).toLocalDate()
        val day2: LocalDate = Instant.ofEpochMilli(last).atZone(ZONE).toLocalDate()

        return if (isActive) {
            "Started ${sdf.format(day1)}"
        } else {
            if (day1 == day2) {
                sdf.format(day1)
            } else if (day1.year == day2.year) {
                "${sdfShort.format(day1)} - ${sdf.format(day2)}"
            } else {
                "${sdf.format(day1)} - ${sdf.format(day2)}"
            }
        }
    }

    private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
}
