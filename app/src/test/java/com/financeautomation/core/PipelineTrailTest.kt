package com.financeautomation.core

import com.financeautomation.core.utils.PipelineTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTrailTest {

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
        val initial = PipelineTrail.getTodayDroppedNonWhitelistedCount()
        PipelineTrail.recordDroppedNotWhitelisted()
        val updated = PipelineTrail.getTodayDroppedNonWhitelistedCount()

        assertEquals(initial + 1, updated)
    }
}

