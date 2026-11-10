package com.financeautomation.core

import android.content.SharedPreferences
import com.financeautomation.core.utils.PipelineTrail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PipelineTrailTest {

    @Before
    fun setUp() {
        PipelineTrail.resetForTesting()
    }

    @After
    fun tearDown() {
        PipelineTrail.resetForTesting()
    }

    @Test
    fun recordHeartbeat_updatesTimestamp() {
        val before = System.currentTimeMillis()
        PipelineTrail.recordHeartbeat()
        val after = System.currentTimeMillis()

        val heartbeat = PipelineTrail.getLastHeartbeatTime()
        assertTrue(heartbeat in before..after)
    }

    @Test
    fun recordDroppedNotWhitelisted_incrementsCounter() {
        PipelineTrail.todayProvider = { "2026-11-11" }
        val initial = PipelineTrail.getTodayDroppedNonWhitelistedCount()
        PipelineTrail.recordDroppedNotWhitelisted()
        val updated = PipelineTrail.getTodayDroppedNonWhitelistedCount()

        assertEquals(initial + 1, updated)
    }

    @Test
    fun recordDroppedNotWhitelisted_resetsToZeroWhenDayRollsOver() {
        // Day 1
        PipelineTrail.todayProvider = { "2026-11-11" }
        PipelineTrail.recordDroppedNotWhitelisted()
        PipelineTrail.recordDroppedNotWhitelisted()
        assertEquals(2L, PipelineTrail.getTodayDroppedNonWhitelistedCount())

        // Day 2 rollover
        PipelineTrail.todayProvider = { "2026-11-12" }
        assertEquals(0L, PipelineTrail.getTodayDroppedNonWhitelistedCount())
    }

    @Test
    fun initializeWithPrefs_persistedValueFromYesterdayDoesNotCarryOver() {
        val yesterdayPrefs = FakeSharedPreferences(
            mapOf(
                "current_day_key" to "2026-11-10",
                "dropped_not_whitelisted_count" to 42L,
                "last_heartbeat_time" to 123456L
            )
        )

        PipelineTrail.todayProvider = { "2026-11-11" }
        PipelineTrail.initializeWithPrefs(yesterdayPrefs, "2026-11-11")

        assertEquals(0L, PipelineTrail.getTodayDroppedNonWhitelistedCount())
        assertEquals(123456L, PipelineTrail.getLastHeartbeatTime())
    }

    @Test
    fun initializeWithPrefs_persistedValueFromTodayIsRestored() {
        val todayPrefs = FakeSharedPreferences(
            mapOf(
                "current_day_key" to "2026-11-11",
                "dropped_not_whitelisted_count" to 15L,
                "last_heartbeat_time" to 654321L
            )
        )

        PipelineTrail.todayProvider = { "2026-11-11" }
        PipelineTrail.initializeWithPrefs(todayPrefs, "2026-11-11")

        assertEquals(15L, PipelineTrail.getTodayDroppedNonWhitelistedCount())
        assertEquals(654321L, PipelineTrail.getLastHeartbeatTime())
    }

    private class FakeSharedPreferences(
        private val data: Map<String, Any>
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
