package com.financeautomation.testutil

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A [Clock] whose time a test sets directly. */
class MutableTestClock(private var current: Instant) : Clock() {
    fun set(instant: Instant) {
        current = instant
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
}
