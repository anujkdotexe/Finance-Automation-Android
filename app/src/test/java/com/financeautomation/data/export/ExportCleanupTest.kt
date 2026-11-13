package com.financeautomation.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** X-3: old CSV exports are removed when the app starts, not only when the next export is written. */
class ExportCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun startupCleanupDeletesOnlyExpiredExportFiles() {
        val cache = tmp.newFolder("cache")
        val exports = File(cache, CsvExportWriter.EXPORT_DIR).apply { mkdirs() }
        val old = File(exports, "finance_2026_10.csv").apply { writeText("a"); setLastModified(1_000L) }
        val fresh = File(exports, "finance_2026_11.csv").apply { writeText("b"); setLastModified(9_000_000L) }

        val deleted = CsvExportWriter.purgeStaleExports(cache, clock = { 9_000_000L + 60_000L })

        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun cleanupWithoutAnExportFolderDoesNothing() {
        assertEquals(0, CsvExportWriter.purgeStaleExports(tmp.newFolder("empty")))
    }
}
