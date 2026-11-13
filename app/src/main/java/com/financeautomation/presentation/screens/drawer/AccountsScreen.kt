package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.domain.model.Account
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.HeaderActionButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun AccountsScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onReRunSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var selectedAccountForEdit by remember { mutableStateOf<Account?>(null) }
    var newAccountName by remember { mutableStateOf("") }
    var newAccountDigits by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        // Section Header with Add button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.accounts_section_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.accounts_section_subtitle),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HeaderActionButton(
                text = stringResource(R.string.accounts_action_add),
                onClick = { showAddAccountDialog = true }
            )
        }

        if (state.accounts.isEmpty()) {
            EmptyState(
                message = stringResource(R.string.accounts_empty_title)
            )
        } else {
            Surface(
                color = FinanceTheme.colors.surfaceContainerLowest,
                shape = FinanceShapes.large,
                shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FinanceTheme.spacing.xs)
                ) {
                    state.accounts.forEach { account ->
                        val isDefault = account.isDefault || account.name == state.defaultAccount
                        val txCount = state.accountTransactionCounts[account.name] ?: 0

                        AccountListItem(
                            account = account,
                            isDefault = isDefault,
                            transactionCount = txCount,
                            onClick = { selectedAccountForEdit = account }
                        )
                    }
                }
            }
        }
    }

    selectedAccountForEdit?.let { account ->
        val isDefault = account.isDefault || account.name == state.defaultAccount
        var editName by remember { mutableStateOf(account.name) }
        var editDigits by remember { mutableStateOf(account.lastFourDigits.orEmpty()) }

        AlertDialog(
            onDismissRequest = { selectedAccountForEdit = null },
            title = {
                Text(
                    text = "Edit Account",
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = "Account Name",
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            singleLine = true,
                            shape = FinanceShapes.card,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = "Last 4 Digits",
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = editDigits,
                            onValueChange = { if (it.length <= 4) editDigits = it },
                            singleLine = true,
                            shape = FinanceShapes.card,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (!isDefault) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                viewModel.onDefaultAccountChanged(account.name)
                                selectedAccountForEdit = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Set as default account")
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                viewModel.onArchiveAccount(account.id)
                                selectedAccountForEdit = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Archive account", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                ButtonPair(
                    outlinedText = stringResource(R.string.action_cancel),
                    onOutlinedClick = { selectedAccountForEdit = null },
                    filledText = stringResource(R.string.action_save),
                    onFilledClick = {
                        if (editName.isNotBlank()) {
                            viewModel.onRenameAccount(account.id, editName, editDigits)
                            selectedAccountForEdit = null
                        }
                    },
                    filledEnabled = editName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    if (showAddAccountDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddAccountDialog = false
                newAccountName = ""
                newAccountDigits = ""
            },
            title = {
                Text(
                    text = stringResource(R.string.dialog_add_account_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.dialog_add_account_name),
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = newAccountName,
                            onValueChange = { newAccountName = it },
                            placeholder = { Text("e.g. HDFC Salary", style = FinanceTheme.typography.bodyMedium) },
                            singleLine = true,
                            shape = FinanceShapes.card,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.dialog_add_account_last4),
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = newAccountDigits,
                            onValueChange = { if (it.length <= 4) newAccountDigits = it },
                            placeholder = { Text("e.g. 1234", style = FinanceTheme.typography.bodyMedium) },
                            singleLine = true,
                            shape = FinanceShapes.card,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                ButtonPair(
                    outlinedText = stringResource(R.string.action_cancel),
                    onOutlinedClick = {
                        showAddAccountDialog = false
                        newAccountName = ""
                        newAccountDigits = ""
                    },
                    filledText = stringResource(R.string.action_save),
                    onFilledClick = {
                        if (newAccountName.isNotBlank()) {
                            val digits = newAccountDigits.trim().ifEmpty { null }
                            viewModel.onAddAccount(newAccountName.trim(), digits)
                            newAccountName = ""
                            newAccountDigits = ""
                            showAddAccountDialog = false
                        }
                    },
                    filledEnabled = newAccountName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

@Composable
private fun AccountListItem(
    account: Account,
    isDefault: Boolean,
    transactionCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayName = remember(account.name, account.lastFourDigits) {
        if (!account.lastFourDigits.isNullOrBlank()) {
            "${account.name} ••${account.lastFourDigits}"
        } else {
            account.name
        }
    }

    val icon = remember(account.name) {
        val lower = account.name.lowercase()
        when {
            lower.contains("cash") -> Icons.Rounded.Payments
            lower.contains("card") || lower.contains("credit") || lower.contains("magnus") -> Icons.Rounded.CreditCard
            else -> Icons.Rounded.AccountBalance
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
            .clip(FinanceShapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
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
                    .background(FinanceTheme.colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Text(
                        text = displayName,
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isDefault) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs),
                            modifier = Modifier.padding(start = FinanceTheme.spacing.xs)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(FinanceTheme.spacing.compact)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = stringResource(R.string.accounts_badge_default),
                                style = FinanceTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Text(
                    text = if (transactionCount > 0) {
                        stringResource(R.string.accounts_tx_count, transactionCount)
                    } else {
                        "Active account"
                    },
                    style = FinanceTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
            )
        }
    }
}
