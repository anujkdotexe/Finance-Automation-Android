package com.financeautomation.presentation.flows

import androidx.annotation.StringRes
import com.financeautomation.presentation.components.ClearFocusOnUserDrag
import com.financeautomation.core.constants.CategoryConstants

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Companion
import com.financeautomation.presentation.components.AmountEntry
import com.financeautomation.presentation.components.AmountField
import com.financeautomation.presentation.components.AppChip
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.NotesField
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

typealias OpenDebtItem = com.financeautomation.domain.model.OpenDebtItem



enum class InflowOption(val key: String, @StringRes val labelRes: Int) {
    INCOME("Income", R.string.inflow_type_income),
    REFUND("Refund", R.string.inflow_type_return),
    FRIEND_SETTLEMENT("Friend Settlement", R.string.inflow_type_friend),
    TRANSFER("Transfer", R.string.inflow_type_transfer)
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
    var amountText by rememberSaveable { mutableStateOf("") }
    val defaultAccount = accounts.firstOrNull { it.isDefault }?.name ?: accounts.firstOrNull()?.name ?: CategoryConstants.ACCOUNT_OTHER
    var selectedAccount by rememberSaveable { mutableStateOf(defaultAccount) }

    LaunchedEffect(accounts) {
        if (accounts.isNotEmpty()) {
            val hasValidSelection = accounts.any { it.name.equals(selectedAccount, ignoreCase = true) }
            if (!hasValidSelection) {
                val preferred = accounts.firstOrNull { it.isDefault }?.name ?: accounts.firstOrNull()?.name
                if (preferred != null) {
                    selectedAccount = preferred
                }
            }
        }
    }

    val inflowOptions = remember { InflowOption.values().toList() }
    var selectedOption by rememberSaveable { mutableStateOf(InflowOption.INCOME) }

    var settlerName by remember(preselectedFriend) { mutableStateOf(preselectedFriend ?: "") }
    var reasonText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(preselectedFriend) {
        if (!preselectedFriend.isNullOrBlank()) {
            selectedOption = InflowOption.FRIEND_SETTLEMENT
            onPersonChanged(preselectedFriend)
        }
    }

    // Bill picker state for Friend Settlement
    var autoSettleAll by rememberSaveable { mutableStateOf(true) }
    val selectedDebtIds = remember { mutableStateListOf<String>() }

    // Ticked bills belong to one person and one income type: a change of either one drops them
    LaunchedEffect(selectedOption, settlerName.trim().lowercase()) {
        selectedDebtIds.clear()
    }

    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    ClearFocusOnUserDrag(scrollState, focusManager)

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

            AmountField(
                value = amountText,
                onValueChange = { amountText = it },
                hero = true,
                onFocusChanged = onKeyboardFocusChanged
            )
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
                inflowOptions.forEach { option ->
                    val isSelected = selectedOption == option
                    AppChip(
                        text = stringResource(option.labelRes),
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus()
                            selectedOption = option
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
                text = if (selectedOption == InflowOption.FRIEND_SETTLEMENT) {
                    stringResource(R.string.prompt_who_settled)
                } else {
                    stringResource(R.string.prompt_sender_source)
                },
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            // Friend Chips if Friend settlement is active in wrapping FlowRow
            if (selectedOption == InflowOption.FRIEND_SETTLEMENT && companions.isNotEmpty()) {
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
                    val clean = com.financeautomation.core.utils.InputPolicy.cleanNameWhileTyping(it)
                        .take(com.financeautomation.core.utils.InputPolicy.MAX_NAME_LENGTH)
                    settlerName = clean
                    if (selectedOption == InflowOption.FRIEND_SETTLEMENT) {
                        onPersonChanged(com.financeautomation.core.utils.InputPolicy.normalizedPersonForLookup(clean))
                    }
                },
                placeholder = {
                    Text(
                        text = if (selectedOption == InflowOption.FRIEND_SETTLEMENT) {
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
        if (selectedOption == InflowOption.FRIEND_SETTLEMENT && openDebts.isNotEmpty()) {
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
        NotesField(
            value = reasonText,
            onValueChange = { reasonText = it },
            label = stringResource(R.string.prompt_reason_note),
            placeholder = stringResource(R.string.hint_optional),
            onFocusChanged = onKeyboardFocusChanged,
            modifier = Modifier.fillMaxWidth()
        )
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // 7. Action Buttons: Pinned ButtonPair with OutlinedButton "Cancel" and FilledButton "Save income" outside scrollable area
        val amountEntry = AmountEntry(amountText)
        val parsedAmount = amountEntry.amountOrZero
        val isValid = amountEntry.canSave && (selectedOption != InflowOption.FRIEND_SETTLEMENT || settlerName.isNotBlank())

        ButtonPair(
            outlinedText = stringResource(R.string.action_cancel),
            onOutlinedClick = onDismiss,
            filledText = stringResource(R.string.btn_save_income),
            onFilledClick = {
                // Explicit bills are sent only for a friend settlement with at least one ticked bill
                val refs = if (selectedOption == InflowOption.FRIEND_SETTLEMENT && !autoSettleAll && selectedDebtIds.isNotEmpty()) {
                    selectedDebtIds.toList()
                } else {
                    emptyList()
                }
                val cleanSettler = com.financeautomation.core.utils.InputPolicy.cleanName(settlerName)
                val effectiveReason = com.financeautomation.core.utils.InputPolicy.buildSettlementReason(
                    name = settlerName,
                    note = reasonText,
                    optionKey = selectedOption.key
                )
                onConfirm(
                    parsedAmount,
                    selectedAccount,
                    selectedOption.key,
                    cleanSettler,
                    effectiveReason,
                    refs
                )
            },
            filledEnabled = isValid
        )
    }
}
