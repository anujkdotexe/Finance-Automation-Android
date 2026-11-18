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
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PrivacyScrubber
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.presentation.components.AccountAvatar
import com.financeautomation.presentation.components.AppCard
import com.financeautomation.presentation.util.MonthCsvExporter
import kotlinx.coroutines.launch
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

    // Only the stored account is shown; Play Services' last account may be one the app refused
    val rawEmail = state.googleAccountEmail
    val isRealEmail = !rawEmail.isNullOrBlank() && rawEmail.contains("@")
    val accountEmail = if (isRealEmail) rawEmail else null

    val rawDisplayName = state.googleAccountDisplayName?.takeIf { it.isNotBlank() }
        ?: if (!isRealEmail && !rawEmail.isNullOrBlank()) rawEmail else null
    val accountDisplayName = rawDisplayName?.takeIf { it != accountEmail }

    val showNameAndEmail = !accountDisplayName.isNullOrBlank() && !accountEmail.isNullOrBlank()

    var showCreateFreshDialog by rememberSaveable { mutableStateOf(false) }
    var showRepairArchiveDialog by rememberSaveable { mutableStateOf(false) }
    var repairBackupCount by rememberSaveable { mutableStateOf(0) }
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
        state.userMessage?.let {
            Toast.makeText(context, it.asString(context), Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            Toast.makeText(context, it.asString(context), Toast.LENGTH_LONG).show()
            viewModel.clearMessages()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    state.pendingAccountSwitch?.let { prompt ->
        com.financeautomation.presentation.components.AccountSwitchDialog(
            prompt = prompt,
            onConfirm = viewModel::confirmAccountSwitch,
            onCancel = viewModel::cancelAccountSwitch
        )
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
                    text = stringResource(R.string.fresh_workbook_dialog_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.fresh_workbook_dialog_body),
                    style = FinanceTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    Button(
                        onClick = {
                            showCreateFreshDialog = false
                            viewModel.onCreateFreshWorkbook(copyHistory = true)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.fresh_workbook_action_copy_history))
                    }
                    OutlinedButton(
                        onClick = {
                            showCreateFreshDialog = false
                            viewModel.onCreateFreshWorkbook(copyHistory = false)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.fresh_workbook_action_start_empty))
                    }
                    TextButton(
                        onClick = { showCreateFreshDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
            dismissButton = null
        )
    }

    if (showRepairArchiveDialog) {
        val currentYear = remember { java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).year }
        AlertDialog(
            onDismissRequest = { showRepairArchiveDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.repair_backup_dialog_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                val bodyText = if (repairBackupCount == 1) {
                    stringResource(R.string.repair_backup_dialog_body_one, currentYear, 2)
                } else {
                    stringResource(R.string.repair_backup_dialog_body_many, repairBackupCount, currentYear, repairBackupCount + 1)
                }
                Text(
                    text = bodyText,
                    style = FinanceTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    Button(
                        onClick = {
                            showRepairArchiveDialog = false
                            viewModel.onRepairWorkbook(archiveOlder = true)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.repair_backup_action_archive))
                    }
                    OutlinedButton(
                        onClick = {
                            showRepairArchiveDialog = false
                            viewModel.onRepairWorkbook(archiveOlder = false)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.repair_backup_action_keep))
                    }
                    TextButton(
                        onClick = { showRepairArchiveDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
            dismissButton = null
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
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
                                text = accountDisplayName.orEmpty(),
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = accountEmail.orEmpty(),
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
                                text = if (state.spreadsheetId.isNotBlank()) {
                                    state.workbookName?.takeIf { it.isNotBlank() } ?: "Finance${java.time.Year.now().value}"
                                } else {
                                    stringResource(R.string.sync_not_connected)
                                },
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
                            text = if (state.isSyncing) stringResource(R.string.sync_syncing) else stringResource(R.string.sync_synced_recent),
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

                OutlinedButton(
                    onClick = viewModel::onCheckAllMonths,
                    enabled = !state.isCheckingAllMonths && !state.isSyncing,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FinanceTheme.spacing.m)
                        .padding(bottom = FinanceTheme.spacing.m)
                ) {
                    if (state.isCheckingAllMonths) {
                        CircularProgressIndicator(
                            strokeWidth = FinanceTheme.sizes.strokeThick,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                        Text(stringResource(R.string.sync_checking_all_months))
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text(stringResource(R.string.sync_btn_check_all_months))
                    }
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
                    onClick = {
                        coroutineScope.launch {
                            val count = viewModel.checkBackupsBeforeRepair()
                            if (count > 0) {
                                repairBackupCount = count
                                showRepairArchiveDialog = true
                            } else {
                                viewModel.onRepairWorkbook(archiveOlder = false)
                            }
                        }
                    },
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

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            MonthCsvExporter.exportMonth(context, 0)
                                .onFailure { e ->
                                    Diagnostics.record("SyncBackupScreen", "export_failed: ${SyncErrorKind.safeCode("export", e)} ${PrivacyScrubber.scrub(e.message.orEmpty())}")
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.export_failed, SyncErrorKind.safeCode("export", e))
                                    )
                                }
                        }
                    },
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.action_export_this_month),
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
