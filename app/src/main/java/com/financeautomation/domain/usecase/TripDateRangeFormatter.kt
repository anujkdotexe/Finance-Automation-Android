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

    fun format(
        transactions: List<Transaction>,
        isActive: Boolean,
        startDateEpochDay: Long? = null,
        endDateEpochDay: Long? = null
    ): String {
        val sdf = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        val sdfShort = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

        val day1: LocalDate? = startDateEpochDay?.let { LocalDate.ofEpochDay(it) }
            ?: transactions.minOfOrNull { it.timestamp }?.let {
                Instant.ofEpochMilli(it).atZone(ZONE).toLocalDate()
            }

        val day2: LocalDate? = endDateEpochDay?.let { LocalDate.ofEpochDay(it) }
            ?: transactions.maxOfOrNull { it.timestamp }?.let {
                Instant.ofEpochMilli(it).atZone(ZONE).toLocalDate()
            }

        if (day1 == null) {
            return if (isActive) "Active travel ledger" else "Past travel ledger"
        }

        return if (isActive) {
            "Started ${sdf.format(day1)}"
        } else {
            val end = day2 ?: day1
            if (day1 == end) {
                sdf.format(day1)
            } else if (day1.year == end.year) {
                "${sdfShort.format(day1)} - ${sdf.format(end)}"
            } else {
                "${sdf.format(day1)} - ${sdf.format(end)}"
            }
        }
    }

    private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
}
