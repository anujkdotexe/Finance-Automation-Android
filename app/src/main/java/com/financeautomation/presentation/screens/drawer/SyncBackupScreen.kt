package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.presentation.screens.settings.SettingsSyncSection
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun SyncBackupScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(FinanceTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        SettingsSyncSection(
            state = state,
            onSpreadsheetIdChanged = viewModel::onSpreadsheetIdChanged,
            onSettleDelayChanged = viewModel::onSettleDelayChanged,
            onSyncNow = viewModel::onSyncNow,
            onMirrorConfigToCloud = viewModel::onMirrorConfigToCloud,
            onRestoreConfigFromCloud = viewModel::onRestoreConfigFromCloud,
            onRegenerateDashboard = viewModel::onRegenerateDashboard,
            onRepairWorkbook = viewModel::autoProvisionMasterSheet
        )
    }
}

