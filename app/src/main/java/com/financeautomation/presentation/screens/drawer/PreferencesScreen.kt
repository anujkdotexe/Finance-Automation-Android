package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showAccountDialog by remember { mutableStateOf(false) }
    var lockUnavailable by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? androidx.fragment.app.FragmentActivity
    val promptTitle = stringResource(R.string.app_lock_prompt_enable_title)

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .clip(FinanceShapes.medium)
                        .clickable {
                            val nextDelay = when (state.settleDelaySeconds) {
                                60 -> 120
                                120 -> 180
                                180 -> 300
                                else -> 60
                            }
                            viewModel.onSettleDelayChanged(nextDelay)
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
                            text = "9:00 PM",
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

        PrivacyGroup(
            appLockEnabled = state.appLockEnabled,
            hidePreview = state.hideRecentsPreview,
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
            onHidePreviewChange = { viewModel.setHideRecentsPreview(it) }
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
                        listOf("HDFC", "ICICI", "Cash", "Other")
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
    }
}

@Composable
private fun PrivacyGroup(
    appLockEnabled: Boolean,
    hidePreview: Boolean,
    lockUnavailable: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    onHidePreviewChange: (Boolean) -> Unit
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
