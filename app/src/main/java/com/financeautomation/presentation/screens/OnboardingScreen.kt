package com.financeautomation.presentation.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to Finance Automation",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Complete the checklist below to set up your personal Google Drive workspace and automated notification interception.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Step 1: Sign In
            OnboardingStepItem(
                stepNumber = 1,
                title = "Sign in with Google",
                subtitle = when (state.step1State) {
                    StepState.COMPLETED -> "Signed in and authorized for Google Drive sync"
                    StepState.IN_PROGRESS -> "Authorizing through Google Identity..."
                    StepState.ERROR -> state.step1Error ?: "Authorization failed"
                    StepState.NOT_STARTED -> "Authorizes drive.file scope to manage your master workbook"
                },
                icon = Icons.Default.Lock,
                state = state.step1State,
                action = {
                    if (state.step1State == StepState.NOT_STARTED || state.step1State == StepState.ERROR) {
                        Button(
                            onClick = viewModel::startSignIn,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Sign in with Google")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step 2: Creating Finance Workspace
            OnboardingStepItem(
                stepNumber = 2,
                title = "Creating your finance workspace",
                subtitle = when (state.step2State) {
                    StepState.COMPLETED -> "Master workbook '${state.workbookName}' ready"
                    StepState.IN_PROGRESS -> "Locating or provisioning master workbook..."
                    StepState.ERROR -> state.step2Error ?: "Workspace creation failed"
                    StepState.NOT_STARTED -> "Provisions Finance${state.workbookName.takeLast(4)} and Config tabs"
                },
                icon = Icons.Default.TableChart,
                state = state.step2State,
                action = {
                    if (state.step2State == StepState.ERROR) {
                        OutlinedButton(
                            onClick = viewModel::startWorkspaceProvisioning,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Workspace Setup")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step 3: Permissions
            OnboardingStepItem(
                stepNumber = 3,
                title = "Notification listener permission",
                subtitle = when (state.step3State) {
                    StepState.COMPLETED -> "Notification listener enabled"
                    else -> "Required to intercept UPI and banking debit alerts in real time"
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
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step 4: Ready
            OnboardingStepItem(
                stepNumber = 4,
                title = "Ready",
                subtitle = if (state.step4State == StepState.COMPLETED) {
                    "Your workspace is ready. You can now track and review expenses."
                } else {
                    "Complete all steps above to finish setup"
                },
                icon = Icons.Default.Check,
                state = state.step4State,
                action = {
                    AnimatedVisibility(visible = state.step4State == StepState.COMPLETED) {
                        Button(
                            onClick = onOnboardingFinished,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Get Started")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
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
                            modifier = Modifier.size(20.dp)
                        )
                        StepState.IN_PROGRESS -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onSecondary,
                            strokeWidth = 2.dp
                        )
                        StepState.ERROR -> Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(20.dp)
                        )
                        StepState.NOT_STARTED -> Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

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

            Spacer(modifier = Modifier.height(12.dp))

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

