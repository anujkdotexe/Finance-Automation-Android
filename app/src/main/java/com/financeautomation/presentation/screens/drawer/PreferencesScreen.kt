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
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun PreferencesScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onNavigateToCategories: () -> Unit = {},
    onNavigateToAccounts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showAccountDialog by remember { mutableStateOf(false) }
    var lockUnavailable by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? androidx.fragment.app.FragmentActivity
    val promptTitle = stringResource(R.string.app_lock_prompt_enable_title)
    var showClearVoided by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        Column {
            Text(
                text = stringResource(R.string.pref_section_title),
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.pref_section_subtitle),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Categories & Accounts Management Card
        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ROW 1: Categories
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .clickable(onClick = onNavigateToCategories)
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
                                imageVector = Icons.Rounded.Category,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.nav_categories_title),
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.pref_categories_count, state.categories.size),
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

                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))

                // ROW 2: Accounts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .clickable(onClick = onNavigateToAccounts)
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
                                imageVector = Icons.Rounded.AccountBalance,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.accounts_section_title),
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.pref_accounts_count, state.accounts.size),
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
        }

        // Exactly Three Rows Card
        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ROW 1: Theme (System | Light | Dark)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    Text(
                        text = stringResource(R.string.pref_theme_title),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        color = FinanceTheme.colors.surfaceContainer,
                        shape = FinanceShapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(FinanceTheme.spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                        ) {
                            listOf(
                                Triple(ThemeMode.SYSTEM, stringResource(R.string.pref_theme_system), Icons.Rounded.SettingsBrightness),
                                Triple(ThemeMode.LIGHT, stringResource(R.string.pref_theme_light), Icons.Rounded.LightMode),
                                Triple(ThemeMode.DARK, stringResource(R.string.pref_theme_dark), Icons.Rounded.DarkMode)
                            ).forEach { (mode, label, icon) ->
                                val isSelected = state.themeMode == mode
                                Surface(
                                    color = if (isSelected) FinanceTheme.colors.surfaceContainerLowest else FinanceTheme.colors.surfaceContainer,
                                    shape = FinanceShapes.small,
                                    shadowElevation = if (isSelected) FinanceTheme.sizes.shadowElevationSmall else FinanceTheme.spacing.none,
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                                        .clip(FinanceShapes.small)
                                        .clickable { viewModel.setThemeMode(mode) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = FinanceTheme.spacing.s),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                        )
                                        Text(
                                            text = label,
                                            style = FinanceTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = FinanceTheme.spacing.xs)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // ROW 2: Default Account
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .clip(FinanceShapes.medium)
                        .clickable { showAccountDialog = true }
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.pref_default_account_title),
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.pref_default_account_desc),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        Text(
                            text = state.defaultAccount,
                            style = FinanceTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                        )
                    }
                }

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // ROW 3: Subscription Reminder Time
                val (curH, curM) = remember(state.subscriptionReminderTime) {
                    val parts = com.financeautomation.domain.repository.PreferencesRepository.normalizeReminderTime(state.subscriptionReminderTime)
                        .split(":").map { it.toIntOrNull() ?: 0 }
                    Pair(parts.getOrElse(0) { 21 }, parts.getOrElse(1) { 0 })
                }
                val formattedReminderTime = remember(curH, curM) {
                    val amPm = if (curH >= 12) "PM" else "AM"
                    val h12 = if (curH == 0) 12 else if (curH > 12) curH - 12 else curH
                    "%d:%02d %s".format(h12, curM, amPm)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .clip(FinanceShapes.medium)
                        .clickable {
                            if (activity != null) {
                                android.app.TimePickerDialog(
                                    activity,
                                    { _, hourOfDay, minute ->
                                        viewModel.onSubscriptionReminderTimeChanged("%02d:%02d".format(hourOfDay, minute))
                                    },
                                    curH,
                                    curM,
                                    false
                                ).show()
                            }
                        }
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.pref_reminder_time_title),
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.pref_reminder_time_desc),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        Text(
                            text = formattedReminderTime,
                            style = FinanceTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                        )
                    }
                }
            }
        }

        val context = androidx.compose.ui.platform.LocalContext.current
        var crashReportsEnabled by remember(state.crashlyticsOptIn) {
            mutableStateOf(com.financeautomation.core.utils.CrashlyticsManager.isOptedIn(context))
        }

        PrivacyGroup(
            appLockEnabled = state.appLockEnabled,
            hidePreview = state.hideRecentsPreview,
            crashReportsEnabled = crashReportsEnabled,
            lockUnavailable = lockUnavailable,
            onAppLockChange = { wantOn ->
                lockUnavailable = false
                if (!wantOn) {
                    viewModel.onAppLockDisabled()
                } else if (activity != null) {
                    val authenticator = com.financeautomation.presentation.security.AppAuthenticator(activity)
                    if (authenticator.availability() != com.financeautomation.domain.security.AuthAvailability.AVAILABLE) {
                        lockUnavailable = true
                    } else {
                        authenticator.authenticate(promptTitle, null) { ok -> viewModel.onAppLockAuthenticated(ok) }
                    }
                }
            },
            onHidePreviewChange = { viewModel.setHideRecentsPreview(it) },
            onCrashReportsChange = { enabled ->
                crashReportsEnabled = enabled
                viewModel.setCrashlyticsOptIn(context, enabled)
            },
            onSendTestReport = {
                viewModel.sendTestCrashReport(context)
            }
        )
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.pref_default_account_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    val accountsList = if (state.accounts.isNotEmpty()) {
                        state.accounts.map { it.name }
                    } else {
                        listOf(com.financeautomation.core.constants.CategoryConstants.ACCOUNT_OTHER)
                    }
                    accountsList.forEach { accountName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(FinanceShapes.small)
                                .clickable {
                                    viewModel.onDefaultAccountChanged(accountName)
                                    showAccountDialog = false
                                }
                                .padding(vertical = FinanceTheme.spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                        ) {
                            RadioButton(
                                selected = state.defaultAccount == accountName,
                                onClick = {
                                    viewModel.onDefaultAccountChanged(accountName)
                                    showAccountDialog = false
                                }
                            )
                            Text(
                                text = accountName,
                                style = FinanceTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )

        // Data
        Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
            Text(
                text = stringResource(R.string.pref_data_title),
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.pref_data_subtitle),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            com.financeautomation.presentation.components.OutlinedButton(
                text = stringResource(R.string.clear_voided_menu),
                onClick = { showClearVoided = true }
            )
        }
        com.financeautomation.presentation.components.ClearVoidedHistoryHost(
            show = showClearVoided,
            onDismiss = { showClearVoided = false }
        )
    }
}

@Composable
private fun PrivacyGroup(
    appLockEnabled: Boolean,
    hidePreview: Boolean,
    crashReportsEnabled: Boolean,
    lockUnavailable: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    onHidePreviewChange: (Boolean) -> Unit,
    onCrashReportsChange: (Boolean) -> Unit,
    onSendTestReport: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
        Text(
            text = stringResource(R.string.pref_privacy_title),
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
                SwitchRow(
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
                SwitchRow(
                    title = stringResource(R.string.pref_hide_preview_title),
                    description = stringResource(R.string.pref_hide_preview_desc),
                    checked = hidePreview,
                    onCheckedChange = onHidePreviewChange
                )
                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )
                SwitchRow(
                    title = stringResource(R.string.privacy_crash_title),
                    description = stringResource(R.string.privacy_crash_body),
                    checked = crashReportsEnabled,
                    onCheckedChange = onCrashReportsChange
                )
                if (com.financeautomation.BuildConfig.DEBUG || com.financeautomation.BuildConfig.BUILD_TYPE == "beta") {
                    HorizontalDivider(
                        color = FinanceTheme.colors.surfaceContainerLow,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
private fun SwitchRow(
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
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
