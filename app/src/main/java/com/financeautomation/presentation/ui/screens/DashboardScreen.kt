package com.financeautomation.presentation.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.presentation.viewmodel.DashboardViewModel

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    com.financeautomation.presentation.screens.DashboardScreen(
        viewModel = viewModel,
        modifier = modifier
    )
}

