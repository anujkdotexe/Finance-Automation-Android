package com.financeautomation.core.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
    private val dropCounters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
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
        diskExecutor.execute {
            val file = File(dir, "diagnostics.json")
            if (file.exists()) {
                try {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                        val loaded = json.decodeFromString<List<Record>>(content)
                        synchronized(records) {
                            val inMemoryPending = records.toList()
                            records.clear()
                            val combined = (loaded + inMemoryPending).distinctBy {
                                Triple(it.timestamp, it.tag, it.reason)
                            }
                            combined.takeLast(MAX_CAPACITY).forEach { records.addLast(it) }
                        }
                    }
                } catch (e: IOException) {
                    Log.w("Diagnostics", "Failed to read diagnostics file: ${e.message}")
                } catch (e: SerializationException) {
                    Log.w("Diagnostics", "Failed to decode diagnostics file: ${e.message}")
                }
            }
        }
    }

    fun record(tag: String, reason: String) {
        val entry = Record(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            reason = PrivacyScrubber.scrub(reason)
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
        clearDropCounts()
        persistAsync()
    }

    /**
     * Records a drop event by reason code (counts only, never message text or secrets).
     */
    fun recordDrop(reason: String) {
        val scrubbedReason = PrivacyScrubber.scrub(reason)
        dropCounters.computeIfAbsent(scrubbedReason) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        record("DropCounter", scrubbedReason)
    }

    /**
     * Returns the number of times [reason] was dropped.
     */
    fun getDropCount(reason: String): Int {
        val scrubbedReason = PrivacyScrubber.scrub(reason)
        return dropCounters[scrubbedReason]?.get() ?: 0
    }

    /**
     * Returns all drop reason counts as a snapshot map.
     */
    fun getAllDropCounts(): Map<String, Int> {
        return dropCounters.mapValues { it.value.get() }
    }

    /**
     * Clears all drop counters.
     */
    fun clearDropCounts() {
        dropCounters.clear()
    }

    /**
     * Blocks until all pending asynchronous disk writes finish. Used in tests and lifecycle shutdown.
     */
    fun flush(timeoutMs: Long = 2000L) {
        try {
            diskExecutor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w("Diagnostics", "Diagnostics flush timed out: ${e.message}")
        } catch (e: Exception) {
            Log.w("Diagnostics", "Diagnostics flush interrupted: ${e.message}")
        }
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
            } catch (e: IOException) {
                Log.w("Diagnostics", "Failed to write diagnostics file: ${e.message}")
            } catch (e: SerializationException) {
                Log.w("Diagnostics", "Failed to serialize diagnostics records: ${e.message}")
            }
        }
    }
}
