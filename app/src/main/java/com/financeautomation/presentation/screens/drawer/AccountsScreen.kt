package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.presentation.screens.settings.SettingsAccountSection
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun AccountsScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onReRunSetup: () -> Unit = {},
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
        SettingsAccountSection(
            state = state,
            onDefaultAccountChanged = viewModel::onDefaultAccountChanged,
            onAddAccount = viewModel::onAddAccount,
            onAddCompanion = viewModel::onAddCompanion,
            onRenameCompanion = viewModel::onRenameCompanion,
            onRemoveCompanion = viewModel::onRemoveCompanion,
            onReRunSetup = onReRunSetup,
            onSignOut = viewModel::onGoogleSignOut
        )
    }
}

