package com.financeautomation.presentation.screens.drawer

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.financeautomation.R
import com.financeautomation.presentation.screens.AdvancedDiagnosticsCard
import com.financeautomation.presentation.screens.settings.CaptureHealthCard
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.service.NotificationProcessor
import com.financeautomation.service.worker.SubscriptionReminderWorker
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(
    state: SettingsUiState,
    onSyncNow: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var showSyncLog by remember { mutableStateOf(false) }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let {
            Toast.makeText(context, it.asString(context), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            Toast.makeText(context, it.asString(context), Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        if (!state.developerMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surfaceVariant),
                shape = FinanceShapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FinanceTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(FinanceTheme.sizes.iconLarge),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
                    Text(
                        text = stringResource(R.string.diag_locked_title),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
                    Text(
                        text = stringResource(R.string.diag_locked_desc),
                        style = FinanceTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column {
                Text(
                    text = stringResource(R.string.diag_section_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.diag_section_subtitle),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Developer Actions Card
            Surface(
                color = FinanceTheme.colors.surfaceContainerLowest,
                shape = FinanceShapes.large,
                shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Row 1: Run sync now
                    DiagnosticActionRow(
                        icon = Icons.Rounded.Sync,
                        title = stringResource(R.string.diag_run_sync_now),
                        subtitle = stringResource(R.string.diag_run_sync_now_desc),
                        onClick = onSyncNow
                    )

                    HorizontalDivider(
                        color = FinanceTheme.colors.surfaceContainerLow,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                    )

                    // Row 2: Run subscription check now
                    DiagnosticActionRow(
                        icon = Icons.Rounded.EventRepeat,
                        title = stringResource(R.string.diag_run_sub_check),
                        subtitle = stringResource(R.string.diag_run_sub_check_desc),
                        onClick = {
                            SubscriptionReminderWorker.runNow(context.applicationContext)
                        }
                    )

                    HorizontalDivider(
                        color = FinanceTheme.colors.surfaceContainerLow,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                    )

                    // Row 3: View sync log
                    DiagnosticActionRow(
                        icon = Icons.Rounded.Terminal,
                        title = stringResource(R.string.diag_view_sync_log),
                        subtitle = stringResource(R.string.diag_view_sync_log_desc),
                        onClick = { showSyncLog = !showSyncLog }
                    )

                    HorizontalDivider(
                        color = FinanceTheme.colors.surfaceContainerLow,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                    )

                    // Row 4: Export logs
                    DiagnosticActionRow(
                        icon = Icons.Rounded.IosShare,
                        title = stringResource(R.string.diag_export_logs),
                        subtitle = stringResource(R.string.diag_export_logs_desc),
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, com.financeautomation.core.utils.MaskUtils.diagnosticsShareText(state.googleAccountEmail, state.spreadsheetId))
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.diag_share_chooser_title)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    )
                }
            }

            if (showSyncLog) {
                AdvancedDiagnosticsCard(modifier = Modifier.fillMaxWidth())
            }

            CaptureHealthCard(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DiagnosticActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
            .clip(FinanceShapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            Surface(
                color = FinanceTheme.colors.surfaceContainerLow,
                shape = RoundedCornerShape(FinanceTheme.spacing.s),
                modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.icon22)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
        )
    }
}
