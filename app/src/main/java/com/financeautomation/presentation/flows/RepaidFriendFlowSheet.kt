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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Companion
import com.financeautomation.presentation.components.AppChip
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SHEET_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
private val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.US)

/** Date picker rule: nothing after today (Asia/Kolkata). Picker dates are UTC midnights. */
@OptIn(ExperimentalMaterial3Api::class)
internal object NoFutureSelectableDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val today = LocalDate.now(SHEET_ZONE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return utcTimeMillis <= today
    }
    override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now(SHEET_ZONE).year
}

/**
 * Rebuilt Repaid a Friend flow sheet following the frozen design specification:
 * - Who you repaid with open balance shown on chip row.
 * - Amount with "Pay full" and "Custom amount" quick options.
 * - Paid-from account chips (user-defined accounts, no balances).
 * - Date picker (no future dates).
 * - ButtonPair: OutlinedButton "Cancel", FilledButton "Record repayment".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepaidFriendFlowSheet(
    companions: List<Companion>,
    accounts: List<Account> = emptyList(),
    preselectedFriend: String? = null,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, person: String, timestamp: Long) -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var personName by remember(preselectedFriend) { mutableStateOf(preselectedFriend ?: "") }
    val selectedCompanion = companions.firstOrNull { it.name.equals(personName, ignoreCase = true) }

    val defaultAccount = accounts.firstOrNull { it.isDefault }?.name ?: accounts.firstOrNull()?.name ?: "Cash"
    var selectedAccount by remember { mutableStateOf(defaultAccount) }

    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }

    val todayFormatted = remember { LocalDate.now(SHEET_ZONE).format(DISPLAY_DATE_FORMATTER) }
    var dateText by remember { mutableStateOf(todayFormatted) }
    var showDatePicker by remember { mutableStateOf(false) }

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

            // Quick Amount Chips ("Pay full" / "Custom amount")
            val openBalance = selectedCompanion?.balance ?: 0.0
            if (openBalance > 0.0) {
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
                val formattedBalance = formatRupees(openBalance, includeDecimals = false)
                val fullAmountStr = if (openBalance == openBalance.toLong().toDouble()) {
                    openBalance.toLong().toString()
                } else {
                    String.format(java.util.Locale.US, "%.2f", openBalance)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppChip(
                        text = stringResource(R.string.prompt_pay_full, formattedBalance),
                        selected = amountText == fullAmountStr,
                        onClick = {
                            amountText = fullAmountStr
                            focusManager.clearFocus()
                        }
                    )

                    AppChip(
                        text = stringResource(R.string.prompt_custom_amount),
                        selected = amountText != fullAmountStr && amountText.isNotEmpty(),
                        onClick = {
                            amountText = ""
                        }
                    )
                }
            }
        }

        // 2. Who You Repaid (Companion chips with open balance)
        val hasOpenDebts = companions.any { it.balance > 0.0 }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_friend_repaid),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            if (companions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    companions.forEach { companion ->
                        val isSelected = personName.equals(companion.name, ignoreCase = true)
                        val formattedBalance = formatRupees(companion.balance, includeDecimals = false).replace("₹", "Rs ")
                        val label = if (companion.balance > 0.0) {
                            "${companion.name}  $formattedBalance"
                        } else {
                            companion.name
                        }

                        AppChip(
                            text = label,
                            selected = isSelected,
                            onClick = {
                                focusManager.clearFocus()
                                personName = companion.name
                                if (companion.balance > 0.0 && amountText.isEmpty()) {
                                    amountText = if (companion.balance == companion.balance.toLong().toDouble()) {
                                        companion.balance.toLong().toString()
                                    } else {
                                        String.format(java.util.Locale.US, "%.2f", companion.balance)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            if (!hasOpenDebts) {
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

                OutlinedTextField(
                    value = personName,
                    onValueChange = { personName = it },
                    placeholder = { Text(stringResource(R.string.hint_friends_name), style = FinanceTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape = FinanceShapes.card,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
                )
            }
        }

        // 3. Paid From Account Chips (user-defined accounts, no balances)
        if (accounts.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.prompt_paid_from),
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
        }

        // 4. Settlement Note (optional)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_settlement_note),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = { Text(stringResource(R.string.hint_optional), style = FinanceTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = FinanceTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                    )
                },
                singleLine = true,
                shape = FinanceShapes.card,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
            )
        }

        // 5. Date Field (defaults to today; opens picker that rejects future dates)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_date_repaid),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            OutlinedTextField(
                value = dateText,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier
                            .size(FinanceTheme.sizes.iconSmall)
                            .clickable { showDatePicker = true }
                    )
                },
                singleLine = true,
                shape = FinanceShapes.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
            )
        }

        if (showDatePicker) {
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = runCatching {
                    LocalDate.parse(dateText, DISPLAY_DATE_FORMATTER)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                }.getOrElse {
                    runCatching {
                        LocalDate.parse(dateText)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant()
                            .toEpochMilli()
                    }.getOrElse {
                        LocalDate.now(SHEET_ZONE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    }
                },
                selectableDates = NoFutureSelectableDates
            )

            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            val selected = Instant.ofEpochMilli(ms)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            dateText = selected.format(DISPLAY_DATE_FORMATTER)
                        }
                        showDatePicker = false
                    }) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            ) {
                DatePicker(state = pickerState)
            }
        }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // 6. Action Buttons: Pinned ButtonPair with OutlinedButton "Cancel" and FilledButton "Record repayment" outside scrollable area
        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
        val isValid = parsedAmount > 0.0 && personName.isNotBlank()

        ButtonPair(
            outlinedText = stringResource(R.string.action_cancel),
            onOutlinedClick = onDismiss,
            filledText = stringResource(R.string.btn_record_repayment),
            onFilledClick = {
                val epochMillis = runCatching {
                    LocalDate.parse(dateText, DISPLAY_DATE_FORMATTER)
                        .atStartOfDay(SHEET_ZONE)
                        .toInstant()
                        .toEpochMilli()
                }.getOrElse {
                    runCatching {
                        LocalDate.parse(dateText)
                            .atStartOfDay(SHEET_ZONE)
                            .toInstant()
                            .toEpochMilli()
                    }.getOrElse {
                        System.currentTimeMillis()
                    }
                }
                onConfirm(parsedAmount, personName.trim(), epochMillis)
            },
            filledEnabled = isValid
        )
    }
}
