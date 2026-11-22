package com.financeautomation.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.BuildConfig
import com.financeautomation.R
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Shared Privacy settings group used by both PreferencesScreen and SettingsScreen (C8, U15, C14).
 *
 * Guarantees:
 * - Both switches (crash reports and anonymous usage counts) are displayed with identical text.
 * - Test crash report trigger is strictly hidden unless developer mode is unlocked.
 */
object PrivacySettingsPolicy {
    fun shouldShowTestReport(developerMode: Boolean, isDebugOrBeta: Boolean): Boolean {
        return developerMode && isDebugOrBeta
    }
}

@Composable
fun PrivacySettingsGroup(
    crashReportsEnabled: Boolean,
    onCrashReportsChange: (Boolean) -> Unit,
    analyticsEnabled: Boolean,
    onAnalyticsChange: (Boolean) -> Unit,
    developerMode: Boolean,
    onSendTestReport: () -> Unit,
    modifier: Modifier = Modifier,
    appLockEnabled: Boolean? = null,
    lockUnavailable: Boolean = false,
    onAppLockChange: ((Boolean) -> Unit)? = null,
    hidePreview: Boolean? = null,
    onHidePreviewChange: ((Boolean) -> Unit)? = null,
    titleText: String = stringResource(R.string.pref_privacy_title)
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
    ) {
        Text(
            text = titleText,
            style = FinanceTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (appLockEnabled != null && onAppLockChange != null) {
                    PrivacySwitchRow(
                        title = stringResource(R.string.pref_app_lock_title),
                        description = stringResource(R.string.pref_app_lock_desc),
                        checked = appLockEnabled,
                        onCheckedChange = onAppLockChange
                    )
                    if (lockUnavailable) {
                        Text(
                            text = stringResource(R.string.pref_app_lock_unavailable),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                        )
                    }
                    HorizontalDivider(
                        color = FinanceTheme.colors.surfaceContainerLow,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                    )
                }

                if (hidePreview != null && onHidePreviewChange != null) {
                    PrivacySwitchRow(
                        title = stringResource(R.string.pref_hide_preview_title),
                        description = stringResource(R.string.pref_hide_preview_desc),
                        checked = hidePreview,
                        onCheckedChange = onHidePreviewChange
                    )
                    HorizontalDivider(
                        color = FinanceTheme.colors.surfaceContainerLow,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                    )
                }

                // Switch 1: Send crash reports
                PrivacySwitchRow(
                    title = stringResource(R.string.settings_privacy_crash_title),
                    description = stringResource(R.string.settings_privacy_crash_desc),
                    checked = crashReportsEnabled,
                    onCheckedChange = onCrashReportsChange
                )

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // Switch 2: Share anonymous usage counts
                PrivacySwitchRow(
                    title = stringResource(R.string.settings_privacy_analytics_title),
                    description = stringResource(R.string.settings_privacy_analytics_desc),
                    checked = analyticsEnabled,
                    onCheckedChange = onAnalyticsChange
                )

                // Developer-only test crash report (strictly hidden unless developer mode is on)
                if (PrivacySettingsPolicy.shouldShowTestReport(
                        developerMode = developerMode,
                        isDebugOrBeta = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "beta"
                    )
                ) {
                    HorizontalDivider(
                        color = FinanceTheme.colors.surfaceContainerLow,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                            .clip(FinanceShapes.medium)
                            .clickable(onClick = onSendTestReport)
                            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.privacy_test_report_action),
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacySwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
            .clip(FinanceShapes.medium)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = FinanceTheme.colors.surfaceContainerHighest
            )
        )
    }
}
