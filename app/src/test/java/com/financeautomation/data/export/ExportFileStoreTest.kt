package com.financeautomation.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 10_000_000_000L

    @Test
    fun writesTheTextAsUtf8IntoTheExportFolder() {
        val dir = File(tmp.root, "exports")
        val file = ExportFileStore(dir, clock = { now }).write("finance_2026_11.csv", "a,b\n₹\n")

        assertEquals(dir, file.parentFile)
        assertEquals("a,b\n₹\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun deletesFilesOlderThanOneHourOnEachExport() {
        val dir = tmp.newFolder("exports")
        val old = File(dir, "old.csv").apply { writeText("x"); setLastModified(now - 61 * 60 * 1000L) }
        val fresh = File(dir, "fresh.csv").apply { writeText("x"); setLastModified(now - 59 * 60 * 1000L) }

        ExportFileStore(dir, clock = { now }).write("new.csv", "y")

        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(File(dir, "new.csv").exists())
    }

    @Test
    fun purgeReportsHowManyWereDeletedAndIgnoresSubfolders() {
        val dir = tmp.newFolder("exports")
        File(dir, "a.csv").apply { writeText("x"); setLastModified(0L) }
        File(dir, "b.csv").apply { writeText("x"); setLastModified(0L) }
        val sub = File(dir, "sub").apply { mkdirs(); setLastModified(0L) }

        assertEquals(2, ExportFileStore(dir, clock = { now }).purgeOld())
        assertTrue(sub.exists())
    }

    @Test
    fun fileNamesCannotEscapeTheFolder() {
        val dir = File(tmp.root, "exports")
        val file = ExportFileStore(dir, clock = { now }).write("../../evil name.csv", "x")

        assertEquals(dir, file.parentFile)
        assertEquals(".._.._evil_name.csv", file.name)
    }

    @Test
    fun manifestDeclaresANonExportedProviderRestrictedToExports() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val provider = manifest.substringAfter("<provider").substringBefore("</provider>")
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        val paths = File("src/main/res/xml/file_paths.xml").readText()
        assertTrue(paths.contains("<cache-path"))
        assertTrue(paths.contains("path=\"exports/\""))
        assertFalse(paths.contains("<files-path"))
        assertFalse(paths.contains("<external"))
    }
}
