package com.financeautomation.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Raised when the encrypted database cannot be opened right now (for example the Keystore is not ready).
 * Callers must show a "Storage unavailable, try again" state or fail the background write clearly.
 * The app never falls back to an in-memory database, because saves there would be lost.
 */
class StorageUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Reads the database passphrase with one short retry and no fallback. */
object PassphraseAccess {
    fun obtain(provider: () -> ByteArray, pause: () -> Unit): ByteArray {
        try {
            return provider()
        } catch (first: Exception) {
            pause()
            try {
                return provider()
            } catch (second: Exception) {
                throw StorageUnavailableException("The secure key store is not available. Try again.", second)
            }
        }
    }
}

/**
 * Creates a value on first successful access. A failed creation is never cached, so the next call retries.
 */
class RetriableProvider<T : Any>(private val create: suspend () -> T) {
    private val mutex = Mutex()

    @Volatile
    private var value: T? = null

    suspend fun get(): T {
        value?.let { return it }
        return mutex.withLock {
            value ?: create().also { value = it }
        }
    }
}

/** Keeps only the newest unreadable database copies (named name.unreadable_timestamp). */
object UnreadableDatabaseRetention {
    fun prune(directory: File, dbName: String, keep: Int = 2): Int {
        val prefix = "$dbName.unreadable_"
        val files = directory.listFiles { f -> f.isFile && f.name.startsWith(prefix) }.orEmpty()
        val sorted = files.sortedByDescending { it.name.removePrefix(prefix).toLongOrNull() ?: 0L }
        var deleted = 0
        sorted.drop(keep).forEach { if (it.delete()) deleted++ }
        return deleted
    }
}

/** One-time flag telling the user that local data was reset. Stored as a marker file. */
class StorageResetNotice(private val marker: File) {
    fun mark() {
        try {
            marker.parentFile?.mkdirs()
            marker.writeText("1")
        } catch (_: Exception) {
            // The notice is best effort
        }
    }

    /** Returns true once after [mark], then clears the marker. */
    fun consume(): Boolean {
        if (!marker.exists()) return false
        marker.delete()
        return true
    }
}
