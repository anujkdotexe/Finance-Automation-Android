package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.screens.HomeScreenIconCard
import com.financeautomation.presentation.screens.SetupSection
import com.financeautomation.presentation.screens.settings.CaptureHealthCard
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel

@Composable
fun CapturePermissionsScreen(
    state: SettingsUiState,
    setupHealthViewModel: SetupHealthViewModel?,
    overlayControllerProvider: (() -> OverlayController)? = null,
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
        SetupSection(
            setupHealthViewModel = setupHealthViewModel,
            overlayControllerProvider = overlayControllerProvider,
            onNavigateGoogleSignIn = onReRunSetup,
            developerMode = state.developerMode
        )

        HomeScreenIconCard()

        if (state.developerMode) {
            CaptureHealthCard(modifier = Modifier.fillMaxWidth())
        }
    }
}

