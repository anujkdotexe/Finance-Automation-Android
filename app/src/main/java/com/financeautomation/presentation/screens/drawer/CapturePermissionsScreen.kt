package com.financeautomation.presentation.screens.drawer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.financeautomation.R
import com.financeautomation.presentation.components.LinkButton
import com.financeautomation.presentation.components.OutlinedButton
import com.financeautomation.presentation.health.OemHardeningHelper
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.screens.SetupHealthIntents
import com.financeautomation.presentation.screens.settings.CaptureHealthCard
import com.financeautomation.presentation.screens.settings.CompatibilityReportDialog
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.HealthCheckId
import com.financeautomation.presentation.viewmodel.HealthStatus
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel

enum class StatusPillType {
    ON,
    RESTRICTED,
    MISSING,
    MANUAL_CHECK,
    DONE
}

@Composable
fun CaptureStatusPill(
    type: StatusPillType,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, text) = when (type) {
        StatusPillType.ON -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "On"
        )
        StatusPillType.DONE -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Done"
        )
        StatusPillType.RESTRICTED -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Restricted"
        )
        StatusPillType.MISSING -> Triple(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.error,
            "Missing"
        )
        StatusPillType.MANUAL_CHECK -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Manual check"
        )
    }

    Surface(
        shape = FinanceShapes.pill,
        color = bgColor,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = FinanceTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = textColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun CaptureItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    pillType: StatusPillType? = null,
    onFix: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentBelowSubtitle: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(FinanceTheme.sizes.iconMedium)
                .padding(top = FinanceTheme.spacing.xs)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = FinanceTheme.spacing.xs)
        ) {
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
            if (onFix != null) {
                LinkButton(
                    text = stringResource(R.string.health_fix_button),
                    onClick = onFix,
                    modifier = Modifier.padding(top = FinanceTheme.spacing.xs)
                )
            }
            if (contentBelowSubtitle != null) {
                contentBelowSubtitle()
            }
        }
        if (pillType != null) {
            CaptureStatusPill(type = pillType)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OemRowContent(
    oemInstruction: String?,
    isOemDone: Boolean,
    onToggleOemDone: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    oemFeedback: Boolean?,
    onOemFeedback: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = FinanceTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
    ) {
        if (!oemInstruction.isNullOrBlank()) {
            Text(
                text = oemInstruction,
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedButton(
            text = stringResource(R.string.health_oem_open_settings),
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleOemDone(!isOemDone) }
                .padding(vertical = FinanceTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isOemDone,
                onCheckedChange = onToggleOemDone,
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
            Text(
                text = stringResource(R.string.health_oem_configured),
                style = FinanceTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = stringResource(R.string.oem_feedback_prompt),
            style = FinanceTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
        ) {
            FilterChip(
                selected = oemFeedback == true,
                onClick = { onOemFeedback(true) },
                label = { Text(stringResource(R.string.action_yes)) },
                shape = FinanceTheme.shapes.small
            )
            FilterChip(
                selected = oemFeedback == false,
                onClick = { onOemFeedback(false) },
                label = { Text(stringResource(R.string.action_no)) },
                shape = FinanceTheme.shapes.small
            )
        }
    }
}

@Composable
fun OemBackgroundCard(
    isOemDone: Boolean,
    onToggleOemDone: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    oemInstruction: String? = null,
    oemFeedback: Boolean? = null,
    onOemFeedback: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier.fillMaxWidth()
    ) {
        CaptureItemRow(
            icon = Icons.Rounded.Build,
            title = stringResource(R.string.health_oem_title),
            subtitle = stringResource(R.string.health_oem_desc),
            pillType = if (isOemDone) StatusPillType.DONE else StatusPillType.MANUAL_CHECK,
            contentBelowSubtitle = {
                OemRowContent(
                    oemInstruction = oemInstruction,
                    isOemDone = isOemDone,
                    onToggleOemDone = onToggleOemDone,
                    onOpenSettings = onOpenSettings,
                    oemFeedback = oemFeedback,
                    onOemFeedback = onOemFeedback
                )
            }
        )
    }
}

@Composable
fun CapturePermissionsScreen(
    state: SettingsUiState,
    setupHealthViewModel: SetupHealthViewModel?,
    overlayControllerProvider: (() -> OverlayController)? = null,
    onReRunSetup: () -> Unit = {},
    onNavigateGoogleSignIn: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                setupHealthViewModel?.refreshChecks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val healthState by setupHealthViewModel?.uiState?.collectAsState()
        ?: remember { mutableStateOf(null) }

    val hasNotification = remember(healthState) {
        val check = healthState?.checks?.firstOrNull { it.id == HealthCheckId.NOTIFICATION_ACCESS }
        if (check != null) {
            check.status == HealthStatus.OK
        } else {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }
    }

    val isBatteryOptimized = remember(healthState) {
        val check = healthState?.checks?.firstOrNull { it.id == HealthCheckId.BATTERY_OPTIMIZATION }
        if (check != null) {
            check.status != HealthStatus.OK
        } else {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) != true
        }
    }

    val hasOverlay = remember(healthState) {
        val check = healthState?.checks?.firstOrNull { it.id == HealthCheckId.DISPLAY_OVER_APPS }
        if (check != null) {
            check.status == HealthStatus.OK
        } else {
            Settings.canDrawOverlays(context)
        }
    }

    val hasUsageAccess = remember(healthState) {
        val check = healthState?.checks?.firstOrNull { it.id == HealthCheckId.USAGE_ACCESS }
        if (check != null) {
            check.status == HealthStatus.OK
        } else {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps?.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps?.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }
    }

    val oemCheck = remember(healthState) {
        healthState?.checks?.firstOrNull { it.id == HealthCheckId.OEM_BACKGROUND }
    }
    val oemInfo = remember { OemHardeningHelper.getOemInfo() }
    val isOemDone = oemCheck?.isOemToggleDone ?: false

    var oemFeedbackSubmitted by remember { mutableStateOf<Boolean?>(null) }
    var reportDialogMode by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        // Single unified white rounded card on pale teal background matching Stitch design
        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Notification access
                CaptureItemRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.health_notification_access_title),
                    subtitle = stringResource(R.string.health_notification_access_desc),
                    pillType = if (hasNotification) StatusPillType.ON else StatusPillType.MISSING,
                    onFix = if (!hasNotification) {
                        {
                            try {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                        }
                    } else null
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 2. Battery optimisation
                CaptureItemRow(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = stringResource(R.string.health_battery_title),
                    subtitle = stringResource(R.string.health_battery_desc),
                    pillType = if (!isBatteryOptimized) StatusPillType.ON else StatusPillType.RESTRICTED,
                    onFix = if (isBatteryOptimized) {
                        {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (_: Exception) {}
                            }
                        }
                    } else null
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 3. Overlay
                CaptureItemRow(
                    icon = Icons.Rounded.Layers,
                    title = stringResource(R.string.health_overlay_title),
                    subtitle = stringResource(R.string.health_overlay_desc),
                    pillType = if (hasOverlay) StatusPillType.ON else StatusPillType.MISSING,
                    onFix = if (!hasOverlay) {
                        {
                            try {
                                context.startActivity(Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {}
                        }
                    } else null
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 4. Usage access (if supported)
                CaptureItemRow(
                    icon = Icons.Rounded.Schedule,
                    title = stringResource(R.string.health_usage_access_title),
                    subtitle = stringResource(R.string.health_usage_access_desc),
                    pillType = if (hasUsageAccess) StatusPillType.ON else StatusPillType.MISSING,
                    onFix = if (!hasUsageAccess) {
                        {
                            try {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {}
                        }
                    } else null
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 5. OEM background autostart (stacked layout with Open settings button and Yes/No chips)
                CaptureItemRow(
                    icon = Icons.Rounded.Build,
                    title = stringResource(R.string.health_oem_title),
                    subtitle = stringResource(R.string.health_oem_desc),
                    pillType = if (isOemDone) StatusPillType.DONE else StatusPillType.MANUAL_CHECK,
                    contentBelowSubtitle = {
                        OemRowContent(
                            oemInstruction = stringResource(oemInfo.instructionRes),
                            isOemDone = isOemDone,
                            onToggleOemDone = { done ->
                                setupHealthViewModel?.toggleOemCompleted(done)
                            },
                            onOpenSettings = {
                                oemCheck?.let { item ->
                                    SetupHealthIntents.launchSettingsWithGuardedFallback(
                                        context,
                                        item,
                                        oemInfo
                                    ) {}
                                }
                            },
                            oemFeedback = oemFeedbackSubmitted,
                            onOemFeedback = { openedRightScreen ->
                                oemFeedbackSubmitted = openedRightScreen
                                SetupHealthIntents.recordOemFeedback(oemInfo.brand.name, openedRightScreen)
                            }
                        )
                    }
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 6. Apps we read
                CaptureItemRow(
                    icon = Icons.Rounded.Apps,
                    title = stringResource(R.string.capture_apps_title),
                    subtitle = stringResource(R.string.capture_apps_desc)
                )
            }
        }

        // Privacy Guarantee Note below card
        Text(
            text = stringResource(R.string.capture_privacy_note),
            style = FinanceTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.xs)
        )

        // Force-stop warning (docs/DESIGN.md section 10.4)
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            shape = FinanceTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(FinanceTheme.spacing.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                )
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                Text(
                    text = stringResource(R.string.health_force_stop_warning),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Compatibility Report actions
        OutlinedButton(
            text = stringResource(R.string.health_generate_report_button),
            onClick = { reportDialogMode = false },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            text = stringResource(R.string.report_missed_payment_button),
            onClick = { reportDialogMode = true },
            modifier = Modifier.fillMaxWidth()
        )

        // Developer Health Diagnostics
        if (state.developerMode) {
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
            CaptureHealthCard(modifier = Modifier.fillMaxWidth())
        }
    }

    reportDialogMode?.let { isMissed ->
        CompatibilityReportDialog(
            isMissedPayment = isMissed,
            onDismiss = { reportDialogMode = null }
        )
    }
}
