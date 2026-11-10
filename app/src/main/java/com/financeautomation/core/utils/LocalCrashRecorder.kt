package com.financeautomation.core.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LocalCrashEntry(
    val timestamp: Long,
    val exceptionClass: String,
    val appFrames: List<String>,
    val appVersion: String
)

@Serializable
data class ProcessExitEntry(
    val timestamp: Long,
    val reasonCode: Int,
    val reasonDescription: String,
    val importance: Int
)

/**
 * Manages local, privacy-safe crash and exit records (Phase 9.3).
 * Uncaught exceptions record strictly class name, app stack frames, and timestamp - never message text or user input.
 * Process exit reasons capture system kills (API 30+) to diagnose aggressive OEM background termination.
 */
object LocalCrashRecorder {

    private const val PREFS_NAME = "local_crash_records"
    private const val KEY_LAST_CRASH = "last_crash_json"
    private const val KEY_EXIT_ENTRIES = "exit_entries_json"
    private const val APP_PACKAGE_PREFIX = "com.financeautomation"

    private val json = Json { ignoreUnknownKeys = true }
    private var isInitialized = false

    fun install(context: Context, appVersion: String) {
        if (isInitialized) return
        isInitialized = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(context, throwable, appVersion)
            } catch (e: Exception) {
                // Fail silently without disrupting crash propagation
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        readAndStoreExitReasons(context)
    }

    private fun recordCrash(context: Context, throwable: Throwable, appVersion: String) {
        val appFrames = throwable.stackTrace
            .filter { it.className.startsWith(APP_PACKAGE_PREFIX) }
            .map { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            .take(15)

        val entry = LocalCrashEntry(
            timestamp = System.currentTimeMillis(),
            exceptionClass = throwable.javaClass.name,
            appFrames = appFrames,
            appVersion = appVersion
        )

        val prefs = getPrefs(context)
        // Synchronous commit required in uncaught exception handler before process termination
        @SuppressLint("ApplySharedPref")
        prefs.edit().putString(KEY_LAST_CRASH, json.encodeToString(entry)).commit()
    }

    fun readLastCrash(context: Context): LocalCrashEntry? {
        val raw = getPrefs(context).getString(KEY_LAST_CRASH, null) ?: return null
        return try {
            json.decodeFromString<LocalCrashEntry>(raw)
        } catch (e: Exception) {
            null
        }
    }

    fun readAndStoreExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val reasons: List<ApplicationExitInfo> = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val entries = reasons.map { info ->
                ProcessExitEntry(
                    timestamp = info.timestamp,
                    reasonCode = info.reason,
                    reasonDescription = mapReasonCode(info.reason),
                    importance = info.importance
                )
            }
            getPrefs(context).edit().putString(KEY_EXIT_ENTRIES, json.encodeToString(entries)).apply()
        } catch (e: Exception) {
            Diagnostics.record("LocalCrashRecorder", "readExitReasons: ${e.javaClass.simpleName}")
        }
    }

    fun readExitReasons(context: Context): List<ProcessExitEntry> {
        val raw = getPrefs(context).getString(KEY_EXIT_ENTRIES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ProcessExitEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun mapReasonCode(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
            else -> "REASON_UNKNOWN($reason)"
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

