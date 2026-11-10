package com.financeautomation.presentation.screens.settings

import com.financeautomation.presentation.screens.SettingsCard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState

/**
 * Account settings section managing Google Identity, funding accounts, and split companions (Design Guide 5.2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAccountSection(
    state: SettingsUiState,
    onReRunSetup: () -> Unit,
    onSignOut: () -> Unit,
    onDefaultAccountChanged: (String) -> Unit,
    onAddAccount: (String, String?) -> Unit,
    onAddCompanion: (String) -> Unit,
    onRenameCompanion: (String, String) -> Unit,
    onRemoveCompanion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var newAccountInput by remember { mutableStateOf("") }
    var newCompanionInput by remember { mutableStateOf("") }
    var renameCompanionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renameInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)) {
        // 1. Google Identity & Drive Provisioning
        SettingsCard(
            title = "Google account & ledger",
            subtitle = "OAuth authentication and automatic Google Drive spreadsheet provisioning",
            icon = Icons.Rounded.AccountCircle
        ) {
            if (state.googleAccountEmail != null) {
                Surface(
                    color = FinanceTheme.colors.brand.copy(alpha = 0.12f),
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(FinanceTheme.spacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = FinanceTheme.colors.brand,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Connected",
                                style = FinanceTheme.typography.labelMedium,
                                color = FinanceTheme.colors.onSurfaceVariant
                            )
                            Text(
                                text = state.googleAccountEmail.orEmpty(),
                                style = FinanceTheme.typography.bodyMedium,
                                color = FinanceTheme.colors.onSurface
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    OutlinedButton(
                        onClick = onReRunSetup,
                        shape = FinanceTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text("Re-run setup")
                    }

                    OutlinedButton(
                        onClick = onSignOut,
                        shape = FinanceTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sign out")
                    }
                }
            } else {
                Text(
                    text = "Google Drive sync is not configured. Run the setup checklist to connect your account and workbook.",
                    style = FinanceTheme.typography.bodyMedium,
                    color = FinanceTheme.colors.onSurfaceVariant
                )

                Button(
                    onClick = onReRunSetup,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                    Text("Start setup")
                }
            }
        }

        // 2. Bank & Funding Accounts
        SettingsCard(
            title = "Bank and payment accounts",
            subtitle = "Select an account to set default. Add custom accounts anytime.",
            icon = Icons.Rounded.AccountBalance
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                state.accounts.forEach { account ->
                    val isDefault = account.name.equals(state.defaultAccount, ignoreCase = true)
                    FilterChip(
                        selected = isDefault,
                        onClick = { onDefaultAccountChanged(account.name) },
                        label = {
                            Text(text = if (isDefault) "${account.name} (Default)" else account.name)
                        },
                        leadingIcon = if (isDefault) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Default",
                                    tint = FinanceTheme.colors.brand,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        shape = FinanceTheme.shapes.chip
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                OutlinedTextField(
                    value = newAccountInput,
                    onValueChange = { newAccountInput = it },
                    label = { Text("New account name") },
                    singleLine = true,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newAccountInput.isNotBlank()) {
                            onAddAccount(newAccountInput, null)
                            newAccountInput = ""
                        }
                    },
                    shape = FinanceTheme.shapes.medium
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text("Add")
                }
            }
        }

        // 3. Friends & Companions
        SettingsCard(
            title = "Friends and companions",
            subtitle = "Tap a friend chip to rename or delete. Used for 1-tap bill splitting.",
            icon = Icons.Rounded.People
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                state.companions.forEach { companion ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            renameCompanionTarget = companion.id to companion.name
                            renameInput = companion.name
                        },
                        label = { Text(companion.name) },
                        shape = FinanceTheme.shapes.chip
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                OutlinedTextField(
                    value = newCompanionInput,
                    onValueChange = { newCompanionInput = it },
                    label = { Text("New friend name") },
                    singleLine = true,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newCompanionInput.isNotBlank()) {
                            onAddCompanion(newCompanionInput)
                            newCompanionInput = ""
                        }
                    },
                    shape = FinanceTheme.shapes.medium
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text("Add")
                }
            }
        }
    }

    // Rename Companion Dialog
    renameCompanionTarget?.let { (id, _) ->
        AlertDialog(
            onDismissRequest = { renameCompanionTarget = null },
            title = { Text("Manage friend") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Friend name") },
                    singleLine = true,
                    shape = FinanceTheme.shapes.medium,
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
                    shape = FinanceTheme.shapes.medium
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
                        shape = FinanceTheme.shapes.medium
                    ) {
                        Text("Remove")
                    }
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                    TextButton(onClick = { renameCompanionTarget = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}
