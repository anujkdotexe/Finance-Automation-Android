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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Companion
import com.financeautomation.presentation.components.AppChip
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.FilledButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SHEET_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
private val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.US)

/**
 * Rebuilt Friend Paid For Me flow sheet following the frozen design specification:
 * - Description line ("What was this for?").
 * - Who paid (friend chips + "Add friend").
 * - Amount hero in mono.
 * - Category chips (real categories).
 * - Date picker (no future dates).
 * - ButtonPair: OutlinedButton "Cancel", FilledButton "Record debt".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FriendPaidFlowSheet(
    companions: List<Companion>,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, creditor: String, whatFor: String) -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var amountText by remember { mutableStateOf("") }
    var creditorName by remember { mutableStateOf("") }
    var whatForText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(CategoryConstants.CATEGORY_FOOD_AND_DRINK) }

    val todayFormatted = remember { LocalDate.now(SHEET_ZONE).format(DISPLAY_DATE_FORMATTER) }
    var dateText by remember { mutableStateOf(todayFormatted) }
    var showDatePicker by remember { mutableStateOf(false) }

    var showAddFriendInput by remember { mutableStateOf(false) }
    var newFriendName by remember { mutableStateOf("") }

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

        // 2. Who Paid (Friend chips + Add friend)
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.prompt_friend_who_paid),
                    style = FinanceTheme.typography.labelUppercase,
                    color = FinanceTheme.colors.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                companions.forEach { companion ->
                    val isSelected = creditorName.equals(companion.name, ignoreCase = true)
                    AppChip(
                        text = companion.name,
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus()
                            creditorName = companion.name
                        }
                    )
                }

                // Add Friend Chip
                AppChip(
                    text = stringResource(R.string.action_add_friend),
                    selected = false,
                    onClick = {
                        focusManager.clearFocus()
                        showAddFriendInput = !showAddFriendInput
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = FinanceTheme.colors.primary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                    }
                )
            }

            if (showAddFriendInput) {
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newFriendName,
                        onValueChange = { newFriendName = it },
                        placeholder = { Text(stringResource(R.string.hint_friends_name), style = FinanceTheme.typography.bodyMedium) },
                        singleLine = true,
                        shape = FinanceShapes.card,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (newFriendName.isNotBlank()) {
                                    creditorName = newFriendName.trim()
                                    newFriendName = ""
                                    showAddFriendInput = false
                                    focusManager.clearFocus()
                                }
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
                    )

                    FilledButton(
                        text = stringResource(R.string.action_add),
                        onClick = {
                            if (newFriendName.isNotBlank()) {
                                creditorName = newFriendName.trim()
                                newFriendName = ""
                                showAddFriendInput = false
                                focusManager.clearFocus()
                            }
                        },
                        enabled = newFriendName.isNotBlank()
                    )
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            OutlinedTextField(
                value = creditorName,
                onValueChange = { creditorName = it },
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

        // 3. Description Line ("What was this for?")
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_what_was_this_for),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            OutlinedTextField(
                value = whatForText,
                onValueChange = { whatForText = it },
                placeholder = { Text(stringResource(R.string.hint_add_description), style = FinanceTheme.typography.bodyMedium) },
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

        // 4. Category Chips in wrapping FlowRow
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_category),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                CategoryConstants.DEFAULT_CATEGORIES.forEach { category ->
                    val isSelected = selectedCategory.equals(category, ignoreCase = true)
                    AppChip(
                        text = category,
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus()
                            selectedCategory = category
                        }
                    )
                }
            }
        }

        // 5. Date Picker (no future dates)
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
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = runCatching {
                    LocalDate.parse(dateText, DISPLAY_DATE_FORMATTER)
                        .atStartOfDay(ZoneId.of("UTC"))
                        .toInstant()
                        .toEpochMilli()
                }.getOrElse {
                    runCatching {
                        LocalDate.parse(dateText)
                            .atStartOfDay(ZoneId.of("UTC"))
                            .toInstant()
                            .toEpochMilli()
                    }.getOrElse {
                        LocalDate.now(SHEET_ZONE).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
                    }
                },
                selectableDates = NoFutureSelectableDates
            )

            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selected = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.of("UTC"))
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
                DatePicker(state = datePickerState)
            }
        }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // 6. Action Buttons: Pinned ButtonPair with OutlinedButton "Cancel" and FilledButton "Record debt" outside scrollable area
        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
        val isValid = parsedAmount > 0.0 && creditorName.isNotBlank() && whatForText.isNotBlank()

        ButtonPair(
            outlinedText = stringResource(R.string.action_cancel),
            onOutlinedClick = onDismiss,
            filledText = stringResource(R.string.btn_record_debt),
            onFilledClick = {
                val effectiveWhatFor = if (whatForText.isNotBlank()) {
                    whatForText.trim()
                } else {
                    selectedCategory
                }
                onConfirm(parsedAmount, creditorName.trim(), effectiveWhatFor)
            },
            filledEnabled = isValid
        )
    }
}
