package com.financeautomation.presentation.screens.drawer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.financeautomation.R
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.screens.settings.CaptureHealthCard
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.HealthCheckId
import com.financeautomation.presentation.viewmodel.HealthStatus
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel

@Composable
fun CapturePermissionsScreen(
    state: SettingsUiState,
    setupHealthViewModel: SetupHealthViewModel?,
    overlayControllerProvider: (() -> OverlayController)? = null,
    onReRunSetup: () -> Unit = {},
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
        ?: remember { androidx.compose.runtime.mutableStateOf(null) }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        // Main Permissions Card
        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Notification access
                CapturePermissionRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.capture_notification_title),
                    subtitle = stringResource(R.string.capture_notification_desc),
                    statusText = if (hasNotification) stringResource(R.string.status_on) else stringResource(R.string.status_off),
                    onClick = {
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
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 2. Battery optimisation
                CapturePermissionRow(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = stringResource(R.string.capture_battery_title),
                    subtitle = stringResource(R.string.capture_battery_desc),
                    statusText = if (!isBatteryOptimized) stringResource(R.string.status_unrestricted) else stringResource(R.string.status_restricted),
                    onClick = {
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
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 3. Overlay
                CapturePermissionRow(
                    icon = Icons.Rounded.Layers,
                    title = stringResource(R.string.capture_overlay_title),
                    subtitle = stringResource(R.string.capture_overlay_desc),
                    statusText = if (hasOverlay) stringResource(R.string.status_on) else stringResource(R.string.status_off),
                    onClick = {
                        try {
                            context.startActivity(Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {}
                    }
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 4. Usage access
                CapturePermissionRow(
                    icon = Icons.Rounded.Schedule,
                    title = stringResource(R.string.capture_usage_access_title),
                    subtitle = stringResource(R.string.capture_usage_access_desc),
                    statusText = if (hasUsageAccess) stringResource(R.string.status_on) else stringResource(R.string.status_off),
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {}
                    }
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 4. Apps we read
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(FinanceTheme.sizes.iconMedium)
                            .padding(top = FinanceTheme.spacing.xs)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.capture_apps_title),
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.capture_apps_desc),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Privacy Guarantee Note
        Text(
            text = stringResource(R.string.capture_privacy_note),
            style = FinanceTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.xs)
        )

        // Setup Health & OEM Checklist
        if (setupHealthViewModel != null) {
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
            com.financeautomation.presentation.screens.SetupHealthCard(
                viewModel = setupHealthViewModel,
                overlayControllerProvider = overlayControllerProvider,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Developer Health Diagnostics (Only visible in Developer mode)
        if (state.developerMode) {
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
            CaptureHealthCard(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CapturePermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    statusText: String,
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
            )
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

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = FinanceShapes.pill,
            modifier = Modifier.padding(start = FinanceTheme.spacing.s)
        ) {
            Text(
                text = statusText,
                style = FinanceTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.xs)
            )
        }
    }
}
