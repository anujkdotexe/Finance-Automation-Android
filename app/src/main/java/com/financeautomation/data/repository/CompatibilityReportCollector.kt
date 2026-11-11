package com.financeautomation.data.repository

import android.content.Context
import android.os.Build
import com.financeautomation.BuildConfig
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.DiscoveryScanner
import com.financeautomation.core.utils.LocalCrashRecorder
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.data.local.dao.PipelineEventDao
import com.financeautomation.domain.model.CompatibilityReportBuilder
import com.financeautomation.domain.model.CompatibilityReportData
import com.financeautomation.domain.model.CrashReportItem
import com.financeautomation.domain.model.DeviceHeaderInfo
import com.financeautomation.domain.model.DiagnosticReportItem
import com.financeautomation.domain.model.ExitReportItem
import com.financeautomation.domain.model.FunnelStageCount
import com.financeautomation.domain.model.HealthRowReport
import com.financeautomation.domain.model.OemCandidateReport
import com.financeautomation.domain.model.OemRouteReport
import com.financeautomation.domain.model.TrailEventReportItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Collects runtime system telemetry and formats it via CompatibilityReportBuilder (Phase 9.4).
 */
object CompatibilityReportCollector {

    suspend fun collectReport(
        context: Context,
        pipelineEventDao: PipelineEventDao?,
        healthSummary: List<HealthRowReport> = emptyList(),
        candidateFeedback: Map<String, String> = emptyMap(),
        isMissedPaymentReport: Boolean = false,
        userNote: String? = null,
        includeGoogleErrors: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val header = DeviceHeaderInfo(
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            romDisplay = Build.DISPLAY
        )

        val activeRoute = OemRoutesRepository.findActiveRoute(context)
        val candidateResults = OemRoutesRepository.resolveCandidateIntents(context)
        val oemRouteReport = OemRouteReport(
            routeId = activeRoute.id,
            candidates = candidateResults.map { res ->
                val target = res.candidate.component ?: res.candidate.action ?: "Unknown"
                OemCandidateReport(
                    target = target,
                    resolves = res.resolves,
                    feedback = candidateFeedback[target]
                )
            }
        )

        val lastCrash = LocalCrashRecorder.readLastCrash(context)?.let {
            CrashReportItem(
                timestamp = it.timestamp,
                exceptionClass = it.exceptionClass,
                frames = it.appFrames
            )
        }

        val exitHistory = LocalCrashRecorder.readExitReasons(context).map {
            ExitReportItem(
                timestamp = it.timestamp,
                reasonDescription = it.reasonDescription,
                importance = it.importance
            )
        }

        val diagnostics = Diagnostics.getRecords().map {
            DiagnosticReportItem(
                timestamp = it.timestamp,
                tag = it.tag,
                reason = it.reason
            )
        }

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis

        val funnel = pipelineEventDao?.getFunnelCounts(startOfDay, now)?.map {
            FunnelStageCount(stage = it.stage, code = it.code, count = it.count)
        } ?: emptyList()

        val recentTrail = if (isMissedPaymentReport) {
            val thirtyMinutesAgo = now - 30 * 60 * 1000L
            pipelineEventDao?.getEventsSince(thirtyMinutesAgo)
        } else {
            pipelineEventDao?.getRecentEvents(50)
        }?.map {
            TrailEventReportItem(
                time = it.time,
                stage = it.stage,
                code = it.code,
                metricA = it.a,
                metricB = it.b
            )
        } ?: emptyList()

        val discovery = if (BuildConfig.ENABLE_DISCOVERY_SCAN) {
            DiscoveryScanner.scan(context)
        } else {
            emptyList()
        }

        val rawGoogleErrors = if (includeGoogleErrors) {
            com.financeautomation.data.remote.GoogleApiError.rawHttpErrorStore?.getEntries() ?: emptyList()
        } else {
            emptyList()
        }

        val data = CompatibilityReportData(
            header = header,
            healthRows = healthSummary,
            oemRoute = oemRouteReport,
            lastCrash = lastCrash,
            exitHistory = exitHistory,
            diagnostics = diagnostics,
            funnelCounts = funnel,
            recentTrail = recentTrail,
            discoveryScan = discovery,
            lastHeartbeatTime = PipelineTrail.getLastHeartbeatTime(),
            todayDroppedNonWhitelistedCount = PipelineTrail.getTodayDroppedNonWhitelistedCount(),
            userNote = userNote,
            rawGoogleErrors = rawGoogleErrors
        )

        CompatibilityReportBuilder.buildMarkdown(data)
    }
}

