package com.financeautomation.presentation.screens.settings

import com.financeautomation.presentation.screens.SettingsCard

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
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState

/**
 * Settings section for Google Sheets ledger connectivity, cloud config sync, and settle window (Design Guide 5.2).
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
            title = "Google Sheets and sync",
            subtitle = "Master Ledger integration and automated cloud sync",
            icon = Icons.Rounded.CloudSync
        ) {
            OutlinedTextField(
                value = spreadsheetInput,
                onValueChange = { spreadsheetInput = it },
                label = { Text("Google Spreadsheet ID") },
                placeholder = { Text("Paste spreadsheet ID from URL") },
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
                    Icon(imageVector = Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text("Save ID")
                }

                OutlinedButton(
                    onClick = onSyncNow,
                    enabled = !state.isSyncing,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text("Sync now")
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
                    Icon(imageVector = Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text("Mirror config")
                }

                OutlinedButton(
                    onClick = onRestoreConfigFromCloud,
                    enabled = !state.isConfigSyncing,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text("Restore config")
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
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(imageVector = Icons.Rounded.CloudDone, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text("Regenerate dashboard formulas")
                }
            }
        }

        // 2. Background Settle Buffer
        SettingsCard(
            title = "Background settle buffer",
            subtitle = "Wait window before dispatching cloud sync: ${sliderValue.toInt()}s (30s to 300s)",
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
