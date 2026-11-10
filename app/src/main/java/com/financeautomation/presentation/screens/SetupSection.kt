package com.financeautomation.presentation.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel

/**
 * Encapsulates the setup health checklist and advanced diagnostics card.
 * Decoupled from SettingsScreen to preserve strict modular sizing.
 */
@Composable
fun SetupSection(
    setupHealthViewModel: SetupHealthViewModel?,
    overlayControllerProvider: (() -> OverlayController)? = null,
    onNavigateGoogleSignIn: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    setupHealthViewModel?.let { healthVm ->
        SetupHealthCard(
            viewModel = healthVm,
            overlayControllerProvider = overlayControllerProvider,
            onNavigateGoogleSignIn = onNavigateGoogleSignIn,
            modifier = modifier
        )
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))
    }
    AdvancedDiagnosticsCard(modifier = modifier)
}
