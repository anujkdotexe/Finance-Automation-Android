package com.financeautomation.presentation.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.financeautomation.R
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Companion
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Open debit row representation for the Friend Settlement bill picker.
 */
data class OpenDebtItem(
    val uuid: String,
    val description: String,
    val amountOwed: Double,
    val tabName: String,
    val person: String = ""
) {
    val displayLabel: String
        get() = if (person.isNotBlank()) {
            "$description (₹${amountOwed.toInt()} - $person) [$tabName]"
        } else {
            "$description (₹${amountOwed.toInt()}) [$tabName]"
        }
}

/**
 * Flow sheet for logging Income / Return / Friend Settlement (Phase 5.6 point 2).
 */
@Composable
fun IncomeReturnFlowSheet(
    accounts: List<Account>,
    companions: List<Companion>,
    openDebts: List<OpenDebtItem>,
    onPersonChanged: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, account: String, inflowType: String, settler: String, reason: String, explicitRefs: List<String>) -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var amountText by remember { mutableStateOf("") }
    val defaultAccount = accounts.firstOrNull { it.isDefault }?.name ?: accounts.firstOrNull()?.name ?: "Cash"
    var selectedAccount by remember { mutableStateOf(defaultAccount) }

    val friendSettlement = stringResource(R.string.inflow_type_friend)
    val salaryStipend = stringResource(R.string.inflow_type_income)
    val refundReturn = stringResource(R.string.inflow_type_return)
    val transfer = stringResource(R.string.inflow_type_transfer)
    val inflowTypes = remember(friendSettlement, salaryStipend, refundReturn, transfer) {
        listOf(friendSettlement, salaryStipend, refundReturn, transfer)
    }
    var selectedInflowType by remember { mutableStateOf(friendSettlement) }

    var settlerName by remember { mutableStateOf("") }
    var reasonText by remember { mutableStateOf("") }

    // Bill picker state for Friend Settlement
    var autoSettleAll by remember { mutableStateOf(true) }
    val selectedDebtIds = remember { mutableStateListOf<String>() }

    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() }
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Grabber Pill
        Box(
            modifier = Modifier
                .width(FinanceTheme.sizes.grabberWidth)
                .height(FinanceTheme.sizes.grabberHeight)
                .clip(CircleShape)
                .background(FinanceTheme.colors.outline.copy(alpha = 0.4f))
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(FinanceTheme.sizes.icon28)
                    .clickable { onBack() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = FinanceTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                }
            }

            Text(
                text = stringResource(R.string.action_menu_income_title),
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FinanceTheme.colors.onSurface
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(FinanceTheme.sizes.icon28)
                    .clickable { onDismiss() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = FinanceTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Amount Input Field
        OutlinedTextField(
            value = amountText,
            onValueChange = { input ->
                if (input.isEmpty() || input.matches(Regex("""^\d*(\.\d{0,2})?$"""))) {
                    amountText = input
                }
            },
            label = { Text(stringResource(R.string.prompt_amount_received)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Inflow Type Filter Chips
        Text(
            text = stringResource(R.string.prompt_inflow_type),
            style = FinanceTheme.typography.labelMedium,
            color = FinanceTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            inflowTypes.forEach { type ->
                FilterChip(
                    selected = (selectedInflowType == type),
                    onClick = {
                        focusManager.clearFocus()
                        selectedInflowType = type
                    },
                    label = { Text(type) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FinanceTheme.colors.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Account Chips
        Text(
            text = stringResource(R.string.prompt_receiving_account),
            style = FinanceTheme.typography.labelMedium,
            color = FinanceTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            accounts.forEach { account ->
                FilterChip(
                    selected = (selectedAccount == account.name),
                    onClick = {
                        focusManager.clearFocus()
                        selectedAccount = account.name
                    },
                    label = { Text(account.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FinanceTheme.colors.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Who Sent It (Companion chips + text input)
        Text(
            text = if (selectedInflowType == friendSettlement) {
                stringResource(R.string.prompt_who_settled)
            } else {
                stringResource(R.string.prompt_sender_source)
            },
            style = FinanceTheme.typography.labelMedium,
            color = FinanceTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
        if (companions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                companions.take(6).forEach { companion ->
                    FilterChip(
                        selected = (settlerName.equals(companion.name, ignoreCase = true)),
                        onClick = {
                            focusManager.clearFocus()
                            settlerName = companion.name
                            onPersonChanged(companion.name)
                        },
                        label = { Text(companion.name) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
        }

        OutlinedTextField(
            value = settlerName,
            onValueChange = {
                settlerName = it
                onPersonChanged(it)
            },
            label = { Text(stringResource(R.string.prompt_who_sent_this)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Bill Picker for Friend Settlement (if open debts exist for this person)
        if (selectedInflowType == friendSettlement && openDebts.isNotEmpty()) {
            OpenDebtPickerSection(
                openDebts = openDebts,
                autoSettleAll = autoSettleAll,
                selectedDebtIds = selectedDebtIds,
                onToggleAutoSettle = { checked ->
                    autoSettleAll = checked
                    if (checked) selectedDebtIds.clear()
                }
            )
        }

        // Reason Field
        OutlinedTextField(
            value = reasonText,
            onValueChange = { reasonText = it },
            label = { Text(stringResource(R.string.prompt_reason_note)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

        // Confirm & Save Button
        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
        val isValid = parsedAmount > 0.0 && (selectedInflowType != friendSettlement || settlerName.isNotBlank())

        Button(
            onClick = {
                val refs = if (autoSettleAll) emptyList() else selectedDebtIds.toList()
                val effectiveReason = reasonText.ifBlank {
                    if (selectedInflowType == friendSettlement) "Settlement from $settlerName" else selectedInflowType
                }
                onConfirm(
                    parsedAmount,
                    selectedAccount,
                    selectedInflowType,
                    settlerName.trim(),
                    effectiveReason,
                    refs
                )
            },
            enabled = isValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(FinanceTheme.sizes.buttonHeightLarge),
            shape = FinanceTheme.shapes.large
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(FinanceTheme.sizes.icon18)
            )
            Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
            Text(
                text = stringResource(R.string.btn_record_inflow),
                style = FinanceTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
    }
}
