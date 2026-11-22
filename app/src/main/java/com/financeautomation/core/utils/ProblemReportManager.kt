package com.financeautomation.core.utils

import android.content.Context
import android.os.Build
import com.financeautomation.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.TimeoutException

enum class ReportOutcome {
    SENT,
    ALREADY_SENT,
    MAYBE_SENT
}

/**
 * Manages user-initiated problem reports sent to Firebase Firestore.
 * Conforms strictly to AGENTS.md section 2B item 3 and firebase/firestore.rules.
 *
 * Strict Privacy Guarantees:
 * - A report is created only when the user explicitly taps Send after seeing the exact payload.
 * - Never sent in the background, never at startup, never automatically.
 * - Never contains transaction amounts, payees, account numbers, categories, friends, trips,
 *   spreadsheet IDs, email addresses, or message text.
 */
object ProblemReportManager {

    private const val PREFS_NAME = "problem_reports_config"
    private const val KEY_INSTALL_ID = "install_id"
    const val COLLECTION_NAME = "problem_reports"

    @Synchronized
    fun getOrCreateInstallId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_INSTALL_ID, null)
        if (id == null || id.length != 36) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        }
        return id
    }

    fun isFirebaseConfigured(context: Context): Boolean {
        return try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun buildCountersString(): String {
        val dropCounts = Diagnostics.getAllDropCounts()
        if (dropCounts.isEmpty()) return ""
        return dropCounts.entries
            .sortedByDescending { it.value }
            .take(20)
            .joinToString("; ") { "${it.key}=${it.value}" }
            .take(1500)
    }

    fun buildPayload(
        context: Context,
        screen: String = "Diagnostics",
        reasonCode: String = "user_report",
        userText: String = ""
    ): ProblemReportPayload {
        val installId = getOrCreateInstallId(context)
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val counters = buildCountersString().ifEmpty { null }
        return ProblemReportPayload(
            schemaVersion = 1,
            appVersion = BuildConfig.VERSION_NAME.ifEmpty { "1.0.0" },
            versionCode = BuildConfig.VERSION_CODE,
            deviceModel = deviceModel.ifEmpty { "Android Device" },
            androidSdk = Build.VERSION.SDK_INT,
            installId = installId,
            screen = screen,
            reasonCode = reasonCode,
            counters = counters,
            text = userText
        )
    }

    fun buildDiagnosticsExportReport(context: Context): String {
        val payload = buildPayload(
            context = context,
            screen = "Diagnostics",
            reasonCode = "export_logs",
            userText = ""
        )
        val sb = StringBuilder()
        sb.append(payload.toPlainText())
        sb.appendLine()
        sb.appendLine("Recent Diagnostics Events:")
        val records = Diagnostics.getRecords()
        if (records.isEmpty()) {
            sb.appendLine("(no recent events recorded)")
        } else {
            records.takeLast(25).forEach { rec ->
                val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(rec.timestamp))
                sb.appendLine("[$timeStr] [${rec.tag}] ${rec.reason}")
            }
        }
        return sb.toString()
    }

    const val SUBMIT_TIMEOUT_MILLIS = 15_000L

    private fun findFirestoreCode(throwable: Throwable?): FirebaseFirestoreException.Code? {
        var current = throwable
        while (current != null) {
            if (current is FirebaseFirestoreException) {
                return current.code
            }
            current = current.cause
        }
        return null
    }

    suspend fun submitReport(
        context: Context,
        payload: ProblemReportPayload,
        documentId: String = UUID.randomUUID().toString(),
        timeoutMillis: Long = SUBMIT_TIMEOUT_MILLIS,
        isRetry: Boolean = false,
        submitter: (suspend (String, Map<String, Any?>) -> Unit)? = null
    ): Result<ReportOutcome> = withContext(Dispatchers.IO) {
        if (!isFirebaseConfigured(context) && submitter == null) {
            return@withContext Result.failure(
                IllegalStateException("Firebase service is not configured on this device")
            )
        }

        try {
            withTimeout(timeoutMillis) {
                val firestoreData = payload.toFirestoreData(isServerTimestamp = true)
                if (submitter != null) {
                    submitter(documentId, firestoreData)
                } else {
                    val db = FirebaseFirestore.getInstance()
                    db.collection(COLLECTION_NAME).document(documentId).set(firestoreData).await()
                }
            }
            Diagnostics.record("ProblemReportManager", "Report submitted successfully: ${payload.reasonCode}")
            Result.success(ReportOutcome.SENT)
        } catch (e: TimeoutCancellationException) {
            val errorMsg = "Submission timed out after ${timeoutMillis / 1000} seconds. If your phone was offline, the report may still arrive later once connected."
            Diagnostics.record("ProblemReportManager", "Submission error: $errorMsg")
            Result.failure(TimeoutException(errorMsg))
        } catch (e: Exception) {
            val code = findFirestoreCode(e)
            val isAlreadyExists = code == FirebaseFirestoreException.Code.ALREADY_EXISTS
            val isPermissionDenied = code == FirebaseFirestoreException.Code.PERMISSION_DENIED

            if (isAlreadyExists) {
                Diagnostics.record("ProblemReportManager", "Report $documentId already exists, treated as already sent")
                Result.success(ReportOutcome.ALREADY_SENT)
            } else if (isRetry && isPermissionDenied) {
                val successMessage = "Report may already have been sent"
                Diagnostics.record("ProblemReportManager", "Report retry for $documentId received permission denied: $successMessage")
                Result.success(ReportOutcome.MAYBE_SENT)
            } else {
                val scrubbedError = PrivacyScrubber.scrub(e.message ?: e.javaClass.simpleName)
                Diagnostics.record("ProblemReportManager", "Submission error: $scrubbedError")
                Result.failure(e)
            }
        }
    }
}
