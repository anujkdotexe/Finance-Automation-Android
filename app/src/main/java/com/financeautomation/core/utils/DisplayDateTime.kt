package com.financeautomation.core.utils

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one place that turns a time stamp into text for the screen. Every format uses Asia/Kolkata, the zone of the
 * ledger, whatever time zone the phone is set to, so a payment made at 23:50 on the last day of a month is shown on
 * that day on every screen.
 */
object DisplayDateTime {

    private val ZONE = DateSerialUtils.SHEET_ZONE

    private fun fmt(ts: Long, pattern: String, locale: Locale): String =
        DateTimeFormatter.ofPattern(pattern, locale).withZone(ZONE).format(Instant.ofEpochMilli(ts))

    /** 11:50 PM */
    fun time(ts: Long, locale: Locale = Locale.getDefault()): String = fmt(ts, "hh:mm a", locale)

    /** 11:50 PM without a leading zero */
    fun timeShort(ts: Long, locale: Locale = Locale.getDefault()): String = fmt(ts, "h:mm a", locale)

    /** 30 Nov */
    fun dayMonth(ts: Long, locale: Locale = Locale.getDefault()): String = fmt(ts, "d MMM", locale)

    /** 30 Nov, 11:50 PM */
    fun dayMonthTime(ts: Long, locale: Locale = Locale.getDefault()): String = fmt(ts, "d MMM, h:mm a", locale)

    /** Monday, 30 November 2026, 11:50 PM */
    fun full(ts: Long, locale: Locale = Locale.getDefault()): String = fmt(ts, "EEEE, d MMMM yyyy, h:mm a", locale)

    /** 23:50:00 */
    fun clock24(ts: Long): String = fmt(ts, "HH:mm:ss", Locale.US)

    /** 2026-11-30 23:50:00 */
    fun dateTimeIso(ts: Long): String = fmt(ts, "yyyy-MM-dd HH:mm:ss", Locale.US)

    /** A calendar date given as an epoch day, for example the due date of a subscription: 30 Nov */
    fun epochDay(epochDay: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("d MMM", locale).format(LocalDate.ofEpochDay(epochDay))

    /** Today as an epoch day in Asia/Kolkata. */
    fun todayEpochDay(nowMillis: Long = System.currentTimeMillis()): Long =
        Instant.ofEpochMilli(nowMillis).atZone(ZONE).toLocalDate().toEpochDay()

    fun isSameDay(a: Long, b: Long): Boolean =
        Instant.ofEpochMilli(a).atZone(ZONE).toLocalDate() == Instant.ofEpochMilli(b).atZone(ZONE).toLocalDate()
}
