package com.financeautomation.domain

import com.financeautomation.core.utils.DeviceSnapshot
import com.financeautomation.domain.model.CompatibilityReportBuilder
import com.financeautomation.domain.model.CompatibilityReportData
import com.financeautomation.domain.model.DeviceHeaderInfo
import com.financeautomation.domain.model.FunnelStageCount
import com.financeautomation.domain.model.HealthRowReport
import com.financeautomation.domain.model.OemCandidateReport
import com.financeautomation.domain.model.OemRouteReport
import com.financeautomation.domain.model.TrailEventReportItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityReportBuilderTest {

    @Test
    fun buildMarkdown_includesStandardSectionsAndSnapshot() {
        val snapshotMask = (1L shl DeviceSnapshot.BIT_LISTENER_ACCESS) or
                (1L shl DeviceSnapshot.BIT_BATTERY_UNRESTRICTED)

        val data = CompatibilityReportData(
            header = DeviceHeaderInfo(
                appVersion = "1.0.0",
                buildType = "beta",
                manufacturer = "Samsung",
                brand = "samsung",
                model = "SM-S928B",
                androidSdk = 34,
                romDisplay = "UP1A.231005.007"
            ),
            healthRows = listOf(
                HealthRowReport("Notification Access", "OK"),
                HealthRowReport("Display Over Apps", "MISSING")
            ),
            oemRoute = OemRouteReport(
                routeId = "samsung_standard",
                candidates = listOf(
                    OemCandidateReport("com.samsung.android.sm", true, "Opened battery")
                )
            ),
            lastCrash = null,
            exitHistory = emptyList(),
            diagnostics = emptyList(),
            funnelCounts = listOf(
                FunnelStageCount("ALERT", "WHITELISTED_SEEN", 5),
                FunnelStageCount("CAPTURE", "CREATED_WAITING", 3),
                FunnelStageCount("PROMPT", "USER_SAVED", 2)
            ),
            recentTrail = listOf(
                TrailEventReportItem(1700000000000L, "DEVICE", "SNAPSHOT", snapshotMask, 10L),
                TrailEventReportItem(1700000001000L, "ALERT", "WHITELISTED_SEEN", null, null)
            ),
            discoveryScan = emptyList(),
            lastHeartbeatTime = 1700000005000L,
            todayDroppedNonWhitelistedCount = 12L,
            userNote = "Testing missed payment note"
        )

        val report = CompatibilityReportBuilder.buildMarkdown(data)

        // Positive assertions
        assertTrue(report.contains("# Finance Automation - Compatibility Report"))
        assertTrue(report.contains("SM-S928B"))
        assertTrue(report.contains("Testing missed payment note"))
        assertTrue(report.contains("Notification Access: OK"))
        assertTrue(report.contains("Latest Device Snapshot"))
        assertTrue(report.contains("Listener Access: Yes"))
        assertTrue(report.contains("Overlay Permission: No"))
        assertTrue(report.contains("Battery Optimization Unrestricted: Yes"))
        assertTrue(report.contains("App Standby Bucket: ACTIVE (10)"))
        assertTrue(report.contains("Funnel Summary: captures=3, saved=2"))
        assertTrue(report.contains("Today Filtered Non-Whitelisted Count: 12"))

        // Negative assertions: Telemetry report must never leak financial amounts or currencies
        assertFalse(report.contains("₹"))
        assertFalse(report.contains("Rs."))
        assertFalse(report.contains("INR"))
    }

    @Test
    fun userNoteIsCleanedAndCappedAtMaxNoteLength() {
        val raw = "  Notice with control chars \u200E and excessive length " + "a".repeat(200)
        val cleaned = com.financeautomation.core.utils.InputPolicy.cleanNote(raw)
            .take(com.financeautomation.core.utils.InputPolicy.MAX_NOTE_LENGTH)
        assertEquals(com.financeautomation.core.utils.InputPolicy.MAX_NOTE_LENGTH, cleaned.length)
        assertFalse(cleaned.contains("\u200E"))
    }
}

