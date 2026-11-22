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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.utils.InputPolicy
import com.financeautomation.presentation.components.AttentionCard
import com.financeautomation.presentation.components.FilledButton
import com.financeautomation.presentation.health.AttentionIssue
import com.financeautomation.presentation.health.AttentionState
import com.financeautomation.presentation.health.attentionContent
import com.financeautomation.presentation.health.attentionState
import com.financeautomation.presentation.components.LinkButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.OnboardingUiState
import com.financeautomation.presentation.viewmodel.OnboardingViewModel
import com.financeautomation.presentation.viewmodel.StepState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onOnboardingFinished: () -> Unit,
    modifier: Modifier = Modifier,
    /** False on the first run: leaving before setup is complete would only show this screen again. */
    showBackButton: Boolean = true
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

    val gso = remember {
        com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
        ).requestEmail().build()
    }
    val googleSignInClient = remember(context) {
        com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso)
    }
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                viewModel.onGoogleAccountDetails(
                    account.email,
                    account.displayName,
                    account.photoUrl?.toString()
                )
                viewModel.startSignIn()
            }
        } catch (_: Exception) {
            // The picker was cancelled or failed: stay on this step with the Sign in button and show nothing
        }
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

    state.pendingAccountSwitch?.let { prompt ->
        com.financeautomation.presentation.components.AccountSwitchDialog(
            prompt = prompt,
            onConfirm = viewModel::confirmAccountSwitch,
            onCancel = viewModel::cancelAccountSwitch
        )
    }

    if (state.showAccountSetupStep) {
        OnboardingAccountsSetupScreen(
            state = state,
            onAddAccount = viewModel::addAccount,
            onSetDefaultAccount = viewModel::setDefaultAccount,
            onDeleteAccount = viewModel::deleteAccount,
            onContinue = viewModel::completeAccountSetup,
            onSkip = viewModel::skipAccountSetup,
            onBack = viewModel::dismissAccountSetup,
            modifier = modifier
        )
        return
    }

    val isStep1Done = state.step1State == StepState.COMPLETED
    val isStep2Done = state.step2State == StepState.COMPLETED
    val isStep3Done = state.step3State == StepState.COMPLETED
    val isReady = isStep1Done && isStep2Done

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.onboarding_setup_title),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onOnboardingFinished) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
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
                        if (!state.isAccountsSeen) {
                            viewModel.openAccountSetup()
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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
                            Uri.parse("https://github.com/anujkdotexe/Finance-Automation-Android#readme")
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
                // Step 1: sign in, then the workbook. One shared card per problem, with the one button that fixes it.
                val signInWorking = state.step1State == StepState.IN_PROGRESS
                val workbookWorking = state.step2State == StepState.IN_PROGRESS
                if (!isStep1Done) {
                    AttentionCard(
                        content = attentionContent(AttentionIssue.SIGN_IN, attentionState(ok = false, working = signInWorking)),
                        detail = state.step1Error?.asString(context),
                        onAction = {
                            try {
                                googleSignInLauncher.launch(googleSignInClient.signInIntent)
                            } catch (_: Exception) {
                                viewModel.startSignIn()
                            }
                        }
                    )
                } else {
                    AttentionCard(
                        content = attentionContent(AttentionIssue.SIGN_IN, AttentionState.OK),
                        detail = state.googleAccountEmail,
                        onAction = {}
                    )
                    AttentionCard(
                        content = attentionContent(AttentionIssue.WORKBOOK, attentionState(ok = isStep2Done, working = workbookWorking)),
                        detail = if (isStep2Done) state.workbookName.ifBlank { null } else state.step2Error?.asString(context),
                        onAction = { viewModel.startWorkspaceProvisioning(forceValidateTabs = state.step2State == StepState.ERROR) }
                    )
                }

                // A revoked consent or an account removed from the phone: let the user pick another account
                if (state.step1State == StepState.ERROR || state.step2State == StepState.ERROR) {
                    com.financeautomation.presentation.components.OutlinedButton(
                        text = stringResource(R.string.onboarding_use_other_account),
                        onClick = {
                            try {
                                googleSignInClient.signOut().addOnCompleteListener {
                                    googleSignInLauncher.launch(googleSignInClient.signInIntent)
                                }
                            } catch (_: Exception) {
                                googleSignInLauncher.launch(googleSignInClient.signInIntent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Step 2: notification access
                AttentionCard(
                    content = attentionContent(AttentionIssue.NOTIFICATION_ACCESS, attentionState(ok = isStep3Done)),
                    onAction = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                )

                // Step 3: accounts (a default account is created with the workbook)
                AttentionCard(
                    content = attentionContent(
                        AttentionIssue.ACCOUNTS,
                        if (state.isAccountsSeen) AttentionState.OK else AttentionState.NEEDS_ATTENTION
                    ),
                    detail = if (state.isAccountsSeen) state.defaultAccountName else null,
                    onAction = { viewModel.openAccountSetup() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingAccountsSetupScreen(
    state: OnboardingUiState,
    onAddAccount: (name: String, last4: String?) -> Unit,
    onSetDefaultAccount: (id: String, name: String) -> Unit,
    onDeleteAccount: (id: String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var accountName by rememberSaveable { mutableStateOf("") }
    var lastFourDigits by rememberSaveable { mutableStateOf("") }
    // Account count when Add was tapped; the fields are cleared only once the list has grown.
    var accountCountAtAdd by remember { mutableStateOf<Int?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current

    LaunchedEffect(state.accounts, state.accountSetupError) {
        val base = accountCountAtAdd
        if (base != null) {
            if (state.accounts.size > base) {
                accountName = ""
                lastFourDigits = ""
                accountCountAtAdd = null
            } else if (state.accountSetupError != null) {
                accountCountAtAdd = null
            }
        }
    }

    deleteTarget?.let { (targetId, targetName) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = stringResource(R.string.onboarding_accounts_delete_title, targetName)) },
            text = { Text(text = stringResource(R.string.onboarding_accounts_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAccount(targetId)
                        deleteTarget = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_accounts_delete_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.onboarding_accounts_title),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
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
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth()
                )

                LinkButton(
                    text = stringResource(R.string.onboarding_accounts_skip),
                    onClick = onSkip
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
                        imageVector = Icons.Rounded.AccountBalance,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(FinanceTheme.sizes.icon28)
                    )
                }

                Text(
                    text = stringResource(R.string.onboarding_accounts_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.onboarding_accounts_subtitle),
                    style = FinanceTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Add Account Card
            Surface(
                shape = FinanceShapes.large,
                color = FinanceTheme.colors.surfaceContainerLowest,
                border = BorderStroke(FinanceTheme.sizes.strokeThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FinanceTheme.spacing.l),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    Text(
                        text = stringResource(R.string.dialog_add_account_title),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.onboarding_accounts_name_label),
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = accountName,
                            onValueChange = {
                                accountName = InputPolicy.cleanNameWhileTyping(it).take(InputPolicy.MAX_NAME_LENGTH)
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.onboarding_accounts_name_placeholder),
                                    style = FinanceTheme.typography.bodyMedium
                                )
                            },
                            singleLine = true,
                            shape = FinanceShapes.card,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.onboarding_accounts_last4_label),
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = lastFourDigits,
                            onValueChange = {
                                val clean = InputPolicy.cleanDigits4(it)
                                if (clean.length <= 4) lastFourDigits = clean
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.onboarding_accounts_last4_placeholder),
                                    style = FinanceTheme.typography.bodyMedium
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = FinanceShapes.card,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    state.accountSetupError?.let { err ->
                        Text(
                            text = err.asString(context),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    FilledButton(
                        text = stringResource(R.string.onboarding_accounts_add_button),
                        onClick = {
                            if (accountName.isNotBlank()) {
                                accountCountAtAdd = state.accounts.size
                                onAddAccount(accountName.trim(), lastFourDigits.trim().ifEmpty { null })
                            }
                        },
                        enabled = accountName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Current Accounts List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_accounts_your_accounts),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                state.accounts.forEach { account ->
                    val isDefault = account.name == state.defaultAccountName
                    val isProtected = CategoryConstants.isProtectedAccount(account.name)

                    Surface(
                        shape = FinanceShapes.card,
                        color = FinanceTheme.colors.surfaceContainerLowest,
                        border = BorderStroke(FinanceTheme.sizes.strokeThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(FinanceTheme.sizes.buttonHeightMedium)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                val icon = when {
                                    account.name.equals(CategoryConstants.ACCOUNT_CASH, ignoreCase = true) -> Icons.Rounded.Payments
                                    account.lastFourDigits != null -> Icons.Rounded.CreditCard
                                    else -> Icons.Rounded.AccountBalance
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = account.name,
                                    style = FinanceTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                account.lastFourDigits?.let { digits ->
                                    Text(
                                        text = "•••• $digits",
                                        style = FinanceTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (isDefault) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = stringResource(R.string.onboarding_accounts_default_badge),
                                        style = FinanceTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                                    )
                                }
                            } else {
                                TextButton(
                                    onClick = { onSetDefaultAccount(account.id, account.name) }
                                ) {
                                    Text(
                                        text = stringResource(R.string.onboarding_accounts_make_default),
                                        style = FinanceTheme.typography.labelSmall
                                    )
                                }
                            }

                            if (isProtected) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    Text(
                                        text = stringResource(R.string.onboarding_accounts_builtin),
                                        style = FinanceTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { deleteTarget = account.id to account.name },
                                    modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.onboarding_accounts_delete_cd, account.name),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val packageNames = NotificationManagerCompat.getEnabledListenerPackages(context)
    return packageNames.contains(context.packageName)
}
