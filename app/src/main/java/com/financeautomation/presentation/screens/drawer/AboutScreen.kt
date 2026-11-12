package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.financeautomation.BuildConfig
import com.financeautomation.presentation.screens.SettingsCard
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun AboutScreen(
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
        // 1. App Info & Version (Clickable for Developer Options)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.onVersionTapped() },
            colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surfaceVariant),
            shape = FinanceTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FinanceTheme.spacing.l),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Finance Automation",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.developerMode) {
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
                    Text(
                        text = "Developer mode unlocked",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 2. Privacy Promise
        SettingsCard(
            title = "Privacy & Security",
            subtitle = "Built on zero-leak principles",
            icon = Icons.Rounded.Shield
        ) {
            Text(
                text = "Your financial data stays strictly on your phone and your private Google Sheet. Under no circumstances does the app inspect or read WhatsApp, Telegram, personal chats, or OTP authentication messages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 3. Supported UPI Ecosystem
        SettingsCard(
            title = "Supported Apps",
            subtitle = "Active notification interception",
            icon = Icons.Rounded.Info
        ) {
            Text(
                text = "Google Pay, BHIM, Supermoney, PhonePe, Paytm, and system banking debit alerts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

