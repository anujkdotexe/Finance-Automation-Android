package com.financeautomation.presentation.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.screens.settings.SettingsAccountSection
import com.financeautomation.presentation.screens.settings.SettingsCaptureSection
import com.financeautomation.presentation.screens.settings.SettingsSyncSection
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel

/**
 * Modernized Calm Utility Settings screen supporting 100% config-driven customization (Design Guide 5.2).
 * Grouped into modular sections: Account, Sheets and sync, Capture, and Home screen widgets.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    setupHealthViewModel: SetupHealthViewModel? = null,
    overlayControllerProvider: (() -> OverlayController)? = null,
    onReRunSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage, state.errorMessage) {
        state.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = FinanceTheme.colors.surface,
        modifier = modifier
    ) { innerPadding ->
        SettingsContent(
            state = state,
            setupHealthViewModel = setupHealthViewModel,
            overlayControllerProvider = overlayControllerProvider,
            onReRunSetup = onReRunSetup,
            onSignOut = viewModel::onGoogleSignOut,
            onSpreadsheetIdChanged = viewModel::onSpreadsheetIdChanged,
            onDefaultAccountChanged = viewModel::onDefaultAccountChanged,
            onAddAccount = viewModel::onAddAccount,
            onAddCategory = viewModel::onAddCategory,
            onToggleCategoryLivingBurnExclusion = viewModel::onToggleCategoryLivingBurnExclusion,
            onAddTripTag = viewModel::onAddTripTag,
            onToggleTripTagActive = viewModel::onToggleTripTagActive,
            onAddCompanion = viewModel::onAddCompanion,
            onRenameCompanion = viewModel::onRenameCompanion,
            onRemoveCompanion = viewModel::onRemoveCompanion,
            onSettleDelayChanged = viewModel::onSettleDelayChanged,
            onSyncNow = viewModel::onSyncNow,
            onMirrorConfigToCloud = viewModel::onMirrorConfigToCloud,
            onRestoreConfigFromCloud = viewModel::onRestoreConfigFromCloud,
            onRegenerateDashboard = viewModel::onRegenerateDashboard,
            onRepairWorkbook = viewModel::autoProvisionMasterSheet,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

/**
 * Pure stateless Settings layout composed of grouped section cards.
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    setupHealthViewModel: SetupHealthViewModel? = null,
    overlayControllerProvider: (() -> OverlayController)? = null,
    onReRunSetup: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onSpreadsheetIdChanged: (String) -> Unit,
    onDefaultAccountChanged: (String) -> Unit,
    onAddAccount: (String, String?) -> Unit,
    onAddCategory: (String, Boolean) -> Unit,
    onToggleCategoryLivingBurnExclusion: (String, Boolean) -> Unit,
    onAddTripTag: (String) -> Unit,
    onToggleTripTagActive: (String) -> Unit,
    onAddCompanion: (String) -> Unit,
    onRenameCompanion: (String, String) -> Unit,
    onRemoveCompanion: (String) -> Unit,
    onSettleDelayChanged: (Int) -> Unit,
    onSyncNow: () -> Unit,
    onMirrorConfigToCloud: () -> Unit,
    onRestoreConfigFromCloud: () -> Unit,
    onRegenerateDashboard: () -> Unit,
    onRepairWorkbook: () -> Unit = {},
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
        // 1. Setup Health Checklist & System Hardening
        SetupSection(setupHealthViewModel, overlayControllerProvider, onReRunSetup)

        // 2. Home Screen 1-Tap Widget & Shortcuts
        HomeScreenIconCard()

        // 3. Google Account, Funding Accounts, and Companions
        SettingsAccountSection(
            state = state,
            onReRunSetup = onReRunSetup,
            onSignOut = onSignOut,
            onDefaultAccountChanged = onDefaultAccountChanged,
            onAddAccount = onAddAccount,
            onAddCompanion = onAddCompanion,
            onRenameCompanion = onRenameCompanion,
            onRemoveCompanion = onRemoveCompanion
        )

        // 4. Google Sheets Ledger Connection & Settle Window
        SettingsSyncSection(
            state = state,
            onSpreadsheetIdChanged = onSpreadsheetIdChanged,
            onSettleDelayChanged = onSettleDelayChanged,
            onSyncNow = onSyncNow,
            onMirrorConfigToCloud = onMirrorConfigToCloud,
            onRestoreConfigFromCloud = onRestoreConfigFromCloud,
            onRegenerateDashboard = onRegenerateDashboard,
            onRepairWorkbook = onRepairWorkbook
        )

        // 5. Overlay Permission, Categories, and Trip Tags
        SettingsCaptureSection(
            state = state,
            onAddCategory = onAddCategory,
            onToggleCategoryLivingBurnExclusion = onToggleCategoryLivingBurnExclusion,
            onAddTripTag = onAddTripTag,
            onToggleTripTagActive = onToggleTripTagActive
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun SettingsScreenPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        SettingsContent(
            state = SettingsUiState(
                googleAccountEmail = "user@example.com",
                spreadsheetId = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms",
                defaultAccount = "Primary Account"
            ),
            onSpreadsheetIdChanged = {},
            onDefaultAccountChanged = {},
            onAddAccount = { _, _ -> },
            onAddCategory = { _, _ -> },
            onToggleCategoryLivingBurnExclusion = { _, _ -> },
            onAddTripTag = {},
            onToggleTripTagActive = {},
            onAddCompanion = {},
            onRenameCompanion = { _, _ -> },
            onRemoveCompanion = {},
            onSettleDelayChanged = {},
            onSyncNow = {},
            onMirrorConfigToCloud = {},
            onRestoreConfigFromCloud = {},
            onRegenerateDashboard = {}
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun SettingsScreenPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        SettingsContent(
            state = SettingsUiState(
                googleAccountEmail = "user@example.com"
            ),
            onSpreadsheetIdChanged = {},
            onDefaultAccountChanged = {},
            onAddAccount = { _, _ -> },
            onAddCategory = { _, _ -> },
            onToggleCategoryLivingBurnExclusion = { _, _ -> },
            onAddTripTag = {},
            onToggleTripTagActive = {},
            onAddCompanion = {},
            onRenameCompanion = { _, _ -> },
            onRemoveCompanion = {},
            onSettleDelayChanged = {},
            onSyncNow = {},
            onMirrorConfigToCloud = {},
            onRestoreConfigFromCloud = {},
            onRegenerateDashboard = {}
        )
    }
}
