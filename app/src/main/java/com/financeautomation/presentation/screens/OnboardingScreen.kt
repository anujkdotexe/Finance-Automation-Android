package com.financeautomation.presentation.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.financeautomation.R
import com.financeautomation.presentation.components.FilledButton
import com.financeautomation.presentation.components.LinkButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.OnboardingUiState
import com.financeautomation.presentation.viewmodel.OnboardingViewModel
import com.financeautomation.presentation.viewmodel.StepState

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onOnboardingFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        viewModel.onConsentResult(granted)
    }

    val postNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onOnboardingFinished()
    }

    LaunchedEffect(viewModel) {
        viewModel.consentRequests.collect { pendingIntent ->
            consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasListener = isNotificationListenerEnabled(context)
                viewModel.updatePermissionsState(hasListener)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isStep1Done = state.step1State == StepState.COMPLETED
    val isStep2Done = state.step2State == StepState.COMPLETED
    val isStep3Done = state.step3State == StepState.COMPLETED
    val isReady = isStep1Done && isStep2Done

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                FilledButton(
                    text = stringResource(R.string.onboarding_checklist_continue),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onOnboardingFinished()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isReady
                )

                LinkButton(
                    text = stringResource(R.string.onboarding_checklist_trouble),
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://financeautomation.app/help")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xl)
        ) {
            // Header Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                Box(
                    modifier = Modifier
                        .size(FinanceTheme.sizes.buttonHeight)
                        .clip(FinanceShapes.card)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Checklist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(FinanceTheme.sizes.icon28)
                    )
                }

                Text(
                    text = stringResource(R.string.onboarding_checklist_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.onboarding_checklist_desc),
                    style = FinanceTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Checklist Steps
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                // Step 1: Connect Google Sheet
                ChecklistCard(
                    isCompleted = isStep1Done && isStep2Done,
                    isLoading = state.step1State == StepState.IN_PROGRESS || state.step2State == StepState.IN_PROGRESS,
                    defaultIcon = Icons.Rounded.TableChart,
                    title = stringResource(R.string.onboarding_checklist_step1_title),
                    subtitle = if (isStep2Done) {
                        state.workbookName.ifBlank { stringResource(R.string.onboarding_checklist_step1_default_sheet) }
                    } else if (isStep1Done) {
                        state.googleAccountEmail ?: stringResource(R.string.onboarding_checklist_step1_default_sheet)
                    } else {
                        stringResource(R.string.onboarding_checklist_step1_default_sheet)
                    },
                    statusText = if (isStep1Done && isStep2Done) stringResource(R.string.onboarding_checklist_step1_connected) else null,
                    actionButtonText = if (!isStep1Done) stringResource(R.string.onboarding_checklist_step1_connect) else if (state.step2State == StepState.ERROR) stringResource(R.string.action_retry_workspace) else null,
                    onActionClick = {
                        if (!isStep1Done) {
                            viewModel.startSignIn()
                        } else if (state.step2State == StepState.ERROR) {
                            viewModel.startWorkspaceProvisioning(forceValidateTabs = true)
                        }
                    }
                )

                // Step 2: Notification access
                ChecklistCard(
                    isCompleted = isStep3Done,
                    isLoading = false,
                    defaultIcon = Icons.Rounded.Notifications,
                    title = stringResource(R.string.onboarding_checklist_step2_title),
                    subtitle = stringResource(R.string.onboarding_checklist_step2_desc),
                    statusText = if (isStep3Done) stringResource(R.string.onboarding_checklist_step2_active) else null,
                    actionButtonText = if (!isStep3Done) stringResource(R.string.onboarding_checklist_step2_grant) else null,
                    onActionClick = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                )

                // Step 3: Default account
                ChecklistCard(
                    isCompleted = true,
                    isLoading = false,
                    defaultIcon = Icons.Rounded.CreditCard,
                    title = stringResource(R.string.onboarding_checklist_step3_title),
                    subtitle = stringResource(R.string.onboarding_checklist_step3_desc),
                    statusText = stringResource(R.string.onboarding_checklist_step3_configured),
                    actionButtonText = null,
                    onActionClick = null
                )
            }
        }
    }
}

@Composable
private fun ChecklistCard(
    isCompleted: Boolean,
    isLoading: Boolean,
    defaultIcon: ImageVector,
    title: String,
    subtitle: String,
    statusText: String?,
    actionButtonText: String?,
    onActionClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = FinanceTheme.sizes.strokeThin,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = FinanceShapes.large
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Box(
                    modifier = Modifier
                        .size(FinanceTheme.sizes.icon32)
                        .clip(CircleShape)
                        .background(
                            if (isCompleted) MaterialTheme.colorScheme.primaryContainer
                            else FinanceTheme.colors.surfaceContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(FinanceTheme.sizes.icon18),
                            strokeWidth = FinanceTheme.sizes.strokeThin,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (isCompleted) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(FinanceTheme.sizes.icon18)
                        )
                    } else {
                        Icon(
                            imageVector = defaultIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(FinanceTheme.sizes.icon18)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))

            if (statusText != null) {
                Text(
                    text = statusText,
                    style = FinanceTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xs)
                )
            } else if (actionButtonText != null && onActionClick != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = FinanceShapes.pill,
                    modifier = Modifier.clickable(onClick = onActionClick)
                ) {
                    Text(
                        text = actionButtonText,
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.xs)
                    )
                }
            }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val packageNames = NotificationManagerCompat.getEnabledListenerPackages(context)
    return packageNames.contains(context.packageName)
}
