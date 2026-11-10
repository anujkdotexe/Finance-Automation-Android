package com.financeautomation.presentation.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.FinanceDatabase
import com.financeautomation.data.local.dao.StageCodeCount
import com.financeautomation.data.local.entity.PipelineEventEntity
import kotlinx.coroutines.CancellationException
import com.financeautomation.presentation.components.SecondaryButton
import com.financeautomation.presentation.components.TextAction
import com.financeautomation.presentation.screens.SettingsCard
import com.financeautomation.presentation.theme.FinanceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Capture health card in Settings: displays the daily capture funnel and last 50 events (Phase 9.3b).
 */
@Composable
fun CaptureHealthCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var funnelCounts by remember { mutableStateOf<List<StageCodeCount>>(emptyList()) }
    var recentEvents by remember { mutableStateOf<List<PipelineEventEntity>>(emptyList()) }
    var isExpanded by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val db = FinanceDatabase.getInstance(context)
                val dao = db.pipelineEventDao()
                val now = System.currentTimeMillis()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                funnelCounts = dao.getFunnelCounts(cal.timeInMillis, now)
                recentEvents = dao.getRecentEvents(50)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("CaptureHealthCard", "loadTrailData: ${e.javaClass.simpleName}")
            }
        }
    }

    SettingsCard(
        title = stringResource(R.string.report_capture_health_title),
        subtitle = stringResource(R.string.report_capture_health_subtitle),
        icon = Icons.Rounded.HealthAndSafety,
        modifier = modifier
    ) {
        // Daily Funnel Summary
        Text(
            text = "Daily Pipeline Funnel",
            style = FinanceTheme.typography.labelMedium,
            color = FinanceTheme.colors.onSurface
        )

        if (funnelCounts.isEmpty()) {
            Text(
                text = "No events recorded today",
                style = FinanceTheme.typography.bodySmall,
                color = FinanceTheme.colors.onSurfaceVariant
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = FinanceTheme.colors.surfaceVariant,
                        shape = FinanceTheme.shapes.small
                    )
                    .padding(FinanceTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                for (item in funnelCounts) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${item.stage} / ${item.code}",
                            style = FinanceTheme.typography.bodySmall,
                            color = FinanceTheme.colors.onSurfaceVariant
                        )
                        Text(
                            text = item.count.toString(),
                            style = FinanceTheme.typography.labelMedium,
                            color = FinanceTheme.colors.onSurface
                        )
                    }
                }
            }
        }

        // Recent 50 Events Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Trail Events (${recentEvents.size})",
                style = FinanceTheme.typography.labelMedium,
                color = FinanceTheme.colors.onSurface
            )
            TextAction(
                text = if (isExpanded) stringResource(R.string.action_show_less) else stringResource(R.string.action_see_all),
                onClick = { isExpanded = !isExpanded }
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = FinanceTheme.sizes.diagnosticsHeight)
                    .background(
                        color = FinanceTheme.colors.surfaceVariant,
                        shape = FinanceTheme.shapes.small
                    )
                    .padding(FinanceTheme.spacing.m)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    for (event in recentEvents) {
                        Text(
                            text = "${event.time}: ${event.stage} -> ${event.code}",
                            style = FinanceTheme.typography.bodySmall,
                            color = FinanceTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }
        }

        SecondaryButton(
            text = stringResource(R.string.report_title),
            onClick = { showReportDialog = true },
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showReportDialog) {
        CompatibilityReportDialog(
            isMissedPayment = false,
            onDismiss = { showReportDialog = false }
        )
    }
}
