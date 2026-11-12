package com.financeautomation.core.utils

/**
 * Rejects timestamps that lie in the future (product rule: no future-dated transactions).
 * A small clock tolerance absorbs minor device clock skew.
 */
object TimeGuardConstants {
    const val FUTURE_DATE_MESSAGE = "That date is in the future."
    const val DEFAULT_TOLERANCE_MILLIS = 5 * 60_000L
}

fun isFuture(
    timestampMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    toleranceMillis: Long = TimeGuardConstants.DEFAULT_TOLERANCE_MILLIS
): Boolean = timestampMillis > nowMillis + toleranceMillis
