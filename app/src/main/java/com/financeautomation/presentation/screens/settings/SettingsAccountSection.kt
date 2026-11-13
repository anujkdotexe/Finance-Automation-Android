package com.financeautomation.presentation.screens.settings

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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import com.financeautomation.R
import com.financeautomation.presentation.screens.SettingsCard
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState

/**
 * Settings section: Google Account & Ledger, Bank/Funding Accounts, Friends & Companions.
 * Split from SettingsScreen to preserve the 400-line ceiling (Design Guide 4).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAccountSection(
    state: SettingsUiState,
    onDefaultAccountChanged: (String) -> Unit,
    onAddAccount: (String, String?) -> Unit,
    onAddCompanion: (String) -> Unit,
    onRenameCompanion: (String, String) -> Unit,
    onRemoveCompanion: (String) -> Unit,
    onReRunSetup: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var newAccountInput by remember { mutableStateOf("") }
    var newCompanionInput by remember { mutableStateOf("") }
    var renameCompanionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renameInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)) {
        // 1. Google Identity & Drive Provisioning
        SettingsCard(
            title = stringResource(R.string.settings_account_title),
            subtitle = stringResource(R.string.settings_account_subtitle),
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
                            modifier = Modifier.size(FinanceTheme.sizes.iconLarge)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_account_connected),
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
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text(stringResource(R.string.action_re_run_setup))
                    }

                    OutlinedButton(
                        onClick = onSignOut,
                        shape = FinanceTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_sign_out))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.settings_account_disconnected_desc),
                    style = FinanceTheme.typography.bodyMedium,
                    color = FinanceTheme.colors.onSurfaceVariant
                )

                Button(
                    onClick = onReRunSetup,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.icon18))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                    Text(stringResource(R.string.action_start_setup))
                }
            }
        }

        // 2. Bank & Funding Accounts
        SettingsCard(
            title = stringResource(R.string.settings_funding_accounts_title),
            subtitle = stringResource(R.string.settings_funding_accounts_desc),
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
                            Text(text = if (isDefault) stringResource(R.string.account_default_format, account.name) else account.name)
                        },
                        leadingIcon = if (isDefault) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.account_default_label),
                                    tint = FinanceTheme.colors.brand,
                                    modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                                )
                            }
                        } else null,
                        shape = FinanceTheme.shapes.chip
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                Text(
                    text = stringResource(R.string.settings_new_account_label),
                    style = FinanceTheme.typography.labelMedium,
                    color = FinanceTheme.colors.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    OutlinedTextField(
                        value = newAccountInput,
                        onValueChange = { newAccountInput = it },
                        placeholder = { Text("e.g. HDFC", style = FinanceTheme.typography.bodyMedium) },
                        singleLine = true,
                        shape = FinanceTheme.shapes.card,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            if (newAccountInput.isNotBlank()) {
                                onAddAccount(newAccountInput, null)
                                newAccountInput = ""
                            }
                        },
                        shape = FinanceTheme.shapes.pill
                    ) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text(stringResource(R.string.action_add))
                    }
                }
            }
        }

        // 3. Friends & Companions
        SettingsCard(
            title = stringResource(R.string.settings_friends_title),
            subtitle = stringResource(R.string.settings_friends_desc),
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

            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                Text(
                    text = stringResource(R.string.settings_new_friend_label),
                    style = FinanceTheme.typography.labelMedium,
                    color = FinanceTheme.colors.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    OutlinedTextField(
                        value = newCompanionInput,
                        onValueChange = { newCompanionInput = it },
                        placeholder = { Text("e.g. Karan", style = FinanceTheme.typography.bodyMedium) },
                        singleLine = true,
                        shape = FinanceTheme.shapes.card,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            if (newCompanionInput.isNotBlank()) {
                                onAddCompanion(newCompanionInput)
                                newCompanionInput = ""
                            }
                        },
                        shape = FinanceTheme.shapes.pill
                    ) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text(stringResource(R.string.action_add))
                    }
                }
            }
        }
    }

    // Rename Companion Dialog
    renameCompanionTarget?.let { (id, _) ->
        AlertDialog(
            onDismissRequest = { renameCompanionTarget = null },
            title = { Text(stringResource(R.string.settings_manage_friend_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.settings_friend_name_label),
                        style = FinanceTheme.typography.labelMedium,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        placeholder = { Text("Name", style = FinanceTheme.typography.bodyMedium) },
                        singleLine = true,
                        shape = FinanceTheme.shapes.card,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                    Text(stringResource(R.string.action_rename))
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
                        Text(stringResource(R.string.action_remove))
                    }
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                    TextButton(onClick = { renameCompanionTarget = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        )
    }
}
