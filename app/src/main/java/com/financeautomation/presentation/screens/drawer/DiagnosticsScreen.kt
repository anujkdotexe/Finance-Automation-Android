package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.financeautomation.BuildConfig
import com.financeautomation.R
import com.financeautomation.core.utils.CrashlyticsManager
import com.financeautomation.presentation.screens.AdvancedDiagnosticsCard
import com.financeautomation.presentation.screens.settings.CaptureHealthCard
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.service.worker.SubscriptionReminderWorker

@Composable
fun DiagnosticsScreen(
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
        if (!state.developerMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surfaceVariant),
                shape = FinanceTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FinanceTheme.spacing.l),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(FinanceTheme.sizes.iconLarge),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
                    Text(
                        text = "Diagnostics Locked",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
                    Text(
                        text = "Developer options are currently disabled. Open About and tap the version number 7 times to unlock.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            AdvancedDiagnosticsCard(modifier = Modifier.fillMaxWidth())

            CaptureHealthCard(modifier = Modifier.fillMaxWidth())

            val appContext = LocalContext.current.applicationContext
            OutlinedButton(
                onClick = { SubscriptionReminderWorker.runNow(appContext) },
                shape = FinanceTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run subscription check now")
            }

            if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "beta") {
                OutlinedButton(
                    onClick = { CrashlyticsManager.triggerTestCrash() },
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.btn_test_crash))
                }
            }
        }
    }
}

