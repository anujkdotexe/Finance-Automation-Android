package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
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
    var showAddAccountDialog by rememberSaveable { mutableStateOf(false) }
    var selectedAccountForEdit by remember { mutableStateOf<Account?>(null) }
    var newAccountName by rememberSaveable { mutableStateOf("") }
    var newAccountDigits by rememberSaveable { mutableStateOf("") }

    var localAccounts by remember(state.accounts) { mutableStateOf(state.accounts) }
    var draggingAccountId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density

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

        if (localAccounts.isEmpty()) {
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
                    localAccounts.forEachIndexed { index, account ->
                        val isDefault = account.isDefault || account.name == state.defaultAccount
                        val txCount = state.accountTransactionCounts[account.name] ?: 0
                        val isLocked = CategoryConstants.isProtectedAccount(account.name)
                        val isDragging = account.id == draggingAccountId

                        AccountListItem(
                            account = account,
                            isDefault = isDefault,
                            isLocked = isLocked,
                            isDragging = isDragging,
                            transactionCount = txCount,
                            onEdit = { selectedAccountForEdit = account },
                            onSetDefault = { viewModel.onDefaultAccountChanged(account.name) },
                            onArchive = { viewModel.onArchiveAccount(account.id) },
                            onDelete = { viewModel.onDeleteAccount(account.id) },
                            modifier = Modifier
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0) {
                                            add(CustomAccessibilityAction(label = "Move up") {
                                                val mutable = localAccounts.toMutableList()
                                                val item = mutable.removeAt(index)
                                                mutable.add(index - 1, item)
                                                localAccounts = mutable
                                                viewModel.onReorderAccounts(mutable.map { it.id })
                                                true
                                            })
                                        }
                                        if (index < localAccounts.size - 1) {
                                            add(CustomAccessibilityAction(label = "Move down") {
                                                val mutable = localAccounts.toMutableList()
                                                val item = mutable.removeAt(index)
                                                mutable.add(index + 1, item)
                                                localAccounts = mutable
                                                viewModel.onReorderAccounts(mutable.map { it.id })
                                                true
                                            })
                                        }
                                    }
                                },
                            dragHandleModifier = Modifier.pointerInput(account.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingAccountId = account.id
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        val finalIds = localAccounts.map { it.id }
                                        if (finalIds != state.accounts.map { it.id }) {
                                            viewModel.onReorderAccounts(finalIds)
                                        }
                                        draggingAccountId = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggingAccountId = null
                                        dragOffsetY = 0f
                                        localAccounts = state.accounts
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        val itemHeightPx = 68f * density
                                        val currentIndex = localAccounts.indexOfFirst { it.id == draggingAccountId }
                                        if (currentIndex != -1) {
                                            if (dragOffsetY > itemHeightPx && currentIndex < localAccounts.size - 1) {
                                                val mutable = localAccounts.toMutableList()
                                                val item = mutable.removeAt(currentIndex)
                                                mutable.add(currentIndex + 1, item)
                                                localAccounts = mutable
                                                dragOffsetY -= itemHeightPx
                                            } else if (dragOffsetY < -itemHeightPx && currentIndex > 0) {
                                                val mutable = localAccounts.toMutableList()
                                                val item = mutable.removeAt(currentIndex)
                                                mutable.add(currentIndex - 1, item)
                                                localAccounts = mutable
                                                dragOffsetY += itemHeightPx
                                            }
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    selectedAccountForEdit?.let { account ->
        val isDefault = account.isDefault || account.name == state.defaultAccount
        var editName by rememberSaveable { mutableStateOf(account.name) }
        var editDigits by rememberSaveable { mutableStateOf(account.lastFourDigits.orEmpty()) }

        AlertDialog(
            onDismissRequest = { selectedAccountForEdit = null },
            title = {
                Text(
                    text = stringResource(R.string.accounts_edit_title),
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
                            text = stringResource(R.string.accounts_field_name),
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = editName,
                            onValueChange = {
                                editName = com.financeautomation.core.utils.InputPolicy.cleanName(it)
                                    .take(com.financeautomation.core.utils.InputPolicy.MAX_NAME_LENGTH)
                            },
                            singleLine = true,
                            shape = FinanceShapes.card,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.accounts_field_last4),
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = editDigits,
                            onValueChange = {
                                val clean = com.financeautomation.core.utils.InputPolicy.cleanDigits4(it)
                                if (clean.length <= 4) editDigits = clean
                            },
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
                            Text(stringResource(R.string.accounts_set_default))
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                viewModel.onArchiveAccount(account.id)
                                selectedAccountForEdit = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.accounts_archive), color = MaterialTheme.colorScheme.error)
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
                    var selectedType by rememberSaveable { mutableStateOf("Bank") }

                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = "Account type",
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("Bank", "Card", "Wallet", "Cash").forEach { type ->
                                val selected = selectedType == type
                                Surface(
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else FinanceTheme.colors.surfaceContainerHigh,
                                    shape = FinanceShapes.pill,
                                    modifier = Modifier.clickable { selectedType = type }
                                ) {
                                    Text(
                                        text = type,
                                        style = FinanceTheme.typography.labelMedium,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.xs)
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.dialog_add_account_name),
                            style = FinanceTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = newAccountName,
                            onValueChange = {
                                newAccountName = com.financeautomation.core.utils.InputPolicy.cleanName(it)
                                    .take(com.financeautomation.core.utils.InputPolicy.MAX_NAME_LENGTH)
                            },
                            placeholder = { Text(stringResource(R.string.accounts_name_placeholder), style = FinanceTheme.typography.bodyMedium) },
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
                            onValueChange = {
                                val clean = com.financeautomation.core.utils.InputPolicy.cleanDigits4(it)
                                if (clean.length <= 4) newAccountDigits = clean
                            },
                            placeholder = { Text(stringResource(R.string.accounts_last4_placeholder), style = FinanceTheme.typography.bodyMedium) },
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
    isLocked: Boolean,
    isDragging: Boolean = false,
    transactionCount: Int,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

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
            .background(if (isDragging) FinanceTheme.colors.surfaceContainerHigh else FinanceTheme.colors.surfaceContainerLowest)
            .padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Drag Handle
        Box(
            modifier = dragHandleModifier
                .size(FinanceTheme.sizes.minTouchTarget)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = stringResource(R.string.cd_reorder_account, account.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 2. Account type Icon
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

        Spacer(modifier = Modifier.width(FinanceTheme.spacing.m))

        // 3. Account details
        Column(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                .clickable(enabled = !isLocked, onClick = onEdit),
            verticalArrangement = Arrangement.Center
        ) {
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
            if (isLocked) {
                Text(
                    text = stringResource(R.string.categories_locked_subtitle),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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

        // 4. Trailing overflow menu
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(FinanceTheme.sizes.minTouchTarget)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.cd_account_options, account.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (isLocked) {
                    DropdownMenuItem(
                        text = { Text("System account (locked)") },
                        onClick = { menuExpanded = false },
                        enabled = false
                    )
                } else {
                    if (!isDefault) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.accounts_set_default)) },
                            onClick = {
                                menuExpanded = false
                                onSetDefault()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    if (!isDefault) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.accounts_archive),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onArchive()
                            }
                        )
                    }
                    if (!isDefault && transactionCount == 0) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Delete",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}
