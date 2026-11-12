package com.financeautomation.core.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeGuardTest {
    private val now = 1_800_000_000_000L

    @Test
    fun pastIsNotFuture() = assertFalse(isFuture(now - 86_400_000L, now))

    @Test
    fun nowIsNotFuture() = assertFalse(isFuture(now, now))

    @Test
    fun withinToleranceIsNotFuture() = assertFalse(isFuture(now + 4 * 60_000L, now))

    @Test
    fun exactlyAtToleranceIsNotFuture() = assertFalse(isFuture(now + 5 * 60_000L, now))

    @Test
    fun beyondToleranceIsFuture() = assertTrue(isFuture(now + 5 * 60_000L + 1, now))

    @Test
    fun tomorrowIsFuture() = assertTrue(isFuture(now + 86_400_000L, now))

    @Test
    fun customToleranceIsHonoured() {
        assertTrue(isFuture(now + 1_000L, now, toleranceMillis = 0L))
        assertFalse(isFuture(now + 1_000L, now, toleranceMillis = 2_000L))
    }
}
