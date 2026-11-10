package com.financeautomation.presentation.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    com.financeautomation.presentation.screens.SettingsScreen(
        viewModel = viewModel,
        modifier = modifier
    )
}
