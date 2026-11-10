package com.financeautomation.core.utils

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * Google Sheets date and time serial converter utilities.
 *
 * Date Serial: Integer count of days elapsed since December 30, 1899.
 * Time Serial: Fractional 24h day serial with second precision ((hour*3600 + min*60 + sec) / 86400.0).
 * Integer Seconds: Total elapsed seconds in day (hour*3600 + min*60 + sec) for deterministic orphan matching.
 */
object DateSerialUtils {

    val SHEETS_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    const val SECONDS_PER_DAY: Double = 86400.0

    /**
     * Converts a [LocalDate] to an integer Google Sheets date serial.
     */
    fun toDateSerial(date: LocalDate): Int {
        return ChronoUnit.DAYS.between(SHEETS_EPOCH, date).toInt()
    }

    /**
     * Restores a [LocalDate] from an integer Google Sheets date serial.
     */
    fun fromDateSerial(serial: Int): LocalDate {
        return SHEETS_EPOCH.plusDays(serial.toLong())
    }

    /**
     * Converts a [LocalTime] to a fractional 24h day serial.
     * By default preserves second precision. Set [includeNanos] to true for sub-second precision.
     */
    fun toTimeSerial(time: LocalTime, includeNanos: Boolean = false): Double {
        val seconds = time.hour * 3600.0 + time.minute * 60.0 + time.second
        val subSecond = if (includeNanos) time.nano / 1_000_000_000.0 else 0.0
        return (seconds + subSecond) / SECONDS_PER_DAY
    }

    /**
     * Restores a [LocalTime] from a fractional 24h day serial.
     */
    fun fromTimeSerial(serial: Double): LocalTime {
        val totalSeconds = (serial * SECONDS_PER_DAY).roundToLong()
        val hours = ((totalSeconds / 3600) % 24).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        return LocalTime.of(hours, minutes, seconds)
    }

    /**
     * Converts a [LocalTime] to integer seconds since midnight.
     * Used for integer-second orphan reconciliation without floating-point mismatch.
     */
    fun toIntegerSeconds(time: LocalTime): Int {
        return time.hour * 3600 + time.minute * 60 + time.second
    }

    /**
     * Restores a [LocalTime] from integer seconds since midnight.
     */
    fun fromIntegerSeconds(totalSeconds: Int): LocalTime {
        val hours = (totalSeconds / 3600) % 24
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return LocalTime.of(hours, minutes, seconds)
    }

    /**
     * Converts epoch milliseconds to a [LocalDate] using the specified time zone.
     */
    fun toLocalDate(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
    }

    /**
     * Converts epoch milliseconds to a [LocalTime] using the specified time zone.
     */
    fun toLocalTime(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalTime {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalTime()
    }

    /**
     * Converts epoch milliseconds directly to an integer date serial.
     */
    fun toDateSerial(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Int {
        return toDateSerial(toLocalDate(epochMillis, zoneId))
    }

    /**
     * Converts epoch milliseconds directly to a fractional time serial.
     */
    fun toTimeSerial(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Double {
        return toTimeSerial(toLocalTime(epochMillis, zoneId))
    }
}
