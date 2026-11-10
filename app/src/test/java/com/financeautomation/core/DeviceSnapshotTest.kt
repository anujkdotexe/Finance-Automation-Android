package com.financeautomation.core

import com.financeautomation.core.utils.DeviceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSnapshotTest {

    @Test
    fun isBitSet_detectsIndividualBits() {
        val mask = (1L shl DeviceSnapshot.BIT_LISTENER_ACCESS) or
                (1L shl DeviceSnapshot.BIT_POST_NOTIFICATIONS) or
                (1L shl DeviceSnapshot.BIT_DOZE_STATE)

        assertTrue(DeviceSnapshot.isBitSet(mask, DeviceSnapshot.BIT_LISTENER_ACCESS))
        assertFalse(DeviceSnapshot.isBitSet(mask, DeviceSnapshot.BIT_OVERLAY_PERMISSION))
        assertFalse(DeviceSnapshot.isBitSet(mask, DeviceSnapshot.BIT_USAGE_ACCESS))
        assertTrue(DeviceSnapshot.isBitSet(mask, DeviceSnapshot.BIT_POST_NOTIFICATIONS))
        assertFalse(DeviceSnapshot.isBitSet(mask, DeviceSnapshot.BIT_BATTERY_UNRESTRICTED))
        assertFalse(DeviceSnapshot.isBitSet(mask, DeviceSnapshot.BIT_BACKGROUND_RESTRICTED))
        assertTrue(DeviceSnapshot.isBitSet(mask, DeviceSnapshot.BIT_DOZE_STATE))
    }

    @Test
    fun decodeFlag_returnsYesAndNo() {
        val mask = 1L shl DeviceSnapshot.BIT_OVERLAY_PERMISSION
        assertEquals("Yes", DeviceSnapshot.decodeFlag(mask, DeviceSnapshot.BIT_OVERLAY_PERMISSION))
        assertEquals("No", DeviceSnapshot.decodeFlag(mask, DeviceSnapshot.BIT_LISTENER_ACCESS))
    }

    @Test
    fun decodeBucket_mapsKnownBuckets() {
        assertEquals("ACTIVE (10)", DeviceSnapshot.decodeBucket(10L))
        assertEquals("WORKING_SET (20)", DeviceSnapshot.decodeBucket(20L))
        assertEquals("FREQUENT (30)", DeviceSnapshot.decodeBucket(30L))
        assertEquals("RARE (40)", DeviceSnapshot.decodeBucket(40L))
        assertEquals("RESTRICTED (45)", DeviceSnapshot.decodeBucket(45L))
        assertEquals("NEVER (50)", DeviceSnapshot.decodeBucket(50L))
        assertEquals("UNKNOWN (0)", DeviceSnapshot.decodeBucket(0L))
        assertEquals("OTHER (99)", DeviceSnapshot.decodeBucket(99L))
    }
}

