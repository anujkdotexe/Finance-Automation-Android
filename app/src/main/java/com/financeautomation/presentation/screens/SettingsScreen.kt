package com.financeautomation.presentation.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.platform.LocalContext
import com.financeautomation.service.QuickLogWidgetProvider
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

/**
 * Modernized Settings and Configuration screen supporting 100% config-driven customization.
 * Styled with crisp fintech cards, iconography, and structured sections.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onReRunSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage, state.errorMessage) {
        state.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        SettingsContent(
            state = state,
            onReRunSetup = onReRunSetup,
            onSignOut = viewModel::onGoogleSignOut,
            onSpreadsheetIdChanged = viewModel::onSpreadsheetIdChanged,
            onDefaultAccountChanged = viewModel::onDefaultAccountChanged,
            onAddAccount = viewModel::onAddAccount,
            onArchiveAccount = viewModel::onArchiveAccount,
            onAddCategory = viewModel::onAddCategory,
            onToggleCategoryLivingBurnExclusion = viewModel::onToggleCategoryLivingBurnExclusion,
            onArchiveCategory = viewModel::onArchiveCategory,
            onAddTripTag = viewModel::onAddTripTag,
            onToggleTripTagActive = viewModel::onToggleTripTagActive,
            onAddCompanion = viewModel::onAddCompanion,
            onRenameCompanion = viewModel::onRenameCompanion,
            onRemoveCompanion = viewModel::onRemoveCompanion,
            onSettleDelayChanged = viewModel::onSettleDelayChanged,
            onSyncNow = viewModel::onSyncNow,
            onMirrorConfigToCloud = viewModel::onMirrorConfigToCloud,
            onRestoreConfigFromCloud = viewModel::onRestoreConfigFromCloud,
            onRegenerateDashboard = viewModel::onRegenerateDashboard,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

/**
 * Stateless Settings content composable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onReRunSetup: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onSpreadsheetIdChanged: (String) -> Unit,
    onDefaultAccountChanged: (String) -> Unit,
    onAddAccount: (String, String?) -> Unit,
    onArchiveAccount: (String) -> Unit,
    onAddCategory: (String, Boolean) -> Unit,
    onToggleCategoryLivingBurnExclusion: (String, Boolean) -> Unit,
    onArchiveCategory: (String) -> Unit,
    onAddTripTag: (String) -> Unit,
    onToggleTripTagActive: (String) -> Unit,
    onAddCompanion: (String) -> Unit,
    onRenameCompanion: (String, String) -> Unit,
    onRemoveCompanion: (String) -> Unit,
    onSettleDelayChanged: (Int) -> Unit,
    onSyncNow: () -> Unit,
    onMirrorConfigToCloud: () -> Unit,
    onRestoreConfigFromCloud: () -> Unit,
    onRegenerateDashboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    var spreadsheetInput by remember(state.spreadsheetId) { mutableStateOf(state.spreadsheetId) }
    var newAccountInput by remember { mutableStateOf("") }
    var newCategoryInput by remember { mutableStateOf("") }
    var newCategoryBurnExclude by remember { mutableStateOf(false) }
    var newTagInput by remember { mutableStateOf("") }
    var newCompanionInput by remember { mutableStateOf("") }

    var renameCompanionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renameInput by remember { mutableStateOf("") }

    var sliderValue by remember(state.settleDelaySeconds) { mutableFloatStateOf(state.settleDelaySeconds.toFloat()) }

    val context = LocalContext.current
    val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Card 0: Google Sign-In & Automated Drive Provisioning ---
        SettingsCard(
            title = "Google Identity & Master Ledger",
            subtitle = "OAuth authentication and automatic Google Drive spreadsheet provisioning",
            icon = Icons.Rounded.AccountCircle
        ) {
            if (state.googleAccountEmail != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Authenticated with Google",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = state.googleAccountEmail.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onReRunSetup,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Re-run Setup")
                    }

                    OutlinedButton(
                        onClick = onSignOut,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sign Out")
                    }
                }
            } else {
                Text(
                    text = "Google Drive sync is not configured. Run the setup checklist to connect your account and workbook.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onReRunSetup,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Setup")
                }
            }
        }

        // --- Card 0.5: Overlay Popup Permission ---
        SettingsCard(
            title = "Display Over Other Apps",
            subtitle = "Automatic modal bottom sheet popup after UPI payment completes",
            icon = Icons.Rounded.Layers
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasOverlayPermission) "Overlay Popup Active" else "Overlay Permission Not Granted",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasOverlayPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = if (hasOverlayPermission) {
                            "Bottom sheet pops up automatically after payment"
                        } else {
                            "Falls back to high-priority heads-up notification"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!hasOverlayPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Enable")
                    }
                }
            }
        }

        // --- Card 0.75: Home Screen 1-Tap Quick Log Widget ---
        SettingsCard(
            title = "Home Screen 1-Tap Widget",
            subtitle = "1x1 app icon sized widget for instant manual expense logging",
            icon = Icons.Rounded.Widgets
        ) {
            Text(
                text = "Add a 1x1 Quick Log icon to your Home Screen. Tapping it pops up the Apple prompt sheet directly over your wallpaper without opening the main app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
                        val myProvider = ComponentName(context, QuickLogWidgetProvider::class.java)
                        if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                            appWidgetManager.requestPinAppWidget(myProvider, null, null)
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.Widgets, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pin 1x1 Widget")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Button(
                        onClick = {
                            val shortcutManager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
                            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                                val pinIntent = Intent(context, com.financeautomation.presentation.ui.ModalBottomSheetActivity::class.java).apply {
                                    action = Intent.ACTION_VIEW
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                val shortcut = android.content.pm.ShortcutInfo.Builder(context, "quick_log_shortcut")
                                    .setShortLabel("Quick Log")
                                    .setLongLabel("Quick Log Expense")
                                    .setIcon(android.graphics.drawable.Icon.createWithResource(context, com.financeautomation.R.drawable.ic_quick_log_symbol))
                                    .setIntent(pinIntent)
                                    .build()
                                shortcutManager.requestPinShortcut(shortcut, null)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pin App Icon")
                    }
                }
            }
        }

        // --- Card 1: Google Sheets Connection & Cloud Sync ---
        SettingsCard(
            title = "Google Sheets & Cloud Sync",
            subtitle = "Master Ledger integration and automated two-way cloud sync",
            icon = Icons.Rounded.CloudSync
        ) {
            OutlinedTextField(
                value = spreadsheetInput,
                onValueChange = { spreadsheetInput = it },
                label = { Text("Google Spreadsheet ID") },
                placeholder = { Text("Paste Google Sheet ID from URL") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSpreadsheetIdChanged(spreadsheetInput) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save ID")
                }

                OutlinedButton(
                    onClick = onSyncNow,
                    enabled = !state.isSyncing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Now")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onMirrorConfigToCloud,
                    enabled = !state.isConfigSyncing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Mirror Config")
                }

                OutlinedButton(
                    onClick = onRestoreConfigFromCloud,
                    enabled = !state.isConfigSyncing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restore Config")
                }
            }

            Button(
                onClick = onRegenerateDashboard,
                enabled = !state.isRegeneratingDashboard,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isRegeneratingDashboard) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(imageVector = Icons.Rounded.CloudDone, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Regenerate Dashboard Formulas")
                }
            }
        }

        // --- Card 2: Bank & Funding Accounts ---
        SettingsCard(
            title = "Bank & Funding Accounts",
            subtitle = "Select an account chip to set default. Add custom accounts anytime.",
            icon = Icons.Rounded.AccountBalance
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.accounts.forEach { account ->
                    val isDefault = account.name.equals(state.defaultAccount, ignoreCase = true)
                    FilterChip(
                        selected = isDefault,
                        onClick = { onDefaultAccountChanged(account.name) },
                        label = {
                            Text(
                                text = if (isDefault) "${account.name} (Default)" else account.name,
                                fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (isDefault) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Default",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newAccountInput,
                    onValueChange = { newAccountInput = it },
                    label = { Text("New Account Name (e.g. HDFC, ICICI)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newAccountInput.isNotBlank()) {
                            onAddAccount(newAccountInput, null)
                            newAccountInput = ""
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        // --- Card 3: Expense Categories & Living Burn ---
        SettingsCard(
            title = "Expense Categories & Living Burn",
            subtitle = "Excluded categories are omitted from Monthly Living Burn KPIs (e.g. Savings, Transfers).",
            icon = Icons.Rounded.Category
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.categories.forEach { category ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (category.isExcludedFromLivingBurn) "Excluded from Living Burn" else "Included in Living Burn",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (category.isExcludedFromLivingBurn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Switch(
                                checked = category.isExcludedFromLivingBurn,
                                onCheckedChange = { isChecked ->
                                    onToggleCategoryLivingBurnExclusion(category.id, isChecked)
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newCategoryInput,
                    onValueChange = { newCategoryInput = it },
                    label = { Text("New Category Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newCategoryInput.isNotBlank()) {
                            onAddCategory(newCategoryInput, newCategoryBurnExclude)
                            newCategoryInput = ""
                            newCategoryBurnExclude = false
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Checkbox(
                    checked = newCategoryBurnExclude,
                    onCheckedChange = { newCategoryBurnExclude = it }
                )
                Text(
                    text = "Exclude from Living Burn",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // --- Card 4: Trip & Event Tags ---
        SettingsCard(
            title = "Trip & Event Tags",
            subtitle = if (state.activeTripTag.isNotBlank()) "Active Tag: ${state.activeTripTag}" else "No trip tag active.",
            icon = Icons.Rounded.FlightTakeoff
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.tripTags.forEach { tag ->
                    FilterChip(
                        selected = tag.isActive,
                        onClick = { onToggleTripTagActive(tag.id) },
                        label = { Text(tag.name) },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newTagInput,
                    onValueChange = { newTagInput = it },
                    label = { Text("New Trip Tag (e.g. Goa 2026)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newTagInput.isNotBlank()) {
                            onAddTripTag(newTagInput)
                            newTagInput = ""
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        // --- Card 5: Friends & Companions ---
        SettingsCard(
            title = "Friends & Companions",
            subtitle = "Tap a friend chip to rename or delete. Used for 1-tap bill splitting.",
            icon = Icons.Rounded.People
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.companions.forEach { companion ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            renameCompanionTarget = companion.id to companion.name
                            renameInput = companion.name
                        },
                        label = { Text(companion.name) },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newCompanionInput,
                    onValueChange = { newCompanionInput = it },
                    label = { Text("New Friend Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newCompanionInput.isNotBlank()) {
                            onAddCompanion(newCompanionInput)
                            newCompanionInput = ""
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        // --- Card 6: Background Settle Delay ---
        SettingsCard(
            title = "Background Settle Buffer",
            subtitle = "Wait window before dispatching cloud sync: ${sliderValue.toInt()}s (Range: 30s to 300s, default 120s)",
            icon = Icons.Rounded.Timer
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSettleDelayChanged(sliderValue.toInt()) },
                valueRange = 30f..300f,
                steps = 8
            )
        }
    }

    // Rename Companion Dialog
    renameCompanionTarget?.let { (id, _) ->
        AlertDialog(
            onDismissRequest = { renameCompanionTarget = null },
            title = { Text("Manage Friend") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Friend Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            onRenameCompanion(id, renameInput)
                        }
                        renameCompanionTarget = null
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                Row {
                    OutlinedButton(
                        onClick = {
                            onRemoveCompanion(id)
                            renameCompanionTarget = null
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Remove")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { renameCompanionTarget = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

/**
 * Standard reusable Settings card wrapper providing consistent padding, subtle elevation,
 * 1dp outline stroke, and section iconography.
 */
@Composable
fun SettingsCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            content()
        }
    }
}
