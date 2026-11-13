package com.financeautomation.presentation.flows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.financeautomation.R
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Companion
import com.financeautomation.presentation.components.AppChip
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.theme.FinanceShapes
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
 * Rebuilt Add Income flow sheet following the frozen design specification:
 * - Amount hero in mono.
 * - Inflow type chips: Income, Refund, Friend settlement, Self transfer.
 * - Account chips (user-defined accounts, no balances).
 * - Optional Sender / source text field.
 * - Friend settlement companion chips and open debt picker.
 * - ButtonPair: OutlinedButton "Cancel", FilledButton "Save income".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncomeReturnFlowSheet(
    accounts: List<Account>,
    companions: List<Companion>,
    openDebts: List<OpenDebtItem>,
    preselectedFriend: String? = null,
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

    val incomeType = stringResource(R.string.inflow_type_income)
    val refundType = stringResource(R.string.inflow_type_return)
    val friendSettlementType = stringResource(R.string.inflow_type_friend)
    val transferType = stringResource(R.string.inflow_type_transfer)

    val inflowTypes = remember(incomeType, refundType, friendSettlementType, transferType) {
        listOf(incomeType, refundType, friendSettlementType, transferType)
    }
    var selectedInflowType by remember { mutableStateOf(incomeType) }

    var settlerName by remember(preselectedFriend) { mutableStateOf(preselectedFriend ?: "") }
    var reasonText by remember { mutableStateOf("") }

    LaunchedEffect(preselectedFriend) {
        if (!preselectedFriend.isNullOrBlank()) {
            selectedInflowType = friendSettlementType
            onPersonChanged(preselectedFriend)
        }
    }

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
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            // 1. Amount Field
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.label_amount),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "₹",
                    style = FinanceTheme.typography.amountHero,
                    color = FinanceTheme.colors.primary,
                    modifier = Modifier.padding(end = FinanceTheme.spacing.xs)
                )

                BasicTextField(
                    value = amountText,
                    onValueChange = { input ->
                        if (input.isEmpty() || input.matches(Regex("""^\d*(\.\d{0,2})?$"""))) {
                            amountText = input
                        }
                    },
                    singleLine = true,
                    textStyle = FinanceTheme.typography.amountHero.copy(
                        color = FinanceTheme.colors.onSurface,
                        textAlign = TextAlign.Center
                    ),
                    cursorBrush = SolidColor(FinanceTheme.colors.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        .defaultMinSize(minWidth = FinanceTheme.sizes.emptyStateIllustration)
                        .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.Center) {
                            if (amountText.isEmpty()) {
                                Text(
                                    text = "0.00",
                                    style = FinanceTheme.typography.amountHero,
                                    color = FinanceTheme.colors.outlineVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        // 2. Inflow Type Chips in wrapping FlowRow
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_inflow_type),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                inflowTypes.forEach { type ->
                    val isSelected = selectedInflowType == type
                    AppChip(
                        text = type,
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus()
                            selectedInflowType = type
                        }
                    )
                }
            }
        }

        // 3. Account Credited Chips (user-defined accounts, no balances) in wrapping FlowRow
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_receiving_account),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                accounts.forEach { account ->
                    val isSelected = selectedAccount == account.name
                    AppChip(
                        text = account.name,
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus()
                            selectedAccount = account.name
                        }
                    )
                }
            }
        }

        // 4. Sender / Source Field
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (selectedInflowType == friendSettlementType) {
                    stringResource(R.string.prompt_who_settled)
                } else {
                    stringResource(R.string.prompt_sender_source)
                },
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            // Friend Chips if Friend settlement is active in wrapping FlowRow
            if (selectedInflowType == friendSettlementType && companions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    companions.forEach { companion ->
                        val isSelected = settlerName.equals(companion.name, ignoreCase = true)
                        AppChip(
                            text = companion.name,
                            selected = isSelected,
                            onClick = {
                                focusManager.clearFocus()
                                settlerName = companion.name
                                onPersonChanged(companion.name)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            }

            OutlinedTextField(
                value = settlerName,
                onValueChange = {
                    settlerName = it
                    if (selectedInflowType == friendSettlementType) {
                        onPersonChanged(it)
                    }
                },
                placeholder = {
                    Text(
                        text = if (selectedInflowType == friendSettlementType) {
                            stringResource(R.string.hint_friends_name)
                        } else {
                            stringResource(R.string.prompt_who_sent_this)
                        },
                        style = FinanceTheme.typography.bodyMedium
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                singleLine = true,
                shape = FinanceShapes.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
            )
        }

        // 5. Bill Picker for Friend Settlement (if open debts exist for this person)
        if (selectedInflowType == friendSettlementType && openDebts.isNotEmpty()) {
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

        // 6. Notes / Reason Field (optional)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_reason_note),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            OutlinedTextField(
                value = reasonText,
                onValueChange = { reasonText = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.prompt_reason_note),
                        style = FinanceTheme.typography.bodyMedium
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                shape = FinanceShapes.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
            )
        }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // 7. Action Buttons: Pinned ButtonPair with OutlinedButton "Cancel" and FilledButton "Save income" outside scrollable area
        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
        val isValid = parsedAmount > 0.0 && (selectedInflowType != friendSettlementType || settlerName.isNotBlank())

        ButtonPair(
            outlinedText = stringResource(R.string.action_cancel),
            onOutlinedClick = onDismiss,
            filledText = stringResource(R.string.btn_save_income),
            onFilledClick = {
                val refs = if (autoSettleAll) emptyList() else selectedDebtIds.toList()
                val effectiveReason = reasonText.ifBlank {
                    if (selectedInflowType == friendSettlementType) "Settlement from $settlerName" else selectedInflowType
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
            filledEnabled = isValid
        )
    }
}
