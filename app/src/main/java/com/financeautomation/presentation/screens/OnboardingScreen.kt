package com.financeautomation.presentation.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.financeautomation.R
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = FinanceTheme.spacing.xl)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xl))

            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

            Text(
                text = stringResource(R.string.onboarding_welcome_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxl))

            // Step 1: Sign In
            OnboardingStepItem(
                stepNumber = 1,
                title = stringResource(R.string.onboarding_step1_title),
                subtitle = when (state.step1State) {
                    StepState.COMPLETED -> stringResource(R.string.onboarding_step1_completed)
                    StepState.IN_PROGRESS -> stringResource(R.string.onboarding_step1_progress)
                    StepState.ERROR -> state.step1Error ?: stringResource(R.string.onboarding_step1_not_started)
                    StepState.NOT_STARTED -> stringResource(R.string.onboarding_step1_not_started)
                },
                icon = Icons.Default.Lock,
                state = state.step1State,
                action = {
                    if (state.step1State == StepState.NOT_STARTED || state.step1State == StepState.ERROR) {
                        Button(
                            onClick = viewModel::startSignIn,
                            shape = FinanceTheme.shapes.small
                        ) {
                            Text(stringResource(R.string.onboarding_step1_title))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

            // Step 2: Creating Finance Workspace
            OnboardingStepItem(
                stepNumber = 2,
                title = stringResource(R.string.onboarding_step2_title),
                subtitle = when (state.step2State) {
                    StepState.COMPLETED -> state.workbookName
                    StepState.IN_PROGRESS -> stringResource(R.string.onboarding_step2_progress)
                    StepState.ERROR -> state.step2Error ?: ""
                    StepState.NOT_STARTED -> ""
                },
                icon = Icons.Default.TableChart,
                state = state.step2State,
                action = {
                    if (state.step2State == StepState.ERROR) {
                        OutlinedButton(
                            onClick = { viewModel.startWorkspaceProvisioning(forceValidateTabs = true) },
                            shape = FinanceTheme.shapes.small
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                            Spacer(modifier = Modifier.width(FinanceTheme.sizes.badgeOffset))
                            Text(stringResource(R.string.action_retry_workspace))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

            // Step 3: Permissions
            OnboardingStepItem(
                stepNumber = 3,
                title = stringResource(R.string.onboarding_step3_title),
                subtitle = when (state.step3State) {
                    StepState.COMPLETED -> stringResource(R.string.onboarding_step3_completed)
                    else -> stringResource(R.string.onboarding_step3_desc)
                },
                icon = Icons.Default.Notifications,
                state = state.step3State,
                action = {
                    if (state.step3State != StepState.COMPLETED) {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            },
                            shape = FinanceTheme.shapes.small
                        ) {
                            Text(stringResource(R.string.action_grant_permission))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

            // Step 4: Ready
            OnboardingStepItem(
                stepNumber = 4,
                title = stringResource(R.string.onboarding_step4_title),
                subtitle = if (state.step4State == StepState.COMPLETED) {
                    stringResource(R.string.onboarding_step4_completed)
                } else {
                    stringResource(R.string.onboarding_step4_pending)
                },
                icon = Icons.Default.Check,
                state = state.step4State,
                action = {
                    AnimatedVisibility(visible = state.step4State == StepState.COMPLETED) {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onOnboardingFinished()
                                }
                            },
                            shape = FinanceTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(stringResource(R.string.action_get_started))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxl))
        }
    }
}

@Composable
private fun OnboardingStepItem(
    stepNumber: Int,
    title: String,
    subtitle: String,
    icon: ImageVector,
    state: StepState,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = FinanceTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(FinanceTheme.spacing.l)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(FinanceTheme.sizes.avatarSmall)
                        .clip(CircleShape)
                        .background(
                            when (state) {
                                StepState.COMPLETED -> MaterialTheme.colorScheme.primary
                                StepState.ERROR -> MaterialTheme.colorScheme.error
                                StepState.IN_PROGRESS -> MaterialTheme.colorScheme.secondary
                                StepState.NOT_STARTED -> MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (state) {
                        StepState.COMPLETED -> Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                        )
                        StepState.IN_PROGRESS -> CircularProgressIndicator(
                            modifier = Modifier.size(FinanceTheme.sizes.iconMedium),
                            color = MaterialTheme.colorScheme.onSecondary,
                            strokeWidth = FinanceTheme.sizes.strokeThick
                        )
                        StepState.ERROR -> Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                        )
                        StepState.NOT_STARTED -> Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(FinanceTheme.spacing.l))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state == StepState.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                action()
            }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val packages = NotificationManagerCompat.getEnabledListenerPackages(context)
    return packages.contains(context.packageName)
}

