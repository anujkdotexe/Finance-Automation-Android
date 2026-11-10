package com.financeautomation.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.financeautomation.R
import com.financeautomation.presentation.screens.SettingsCard
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState

/**
 * Settings section: Google Sheets Master Ledger sync and settle buffer interval.
 * Split from SettingsScreen to preserve the 400-line ceiling (Design Guide 4).
 */
@Composable
fun SettingsSyncSection(
    state: SettingsUiState,
    onSpreadsheetIdChanged: (String) -> Unit,
    onSettleDelayChanged: (Int) -> Unit,
    onSyncNow: () -> Unit,
    onMirrorConfigToCloud: () -> Unit,
    onRestoreConfigFromCloud: () -> Unit,
    onRegenerateDashboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    var spreadsheetInput by remember(state.spreadsheetId) { mutableStateOf(state.spreadsheetId) }
    var sliderValue by remember(state.settleDelaySeconds) { mutableFloatStateOf(state.settleDelaySeconds.toFloat()) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)) {
        // 1. Google Sheets Connection & Cloud Sync
        SettingsCard(
            title = stringResource(R.string.settings_sync_title),
            subtitle = stringResource(R.string.settings_sync_desc),
            icon = Icons.Rounded.CloudSync
        ) {
            OutlinedTextField(
                value = spreadsheetInput,
                onValueChange = { spreadsheetInput = it },
                label = { Text(stringResource(R.string.settings_spreadsheet_id_label)) },
                placeholder = { Text(stringResource(R.string.settings_spreadsheet_id_placeholder)) },
                singleLine = true,
                shape = FinanceTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                Button(
                    onClick = { onSpreadsheetIdChanged(spreadsheetInput) },
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.settings_save_id))
                }

                OutlinedButton(
                    onClick = onSyncNow,
                    enabled = !state.isSyncing,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(strokeWidth = FinanceTheme.sizes.strokeThick, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    } else {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text(stringResource(R.string.action_sync_now))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                OutlinedButton(
                    onClick = onMirrorConfigToCloud,
                    enabled = !state.isConfigSyncing,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.action_mirror_config))
                }

                OutlinedButton(
                    onClick = onRestoreConfigFromCloud,
                    enabled = !state.isConfigSyncing,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.action_restore_config))
                }
            }

            Button(
                onClick = onRegenerateDashboard,
                enabled = !state.isRegeneratingDashboard,
                shape = FinanceTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FinanceTheme.colors.brand.copy(alpha = 0.12f),
                    contentColor = FinanceTheme.colors.brand
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isRegeneratingDashboard) {
                    CircularProgressIndicator(strokeWidth = FinanceTheme.sizes.strokeThick, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                } else {
                    Icon(imageVector = Icons.Rounded.CloudDone, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.action_regenerate_dashboard))
                }
            }
        }

        // 2. Background Settle Buffer
        SettingsCard(
            title = stringResource(R.string.settings_settle_buffer_title),
            subtitle = stringResource(R.string.settings_settle_buffer_desc_format, sliderValue.toInt()),
            icon = Icons.Rounded.Timer
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSettleDelayChanged(sliderValue.toInt()) },
                valueRange = 30f..300f,
                steps = 8
            )
        }
    }
}
