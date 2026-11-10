package com.financeautomation.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.presentation.health.OemHardeningHelper
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.viewmodel.HealthCheckId
import com.financeautomation.presentation.viewmodel.HealthCheckItem
import com.financeautomation.presentation.viewmodel.HealthStatus
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel
import kotlinx.coroutines.launch

/**
 * Setup Health & System Hardening checklist card (Phase 7.1 to 7.4).
 * Re-verifies all 7 capture dependencies and displays actionable resolution intents.
 */
@Composable
fun SetupHealthCard(
    viewModel: SetupHealthViewModel,
    overlayControllerProvider: (() -> OverlayController)? = null,
    onNavigateGoogleSignIn: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state = viewModel.uiState.value

    var selfTestResultText by remember { mutableStateOf<String?>(null) }
    var selfTestSucceeded by remember { mutableStateOf<Boolean?>(null) }

    val oemInfo = remember { OemHardeningHelper.getOemInfo() }

    SettingsCard(
        title = "Setup Health & System Hardening",
        subtitle = "Verify all 7 background capture dependencies and permissions",
        icon = Icons.Rounded.HealthAndSafety,
        modifier = modifier
    ) {
        // 1. Force-stop warning text required by Spec 7.4
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.health_force_stop_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. The 7 Setup Health Check Rows
        state.checks.forEach { checkItem ->
            HealthCheckRow(
                item = checkItem,
                onFix = {
                    if (checkItem.id == HealthCheckId.GOOGLE_CONNECTION) {
                        onNavigateGoogleSignIn?.invoke()
                    } else {
                        val intent = SetupHealthViewModel.createFixIntent(checkItem.id, context)
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val fallback = SetupHealthViewModel.getAppDetailsSettingsIntent(context)
                            context.startActivity(fallback)
                        }
                    }
                },
                onToggleOemDone = { done ->
                    viewModel.toggleOemCompleted(done)
                },
                oemInstruction = if (checkItem.id == HealthCheckId.OEM_BACKGROUND) {
                    stringResource(oemInfo.instructionRes)
                } else null
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Self-Test Simulation Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val controller = overlayControllerProvider?.invoke()
                    if (controller == null) {
                        selfTestResultText = context.getString(R.string.health_self_test_failed)
                        selfTestSucceeded = false
                        return@Button
                    }
                    coroutineScope.launch {
                        val passed = controller.showSelfTest()
                        selfTestSucceeded = passed
                        selfTestResultText = if (passed) {
                            context.getString(R.string.health_self_test_success)
                        } else {
                            context.getString(R.string.health_self_test_failed)
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.health_self_test_button))
            }
        }

        AnimatedVisibility(visible = selfTestResultText != null) {
            selfTestResultText?.let { text ->
                val isSuccess = selfTestSucceeded == true
                Surface(
                    color = if (isSuccess) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthCheckRow(
    item: HealthCheckItem,
    onFix: () -> Unit,
    onToggleOemDone: (Boolean) -> Unit,
    oemInstruction: String? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator icon
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = when (item.status) {
                                HealthStatus.OK -> MaterialTheme.colorScheme.tertiaryContainer
                                HealthStatus.MISSING -> MaterialTheme.colorScheme.errorContainer
                                HealthStatus.UNKNOWN -> MaterialTheme.colorScheme.secondaryContainer
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (item.status) {
                            HealthStatus.OK -> Icons.Rounded.CheckCircle
                            HealthStatus.MISSING -> Icons.Rounded.ErrorOutline
                            HealthStatus.UNKNOWN -> Icons.Rounded.HelpOutline
                        },
                        contentDescription = null,
                        tint = when (item.status) {
                            HealthStatus.OK -> MaterialTheme.colorScheme.onTertiaryContainer
                            HealthStatus.MISSING -> MaterialTheme.colorScheme.onErrorContainer
                            HealthStatus.UNKNOWN -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(item.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!item.detailText.isNullOrBlank()) {
                        Text(
                            text = "Status: ${item.detailText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Action button or status badge
                if (item.status == HealthStatus.MISSING && item.canFix) {
                    Button(
                        onClick = onFix,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.health_fix_button),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else if (item.status == HealthStatus.OK) {
                    Text(
                        text = stringResource(R.string.health_status_ok),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // Special OEM guidance and manual toggle
            if (item.id == HealthCheckId.OEM_BACKGROUND) {
                Spacer(modifier = Modifier.height(8.dp))
                if (!oemInstruction.isNullOrBlank()) {
                    Text(
                        text = oemInstruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 34.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.isOemToggleDone,
                        onCheckedChange = onToggleOemDone,
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = "Configured on this device",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onFix,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = "Open OEM settings",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

