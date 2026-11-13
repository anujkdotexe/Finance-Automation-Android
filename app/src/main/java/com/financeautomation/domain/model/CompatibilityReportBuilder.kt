package com.financeautomation.domain.model

import com.financeautomation.core.utils.DeviceSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceHeaderInfo(
    val appVersion: String,
    val buildType: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidSdk: Int,
    val romDisplay: String
)

data class HealthRowReport(
    val name: String,
    val status: String
)

data class OemCandidateReport(
    val target: String,
    val resolves: Boolean,
    val feedback: String? = null
)

data class OemRouteReport(
    val routeId: String,
    val candidates: List<OemCandidateReport>
)

data class CrashReportItem(
    val timestamp: Long,
    val exceptionClass: String,
    val frames: List<String>
)

data class ExitReportItem(
    val timestamp: Long,
    val reasonDescription: String,
    val importance: Int
)

data class DiagnosticReportItem(
    val timestamp: Long,
    val tag: String,
    val reason: String
)

data class TrailEventReportItem(
    val time: Long,
    val stage: String,
    val code: String,
    val metricA: Long? = null,
    val metricB: Long? = null
)

data class FunnelStageCount(
    val stage: String,
    val code: String,
    val count: Int
)

data class CompatibilityReportData(
    val header: DeviceHeaderInfo,
    val healthRows: List<HealthRowReport>,
    val oemRoute: OemRouteReport?,
    val lastCrash: CrashReportItem?,
    val exitHistory: List<ExitReportItem>,
    val diagnostics: List<DiagnosticReportItem>,
    val funnelCounts: List<FunnelStageCount>,
    val recentTrail: List<TrailEventReportItem>,
    val discoveryScan: List<String>,
    val lastHeartbeatTime: Long = 0L,
    val todayDroppedNonWhitelistedCount: Long = 0L,
    val userNote: String? = null,
    val rawGoogleErrors: List<com.financeautomation.data.remote.RawHttpErrorEntry> = emptyList()
)

/**
 * Pure domain builder that renders the privacy-safe Markdown compatibility report (Phase 9.4).
 * Strictly technical system metrics only; never incorporates financial data, amounts, payees, PII, or notification text.
 */
object CompatibilityReportBuilder {

    fun buildMarkdown(data: CompatibilityReportData): String {
        val sb = StringBuilder()
        sb.appendLine("# Finance Automation - Compatibility Report")
        sb.appendLine()

        // 1. Device Header
        sb.appendLine("## 1. Device & Build")
        sb.appendLine("- App Version: ${data.header.appVersion} (${data.header.buildType})")
        sb.appendLine("- Device: ${data.header.manufacturer} / ${data.header.brand} ${data.header.model}")
        sb.appendLine("- Android SDK: ${data.header.androidSdk} (${data.header.romDisplay})")
        sb.appendLine()

        // 2. User Note
        if (!data.userNote.isNullOrBlank()) {
            sb.appendLine("## 2. User Note")
            sb.appendLine("> ${data.userNote.trim()}")
            sb.appendLine()
        }

        // 3. Setup Health Status
        sb.appendLine("## 3. Setup Health")
        if (data.healthRows.isEmpty()) {
            sb.appendLine("No health items recorded.")
        } else {
            for (row in data.healthRows) {
                sb.appendLine("- ${row.name}: ${row.status}")
            }
        }
        sb.appendLine()

        // 4. OEM Background Routing
        sb.appendLine("## 4. OEM Background Route")
        if (data.oemRoute == null) {
            sb.appendLine("No OEM route active.")
        } else {
            sb.appendLine("- Active Route: ${data.oemRoute.routeId}")
            for (cand in data.oemRoute.candidates) {
                val fb = if (cand.feedback != null) " [Feedback: ${cand.feedback}]" else ""
                sb.appendLine("  - Target: ${cand.target} | Resolves: ${cand.resolves}$fb")
            }
        }
        sb.appendLine()

        // 5. Crash & Exit Records
        sb.appendLine("## 5. Local Crash & Exit History")
        if (data.lastCrash != null) {
            sb.appendLine("- Last Uncaught Crash: ${data.lastCrash.exceptionClass} at ${data.lastCrash.timestamp}")
            for (frame in data.lastCrash.frames) {
                sb.appendLine("    at $frame")
            }
        } else {
            sb.appendLine("- Last Uncaught Crash: None recorded")
        }

        if (data.exitHistory.isEmpty()) {
            sb.appendLine("- Process Exit History: None recorded")
        } else {
            sb.appendLine("- Process Exit History (${data.exitHistory.size}):")
            for (exit in data.exitHistory) {
                sb.appendLine("  - ${exit.reasonDescription} (importance ${exit.importance}) at ${exit.timestamp}")
            }
        }
        sb.appendLine()

        // 6. Daily Pipeline Funnel & Heartbeat
        sb.appendLine("## 6. Daily Pipeline Funnel & Heartbeat")
        val heartbeatStr = if (data.lastHeartbeatTime > 0L) {
            com.financeautomation.core.utils.DisplayDateTime.dateTimeIso(data.lastHeartbeatTime)
        } else {
            "Never"
        }
        sb.appendLine("- Last Listener Heartbeat: $heartbeatStr")
        sb.appendLine("- Today Filtered Non-Whitelisted Count: ${data.todayDroppedNonWhitelistedCount}")
        val capturesCount = data.funnelCounts.filter { it.code == "CREATED_WAITING" || it.code == "AWAITING_SUCCESS" }.sumOf { it.count }
        val savedCount = data.funnelCounts.filter { it.code == "USER_SAVED" }.sumOf { it.count }
        sb.appendLine("- Funnel Summary: captures=$capturesCount, saved=$savedCount")
        if (data.funnelCounts.isEmpty()) {
            sb.appendLine("- No funnel events recorded for today.")
        } else {
            for (item in data.funnelCounts) {
                sb.appendLine("- ${item.stage} / ${item.code}: ${item.count}")
            }
        }
        sb.appendLine()

        // 7. Recent Pipeline Trail Events
        sb.appendLine("## 7. Pipeline Trail Events (${data.recentTrail.size})")
        val latestSnapshot = data.recentTrail.firstOrNull { it.stage == "DEVICE" && it.code == "SNAPSHOT" && it.metricA != null }
        if (latestSnapshot != null) {
            val a = latestSnapshot.metricA ?: 0L
            val b = latestSnapshot.metricB ?: 0L
            sb.appendLine("### Latest Device Snapshot")
            sb.appendLine("- Listener Access: ${DeviceSnapshot.decodeFlag(a, DeviceSnapshot.BIT_LISTENER_ACCESS)}")
            sb.appendLine("- Overlay Permission: ${DeviceSnapshot.decodeFlag(a, DeviceSnapshot.BIT_OVERLAY_PERMISSION)}")
            sb.appendLine("- Usage Access: ${DeviceSnapshot.decodeFlag(a, DeviceSnapshot.BIT_USAGE_ACCESS)}")
            sb.appendLine("- Post Notifications: ${DeviceSnapshot.decodeFlag(a, DeviceSnapshot.BIT_POST_NOTIFICATIONS)}")
            sb.appendLine("- Battery Optimization Unrestricted: ${DeviceSnapshot.decodeFlag(a, DeviceSnapshot.BIT_BATTERY_UNRESTRICTED)}")
            sb.appendLine("- Background Restricted: ${DeviceSnapshot.decodeFlag(a, DeviceSnapshot.BIT_BACKGROUND_RESTRICTED)}")
            sb.appendLine("- Doze State Active: ${DeviceSnapshot.decodeFlag(a, DeviceSnapshot.BIT_DOZE_STATE)}")
            sb.appendLine("- App Standby Bucket: ${DeviceSnapshot.decodeBucket(b)}")
            sb.appendLine()
        }
        if (data.recentTrail.isEmpty()) {
            sb.appendLine("No recent trail events.")
        } else {
            for (event in data.recentTrail) {
                val metrics = listOfNotNull(
                    event.metricA?.let { "a=$it" },
                    event.metricB?.let { "b=$it" }
                ).joinToString(", ")
                val metricsStr = if (metrics.isNotEmpty()) " ($metrics)" else ""
                sb.appendLine("- [${event.time}] ${event.stage} -> ${event.code}$metricsStr")
            }
        }
        sb.appendLine()

        // 8. In-Memory Diagnostics
        sb.appendLine("## 8. Diagnostic Events (${data.diagnostics.size})")
        if (data.diagnostics.isEmpty()) {
            sb.appendLine("No diagnostic anomalies.")
        } else {
            for (diag in data.diagnostics) {
                sb.appendLine("- [${diag.timestamp}] ${diag.tag}: ${diag.reason}")
            }
        }
        sb.appendLine()

        // 9. Discovery Scan (Debug/Beta only)
        if (data.discoveryScan.isNotEmpty()) {
            sb.appendLine("## 9. Vendor Discovery Scan (${data.discoveryScan.size})")
            for (entry in data.discoveryScan) {
                sb.appendLine("- $entry")
            }
            sb.appendLine()
        }

        // 10. Raw Google API Errors (User Opt-in)
        if (data.rawGoogleErrors.isNotEmpty()) {
            sb.appendLine("## 10. Raw Google API Errors (${data.rawGoogleErrors.size})")
            for (err in data.rawGoogleErrors) {
                sb.appendLine("- [${err.timestamp}] ${err.endpointId} HTTP ${err.httpStatus}:")
                sb.appendLine("```json")
                sb.appendLine(err.responseBody)
                sb.appendLine("```")
            }
            sb.appendLine()
        }

        return sb.toString().trim()
    }
}

