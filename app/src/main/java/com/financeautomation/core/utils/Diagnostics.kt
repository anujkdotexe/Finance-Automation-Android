package com.financeautomation.core.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Lightweight diagnostics recorder for unexpected errors and lifecycle anomalies.
 * Stores a small capped buffer of 50 redacted records in memory and persists to disk (filesDir/diagnostics.json).
 * Never stores or logs raw notification text, response bodies, tokens, amounts, or secrets.
 */
object Diagnostics {

    const val MAX_CAPACITY: Int = 50

    @Serializable
    data class Record(
        val timestamp: Long,
        val tag: String,
        val reason: String
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val records = ConcurrentLinkedDeque<Record>()
    private val diskExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiagnosticsWriter").apply { isDaemon = true }
    }
    @Volatile
    private var storageDir: File? = null

    fun initialize(context: Context) {
        initialize(context.filesDir)
    }

    @Synchronized
    fun initialize(dir: File) {
        storageDir = dir
        val file = File(dir, "diagnostics.json")
        if (file.exists()) {
            try {
                val content = file.readText()
                if (content.isNotBlank()) {
                    val loaded = json.decodeFromString<List<Record>>(content)
                    records.clear()
                    loaded.takeLast(MAX_CAPACITY).forEach { records.addLast(it) }
                }
            } catch (_: Exception) {
                // Ignore corrupted or unreadable diagnostic file
            }
        }
    }

    fun record(tag: String, reason: String) {
        val entry = Record(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            reason = reason
        )
        records.addLast(entry)
        while (records.size > MAX_CAPACITY) {
            records.pollFirst()
        }
        persistAsync()
    }

    fun getRecords(): List<Record> {
        return records.toList()
    }

    fun clear() {
        records.clear()
        persistAsync()
    }

    /**
     * Blocks until all pending asynchronous disk writes finish. Used in tests and lifecycle shutdown.
     */
    fun flush(timeoutMs: Long = 2000L) {
        try {
            diskExecutor.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    private fun persistAsync() {
        val dir = storageDir ?: return
        val currentRecords = records.toList()
        diskExecutor.execute {
            try {
                val targetFile = File(dir, "diagnostics.json")
                val tempFile = File(dir, "diagnostics.json.tmp")
                val text = json.encodeToString(currentRecords)
                FileOutputStream(tempFile).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (_: Exception) {
                // Non-fatal background write failure
            }
        }
    }
}
