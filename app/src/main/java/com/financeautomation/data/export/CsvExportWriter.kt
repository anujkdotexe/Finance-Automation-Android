package com.financeautomation.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Plain file handling for CSV exports, kept free of Android types so it can be unit tested.
 * Files live in one folder; each export first deletes files older than [maxAgeMillis] (one hour by default).
 */
class ExportFileStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = ONE_HOUR_MILLIS
) {
    fun write(fileName: String, text: String): File {
        directory.mkdirs()
        purgeOld()
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(directory, safeName)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** Deletes files whose last modification is older than the maximum age. Returns how many were deleted. */
    fun purgeOld(): Int {
        val cutoff = clock() - maxAgeMillis
        var deleted = 0
        directory.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff && f.delete()) deleted++
        }
        return deleted
    }

    companion object {
        const val ONE_HOUR_MILLIS: Long = 60L * 60L * 1000L
    }
}

/**
 * Writes the CSV into the app cache folder "exports" and returns a content Uri through the FileProvider
 * declared in the manifest (not exported, read grants per share). Nothing is written outside the cache.
 */
class CsvExportWriter(private val context: Context) {

    fun write(year: Int, month: Int, csv: String): Uri {
        val store = ExportFileStore(File(context.cacheDir, EXPORT_DIR))
        val name = "finance_%04d_%02d.csv".format(year, month)
        val file = store.write(name, csv)
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    companion object {
        const val EXPORT_DIR = "exports"

        /** Deletes export files older than the retention time. Call at app start. Returns how many were deleted. */
        fun purgeStaleExports(cacheDir: File, clock: () -> Long = System::currentTimeMillis): Int {
            val dir = File(cacheDir, EXPORT_DIR)
            if (!dir.isDirectory) return 0
            return ExportFileStore(dir, clock).purgeOld()
        }

        fun authority(context: Context): String = "${context.packageName}.fileprovider"
    }
}
