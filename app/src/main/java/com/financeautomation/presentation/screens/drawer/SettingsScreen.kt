package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

/**
 * Settings v3 screen conforming to Stitch specification.
 * Displays navigation rows for all sub-sections plus technical privacy switches.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onNavigateToCategories: () -> Unit = {},
    onNavigateToAccounts: () -> Unit = {},
    onNavigateToPeopleTrips: () -> Unit = {},
    onNavigateToSubscriptions: () -> Unit = {},
    onNavigateToCapturePermissions: () -> Unit = {},
    onNavigateToSyncBackup: () -> Unit = {},
    onNavigateToPreferences: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var crashReportsEnabled by remember(state.crashlyticsOptIn) {
        mutableStateOf(com.financeautomation.core.utils.CrashlyticsManager.isOptedIn(context))
    }
    var analyticsEnabled by remember(state.analyticsOptIn) {
        mutableStateOf(com.financeautomation.core.utils.AnalyticsManager.isOptedIn(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        // Section Header
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                Text(
                    text = stringResource(R.string.settings_screen_title),
                    style = FinanceTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = FinanceShapes.pill
                ) {
                    Text(
                        text = "v3",
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.xs, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_screen_subtitle),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Primary Navigation Menu Card (9 rows)
        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Categories
                SettingsNavigationRow(
                    icon = Icons.Rounded.Category,
                    title = stringResource(R.string.nav_categories_title),
                    subtitle = stringResource(R.string.pref_categories_count, state.categories.size),
                    onClick = onNavigateToCategories
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // 2. Accounts
                SettingsNavigationRow(
                    icon = Icons.Rounded.AccountBalance,
                    title = stringResource(R.string.accounts_section_title),
                    subtitle = stringResource(R.string.pref_accounts_count, state.accounts.size),
                    onClick = onNavigateToAccounts
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // 3. People and trips
                SettingsNavigationRow(
                    icon = Icons.Rounded.Group,
                    title = stringResource(R.string.nav_people_trips_title),
                    subtitle = stringResource(R.string.settings_nav_people_trips_subtitle),
                    onClick = onNavigateToPeopleTrips
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // 4. Subscriptions
                SettingsNavigationRow(
                    icon = Icons.Rounded.EventRepeat,
                    title = stringResource(R.string.nav_subscriptions_title),
                    subtitle = stringResource(R.string.settings_nav_subscriptions_subtitle),
                    onClick = onNavigateToSubscriptions
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // 5. Capture and permissions
                SettingsNavigationRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.nav_capture_permissions_title),
                    subtitle = stringResource(R.string.settings_nav_capture_subtitle),
                    onClick = onNavigateToCapturePermissions
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // 6. Sync and backup
                SettingsNavigationRow(
                    icon = Icons.Rounded.CloudSync,
                    title = stringResource(R.string.nav_sync_backup_title),
                    subtitle = stringResource(R.string.settings_nav_sync_subtitle),
                    onClick = onNavigateToSyncBackup
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // 7. Preferences
                SettingsNavigationRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.nav_preferences_title),
                    subtitle = stringResource(R.string.settings_nav_preferences_subtitle),
                    onClick = onNavigateToPreferences
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // 8. Diagnostics
                SettingsNavigationRow(
                    icon = Icons.Rounded.BugReport,
                    title = stringResource(R.string.nav_diagnostics_title),
                    subtitle = stringResource(R.string.settings_nav_diagnostics_subtitle),
                    onClick = onNavigateToDiagnostics
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // 9. About
                SettingsNavigationRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.nav_about_title),
                    subtitle = stringResource(R.string.settings_nav_about_subtitle),
                    onClick = onNavigateToAbout
                )
            }
        }

        // Privacy & Telemetry Section
        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Switch 1: Send crash reports (wired to P1-6 opt-in)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = FinanceTheme.spacing.m)) {
                        Text(
                            text = stringResource(R.string.settings_privacy_crash_title),
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_privacy_crash_desc),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = crashReportsEnabled,
                        onCheckedChange = { enabled ->
                            crashReportsEnabled = enabled
                            viewModel.setCrashlyticsOptIn(context, enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = FinanceTheme.colors.surfaceContainerHighest
                        )
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // Switch 2: Share anonymous usage counts (off by default, wired when P2-G is built)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = FinanceTheme.spacing.m)) {
                        Text(
                            text = stringResource(R.string.settings_privacy_analytics_title),
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_privacy_analytics_desc),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = analyticsEnabled,
                        onCheckedChange = { enabled ->
                            analyticsEnabled = enabled
                            viewModel.setAnalyticsOptIn(context, enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = FinanceTheme.colors.surfaceContainerHighest
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
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
            .clickable(onClick = onClick)
            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(FinanceTheme.sizes.buttonHeightMedium)
                    .clip(CircleShape)
                    .background(FinanceTheme.colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
