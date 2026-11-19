package com.financeautomation.presentation.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.financeautomation.R
import com.financeautomation.presentation.components.AttentionCard
import com.financeautomation.presentation.health.AttentionState
import com.financeautomation.presentation.health.attentionContent
import com.financeautomation.presentation.health.issueFor
import com.financeautomation.data.repository.CompatibilityReportCollector
import com.financeautomation.presentation.health.OemHardeningHelper
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.screens.settings.CompatibilityReportDialog
import com.financeautomation.presentation.theme.FinanceTheme
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
    val state by viewModel.uiState.collectAsState()

    var fallbackInstructionText by remember { mutableStateOf<String?>(null) }
    var postNotificationRequestedOnce by remember { mutableStateOf(false) }
    var reportDialogMode by remember { mutableStateOf<Boolean?>(null) }
    var oemFeedbackSubmitted by remember { mutableStateOf<Boolean?>(null) }

    val postNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshChecks()
        if (!isGranted) {
            val activity = context as? Activity
            val permanentlyDenied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                false
            }
            if (permanentlyDenied) {
                SetupHealthIntents.launchAppNotificationSettings(context)
            }
        }
    }

    val oemInfo = remember { OemHardeningHelper.getOemInfo() }

    // Re-check when the user comes back from a system settings screen, so a fixed row turns done at once
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshChecks()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsCard(
        title = stringResource(R.string.health_setup_title),
        subtitle = stringResource(R.string.health_setup_subtitle),
        icon = Icons.Rounded.HealthAndSafety,
        modifier = modifier
    ) {
        // 1. Force-stop warning text required by Spec 7.4
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            shape = FinanceTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(FinanceTheme.spacing.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                )
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                Text(
                    text = stringResource(R.string.health_force_stop_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        AnimatedVisibility(visible = fallbackInstructionText != null) {
            fallbackInstructionText?.let { text ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = FinanceTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = FinanceTheme.spacing.s)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(FinanceTheme.spacing.s)
                    )
                }
            }
        }

        // 2. The 7 Setup Health Check Rows
        state.checks.forEach { checkItem ->
            HealthCheckRow(
                item = checkItem,
                onFix = {
                    fallbackInstructionText = null
                    if (checkItem.id == HealthCheckId.GOOGLE_CONNECTION) {
                        if (onNavigateGoogleSignIn != null) {
                            onNavigateGoogleSignIn.invoke()
                        } else {
                            val navIntent = Intent(context, com.financeautomation.presentation.ui.MainActivity::class.java).apply {
                                putExtra("extra_route", com.financeautomation.presentation.ui.AppRoutes.SYNC_BACKUP)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            context.startActivity(navIntent)
                        }
                    } else if (checkItem.id == HealthCheckId.POST_NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val activity = context as? Activity
                        val permanentlyDenied = activity != null && ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED && !ActivityCompat.shouldShowRequestPermissionRationale(
                            activity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) && postNotificationRequestedOnce
                        if (permanentlyDenied) {
                            SetupHealthIntents.launchAppNotificationSettings(context)
                        } else {
                            postNotificationRequestedOnce = true
                            postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        SetupHealthIntents.launchSettingsWithGuardedFallback(context, checkItem, oemInfo) { instruction ->
                            fallbackInstructionText = instruction
                        }
                    }
                },
                onToggleOemDone = { done ->
                    viewModel.toggleOemCompleted(done)
                },
                oemInstruction = if (checkItem.id == HealthCheckId.OEM_BACKGROUND) {
                    stringResource(oemInfo.instructionRes)
                } else null,
                oemFeedback = oemFeedbackSubmitted,
                onOemFeedback = { openedRightScreen ->
                    oemFeedbackSubmitted = openedRightScreen
                    SetupHealthIntents.recordOemFeedback(oemInfo.brand.name, openedRightScreen)
                }
            )
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        // 3. Reporting Section
        OutlinedButton(
            onClick = { reportDialogMode = false },
            shape = FinanceTheme.shapes.card,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = null,
                modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
            )
            Spacer(modifier = Modifier.width(FinanceTheme.sizes.badgeOffset))
            Text(stringResource(R.string.health_generate_report_button))
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        OutlinedButton(
            onClick = { reportDialogMode = true },
            shape = FinanceTheme.shapes.card,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.BugReport,
                contentDescription = null,
                modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
            )
            Spacer(modifier = Modifier.width(FinanceTheme.sizes.badgeOffset))
            Text(stringResource(R.string.report_missed_payment_button))
        }
    }

    reportDialogMode?.let { isMissed ->
        CompatibilityReportDialog(
            isMissedPayment = isMissed,
            onDismiss = { reportDialogMode = null }
        )
    }
}

@Composable
private fun HealthCheckRow(
    item: HealthCheckItem,
    onFix: () -> Unit,
    onToggleOemDone: (Boolean) -> Unit,
    oemInstruction: String? = null,
    oemFeedback: Boolean? = null,
    onOemFeedback: ((Boolean) -> Unit)? = null
) {
    // Every requirement row uses the shared card; only the advice-only OEM row keeps its own layout
    val issue = issueFor(item.id)
    if (issue != null) {
        val state = when (item.status) {
            HealthStatus.OK -> AttentionState.OK
            HealthStatus.MISSING -> AttentionState.NEEDS_ATTENTION
            HealthStatus.UNKNOWN -> AttentionState.IN_PROGRESS
        }
        AttentionCard(
            content = attentionContent(issue, state),
            onAction = onFix,
            detail = if (state == AttentionState.OK) item.detailText else null
        )
        return
    }
    if (item.id == HealthCheckId.OEM_BACKGROUND) {
        com.financeautomation.presentation.screens.drawer.OemBackgroundCard(
            isOemDone = item.isOemToggleDone,
            onToggleOemDone = onToggleOemDone,
            onOpenSettings = onFix,
            oemInstruction = oemInstruction,
            oemFeedback = oemFeedback,
            onOemFeedback = { onOemFeedback?.invoke(it) },
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = FinanceTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(FinanceTheme.spacing.m)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val (statusBg, statusTint, statusIcon) = when (item.status) {
                    HealthStatus.OK -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Icons.Rounded.CheckCircle)
                    HealthStatus.MISSING -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Rounded.ErrorOutline)
                    HealthStatus.UNKNOWN -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.AutoMirrored.Rounded.HelpOutline)
                }
                Box(modifier = Modifier.size(FinanceTheme.sizes.iconLarge).background(statusBg, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                }
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(item.titleRes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = stringResource(item.descriptionRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!item.detailText.isNullOrBlank()) {
                        Text(text = stringResource(R.string.health_status_format, item.detailText), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                if (item.status == HealthStatus.MISSING && item.canFix) {
                    com.financeautomation.presentation.components.OutlinedButton(
                        text = stringResource(R.string.health_fix_button),
                        onClick = onFix
                    )
                } else if (item.status == HealthStatus.OK) {
                    Text(text = stringResource(R.string.health_status_ok), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

