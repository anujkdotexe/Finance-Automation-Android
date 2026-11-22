package com.financeautomation.core.utils

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue

/**
 * Data model for user-initiated problem reports stored in Firebase Firestore.
 * Matches firebase/firestore.rules exactly.
 */
data class ProblemReportPayload(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val versionCode: Int,
    val deviceModel: String,
    val androidSdk: Int,
    val installId: String,
    val screen: String,
    val reasonCode: String,
    val counters: String?,
    val text: String
) {
    /**
     * Converts to Firestore document data map.
     * Enforces schema constraints and drops any disallowed field.
     */
    fun toFirestoreData(isServerTimestamp: Boolean = true): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map[ProblemReportFields.FIELD_SCHEMA_VERSION] = 1
        if (isServerTimestamp) {
            map[ProblemReportFields.FIELD_CREATED_AT] = FieldValue.serverTimestamp()
        } else {
            map[ProblemReportFields.FIELD_CREATED_AT] = Timestamp.now()
        }
        val safeAppVer = appVersion.trim().take(32).ifEmpty { "unknown" }
        map[ProblemReportFields.FIELD_APP_VERSION] = safeAppVer
        map[ProblemReportFields.FIELD_VERSION_CODE] = versionCode.coerceIn(1, 999999)
        val safeModel = deviceModel.trim().take(64).ifEmpty { "Android" }
        map[ProblemReportFields.FIELD_DEVICE_MODEL] = safeModel
        map[ProblemReportFields.FIELD_ANDROID_SDK] = androidSdk.coerceIn(28, 99)
        map[ProblemReportFields.FIELD_INSTALL_ID] = installId.trim().take(36)
        val safeScreen = screen.trim().take(64).ifEmpty { "Diagnostics" }
        map[ProblemReportFields.FIELD_SCREEN] = safeScreen
        val safeReason = reasonCode.trim().take(64).ifEmpty { "user_report" }
        map[ProblemReportFields.FIELD_REASON_CODE] = safeReason

        if (!counters.isNullOrBlank()) {
            map[ProblemReportFields.FIELD_COUNTERS] = counters.trim().take(1500)
        }
        val scrubbedText = PrivacyScrubber.scrubWithoutLengthCap(ProblemReportFields.maskForbiddenWords(text)).take(2000)
        map[ProblemReportFields.FIELD_TEXT] = scrubbedText

        // Strict filter to only allowed fields
        return map.filterKeys { it in ProblemReportFields.ALLOWED_FIELDS }
    }

    /**
     * Formats human-readable plaintext representation for preview and export.
     */
    fun toPlainText(): String {
        val sb = StringBuilder()
        sb.appendLine("Finance Automation Problem Report")
        sb.appendLine("--------------------------------")
        sb.appendLine("Schema Version: $schemaVersion")
        sb.appendLine("App Version: $appVersion ($versionCode)")
        sb.appendLine("Device Model: $deviceModel")
        sb.appendLine("Android SDK: $androidSdk")
        sb.appendLine("Install ID: $installId")
        sb.appendLine("Screen: $screen")
        sb.appendLine("Reason Code: $reasonCode")
        if (!counters.isNullOrBlank()) {
            sb.appendLine("Diagnostics Counters:")
            sb.appendLine(counters)
        }
        sb.appendLine("User Notes:")
        sb.appendLine(PrivacyScrubber.scrubWithoutLengthCap(ProblemReportFields.maskForbiddenWords(text)).take(2000).ifEmpty { "(none)" })
        sb.appendLine("--------------------------------")
        return sb.toString()
    }
}
