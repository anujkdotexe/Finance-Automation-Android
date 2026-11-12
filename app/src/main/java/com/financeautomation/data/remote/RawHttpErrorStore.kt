package com.financeautomation.data.remote

import com.financeautomation.BuildConfig
import com.financeautomation.core.utils.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Capped raw store for failed Google API calls.
 * Stores RESPONSE body only (cut to 4 KB each, max 20 entries) in a private JSON file off the main thread.
 * Enabled for beta and debug builds only. Provisioning and sheet-structure calls only.
 */
@Serializable
data class RawHttpErrorEntry(
    val timestamp: String,
    val endpointId: String,
    val httpStatus: Int,
    val responseBody: String
)

class RawHttpErrorStore(
    private val filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isDebugOrBeta: Boolean = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "beta"
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val storeFile by lazy { File(filesDir, "http_errors.json") }
    private val mutex = Mutex()
    private val memoryEntries = mutableListOf<RawHttpErrorEntry>()
    @Volatile
    private var isLoaded = false

    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_BODY_BYTES = 4096

        /** True when a failed response of this endpoint may be stored (see [GoogleEndpoint.BODY_ALLOWED]). */
        fun isStructureOrProvisioningCall(endpointId: String): Boolean {
            return GoogleEndpoint.BODY_ALLOWED.contains(endpointId)
        }

        /** Cuts [text] to at most [maxBytes] UTF-8 bytes without splitting a character. */
        internal fun cutToUtf8Bytes(text: String, maxBytes: Int): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return text
            var end = maxBytes
            // Step back over continuation bytes (10xxxxxx) so a multi-byte character is never split.
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
                end--
            }
            return String(bytes, 0, end, Charsets.UTF_8)
        }
    }

    suspend fun load(): List<RawHttpErrorEntry> = withContext(ioDispatcher) {
        mutex.withLock {
            if (!isLoaded) {
                loadInternalLocked()
            }
            memoryEntries.toList()
        }
    }

    suspend fun recordError(
        endpointId: String,
        httpStatus: Int,
        responseBody: String,
        isProvisioningOrStructure: Boolean = isStructureOrProvisioningCall(endpointId)
    ) = withContext(ioDispatcher) {
        // Never store body on release build
        if (!isDebugOrBeta) return@withContext

        // Never store body for transaction row calls
        if (!isProvisioningOrStructure) return@withContext

        // Cut body to 4 KB (4096 UTF-8 bytes, never splitting a character)
        val cutBody = cutToUtf8Bytes(responseBody, MAX_BODY_BYTES)

        val entry = RawHttpErrorEntry(
            timestamp = Instant.now().toString(),
            endpointId = endpointId,
            httpStatus = httpStatus,
            responseBody = cutBody
        )

        mutex.withLock {
            if (!isLoaded) {
                loadInternalLocked()
            }
            memoryEntries.add(entry)
            while (memoryEntries.size > MAX_ENTRIES) {
                memoryEntries.removeAt(0)
            }
            persistLocked()
        }
    }

    suspend fun getEntries(): List<RawHttpErrorEntry> = withContext(ioDispatcher) {
        mutex.withLock {
            if (!isLoaded) {
                loadInternalLocked()
            }
            memoryEntries.toList()
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            memoryEntries.clear()
            isLoaded = true
            val tmp = File(filesDir, "http_errors.json.tmp")
            if (tmp.exists()) tmp.delete()
            if (storeFile.exists()) storeFile.delete()
        }
    }

    private fun loadInternalLocked() {
        if (storeFile.exists()) {
            try {
                val text = storeFile.readText()
                val parsed = json.decodeFromString<List<RawHttpErrorEntry>>(text)
                memoryEntries.clear()
                memoryEntries.addAll(parsed.takeLast(MAX_ENTRIES))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.record("RawHttpErrorStore", "load failed: ${e.javaClass.simpleName}")
                memoryEntries.clear()
            }
        }
        isLoaded = true
    }

    private fun persistLocked() {
        try {
            val serialized = json.encodeToString(memoryEntries)
            val tmpFile = File(filesDir, "http_errors.json.tmp")
            tmpFile.writeText(serialized)
            if (!tmpFile.renameTo(storeFile)) {
                tmpFile.copyTo(storeFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.record("RawHttpErrorStore", "persist failed: ${e.javaClass.simpleName}")
        }
    }
}
