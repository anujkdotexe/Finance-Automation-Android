package com.financeautomation.presentation.screens.drawer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.presentation.components.AccountAvatar
import com.financeautomation.presentation.components.AppCard
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun SyncBackupScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onReRunSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val signedInAccount = remember {
        try {
            com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
        } catch (_: Exception) {
            null
        }
    }
    val rawEmail = signedInAccount?.email ?: state.googleAccountEmail
    val isRealEmail = !rawEmail.isNullOrBlank() && rawEmail.contains("@")
    val accountEmail = if (isRealEmail) rawEmail else null

    val rawDisplayName = signedInAccount?.displayName?.takeIf { it.isNotBlank() }
        ?: state.googleAccountDisplayName?.takeIf { it.isNotBlank() }
        ?: if (!isRealEmail && !rawEmail.isNullOrBlank()) rawEmail else null
    val accountDisplayName = rawDisplayName?.takeIf { it != accountEmail }

    val showNameAndEmail = !accountDisplayName.isNullOrBlank() && !accountEmail.isNullOrBlank()

    var showCreateFreshDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    val gso = remember {
        com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
        ).requestEmail().build()
    }
    val googleSignInClient = remember(context) {
        com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso)
    }
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                viewModel.onGoogleSignInSuccess(
                    account.email ?: "",
                    account.displayName,
                    account.photoUrl?.toString()
                )
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(state.userMessage) {
        if (!state.userMessage.isNullOrBlank()) {
            Toast.makeText(context, state.userMessage, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(state.errorMessage) {
        if (!state.errorMessage.isNullOrBlank()) {
            Toast.makeText(context, state.errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.sync_disconnect_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.sync_disconnect_body),
                    style = FinanceTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutDialog = false
                        viewModel.onGoogleSignOut(context)
                    }
                ) {
                    Text(stringResource(R.string.sync_disconnect_action))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showSignOutDialog = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showCreateFreshDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFreshDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.create_fresh_workbook_confirm_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.create_fresh_workbook_confirm_body),
                    style = FinanceTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreateFreshDialog = false
                        viewModel.onCreateFreshWorkbook()
                    }
                ) {
                    Text(stringResource(R.string.action_create))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showCreateFreshDialog = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        Text(
            text = stringResource(R.string.sync_cloud_storage),
            style = FinanceTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Main Cloud Storage Card
        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Google Account
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    AccountAvatar(
                        email = accountEmail ?: state.googleAccountEmail
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sync_google_account).uppercase(),
                            style = FinanceTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (showNameAndEmail) {
                            Text(
                                text = accountDisplayName!!,
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = accountEmail!!,
                                style = FinanceTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (!accountEmail.isNullOrBlank()) {
                            Text(
                                text = accountEmail,
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = accountDisplayName ?: "Local offline storage",
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    val isConnected = !accountEmail.isNullOrBlank() || !state.googleAccountEmail.isNullOrBlank()
                    if (isConnected) {
                        OutlinedButton(
                            onClick = { showSignOutDialog = true },
                            shape = FinanceTheme.shapes.small,
                            modifier = Modifier.padding(start = FinanceTheme.spacing.xs)
                        ) {
                            Text(stringResource(R.string.action_sign_out), style = FinanceTheme.typography.labelMedium)
                        }
                    } else {
                        Button(
                            onClick = {
                                signInLauncher.launch(googleSignInClient.signInIntent)
                            },
                            shape = FinanceTheme.shapes.small,
                            modifier = Modifier.padding(start = FinanceTheme.spacing.xs)
                        ) {
                            Text(stringResource(R.string.sync_sign_in), style = FinanceTheme.typography.labelMedium)
                        }
                    }
                }

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 2. Workbook
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
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
                                .size(FinanceTheme.sizes.buttonHeightMedium)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.TableChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sync_workbook).uppercase(),
                                style = FinanceTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (state.spreadsheetId.isNotBlank()) "Finance Ledger 2026" else "No workbook linked",
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (state.spreadsheetId.isNotBlank()) {
                        TextButton(
                            onClick = {
                                val url = "https://docs.google.com/spreadsheets/d/${state.spreadsheetId}"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                )
                                Text(
                                    text = stringResource(R.string.sync_open_in_sheets),
                                    style = FinanceTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // 3. Last Sync
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    Box(
                        modifier = Modifier
                            .size(FinanceTheme.sizes.buttonHeightMedium)
                            .clip(CircleShape)
                            .background(FinanceTheme.colors.surfaceContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sync_last_sync).uppercase(),
                            style = FinanceTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (state.isSyncing) "Syncing..." else "Synced 2m ago",
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                HorizontalDivider(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                )

                // Action Buttons
                Box(modifier = Modifier.padding(FinanceTheme.spacing.m)) {
                    ButtonPair(
                        outlinedText = stringResource(R.string.sync_btn_backup_now),
                        onOutlinedClick = viewModel::onMirrorConfigToCloud,
                        filledText = stringResource(R.string.sync_btn_sync_now),
                        onFilledClick = viewModel::onSyncNow,
                        outlinedEnabled = !state.isConfigSyncing,
                        filledEnabled = !state.isSyncing,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Advanced Section
        Text(
            text = stringResource(R.string.sync_advanced),
            style = FinanceTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        AppCard(
            containerColor = FinanceTheme.colors.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                if (state.developerMode) {
                    var spreadsheetInput by remember(state.spreadsheetId) { mutableStateOf(state.spreadsheetId) }
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.settings_spreadsheet_id_label),
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = spreadsheetInput,
                            onValueChange = { spreadsheetInput = it },
                            placeholder = { Text(stringResource(R.string.settings_spreadsheet_id_placeholder), style = FinanceTheme.typography.bodyMedium) },
                            singleLine = true,
                            shape = FinanceShapes.card,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.onSpreadsheetIdChanged(spreadsheetInput) },
                        shape = FinanceShapes.pill,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = null,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text(stringResource(R.string.settings_save_id))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    OutlinedButton(
                        onClick = viewModel::onMirrorConfigToCloud,
                        enabled = !state.isConfigSyncing,
                        shape = FinanceTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.sync_mirror),
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::onRestoreConfigFromCloud,
                        enabled = !state.isConfigSyncing,
                        shape = FinanceTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.sync_restore),
                            maxLines = 1
                        )
                    }
                }

                OutlinedButton(
                    onClick = viewModel::onRegenerateDashboard,
                    enabled = !state.isRegeneratingDashboard && !state.isProvisioning,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.sync_regenerate_formulas),
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = viewModel::autoProvisionMasterSheet,
                    enabled = !state.isProvisioning,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.action_repair_workbook),
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = { showCreateFreshDialog = true },
                    enabled = !state.isProvisioning,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.action_create_fresh_workbook),
                        maxLines = 1
                    )
                }

                if (state.isProvisioning) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = FinanceTheme.spacing.xs),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = FinanceTheme.sizes.strokeThick,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                        Text(
                            text = stringResource(R.string.sync_provisioning),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
