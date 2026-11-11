package com.financeautomation.core.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPersistenceAndReload() {
        val folder = tempFolder.newFolder("diag_test_1")
        Diagnostics.initialize(folder)
        Diagnostics.clear()

        Diagnostics.record("TestTag1", "Reason 1")
        Diagnostics.record("TestTag2", "Reason 2")

        Diagnostics.flush()

        val persistedFile = File(folder, "diagnostics.json")
        assertTrue(persistedFile.exists())
        val text = persistedFile.readText()
        assertTrue(text.contains("TestTag1"))
        assertTrue(text.contains("TestTag2"))

        // Simulate app restart by clearing and re-initializing from same folder
        Diagnostics.initialize(folder)
        val reloaded = Diagnostics.getRecords()
        assertEquals(2, reloaded.size)
        assertEquals("TestTag1", reloaded[0].tag)
        assertEquals("Reason 1", reloaded[0].reason)
        assertEquals("TestTag2", reloaded[1].tag)
        assertEquals("Reason 2", reloaded[1].reason)
    }

    @Test
    fun testCapacityCappedAt50() {
        val folder = tempFolder.newFolder("diag_test_2")
        Diagnostics.initialize(folder)
        Diagnostics.clear()

        for (i in 1..65) {
            Diagnostics.record("Tag$i", "Reason $i")
        }

        Diagnostics.flush()

        val inMemory = Diagnostics.getRecords()
        assertEquals(Diagnostics.MAX_CAPACITY, inMemory.size)
        // First record should be Tag16 (1..65 capped to last 50)
        assertEquals("Tag16", inMemory.first().tag)
        assertEquals("Tag65", inMemory.last().tag)

        // Verify reloaded from disk also has exactly 50
        Diagnostics.initialize(folder)
        val reloaded = Diagnostics.getRecords()
        assertEquals(Diagnostics.MAX_CAPACITY, reloaded.size)
        assertEquals("Tag16", reloaded.first().tag)
        assertEquals("Tag65", reloaded.last().tag)
    }

    @Test
    fun testClearRemovesFromMemoryAndDisk() {
        val folder = tempFolder.newFolder("diag_test_3")
        Diagnostics.initialize(folder)
        Diagnostics.clear()

        Diagnostics.record("TagA", "Reason A")
        Diagnostics.flush()
        assertEquals(1, Diagnostics.getRecords().size)

        Diagnostics.clear()
        Diagnostics.flush()
        assertEquals(0, Diagnostics.getRecords().size)

        Diagnostics.initialize(folder)
        assertEquals(0, Diagnostics.getRecords().size)
    }
}
