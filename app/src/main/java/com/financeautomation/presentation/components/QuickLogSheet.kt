package com.financeautomation.presentation.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.Commute
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.utils.AmountInputSanitizer
import com.financeautomation.core.utils.formatRupeesAuto
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.financeautomation.core.model.SplitMode
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.QuickLogUiState

/**
 * Rebuilt Expense bottom sheet following the frozen design specification:
 * - Amount FIRST in mono 32 Medium.
 * - 10 real categories in two rows.
 * - Account chips (user-defined accounts, no balances).
 * - Segmented control: Just me, Split equally, Custom (shown once).
 * - Split equally: friend chips + Add friend, computing My share and Owed to me.
 * - Custom: personal share input and computed Owed to me.
 * - Unreadable amount fallback with "Amount not found" and expandable message text.
 * - Buttons: FilledButton "Save expense", OutlinedButton "Cancel" as a pair.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickLogSheet(
    state: QuickLogUiState,
    onAmountChanged: (String) -> Unit,
    onPayeeChanged: (String) -> Unit,
    onDateSelected: (Long) -> Unit = {},
    onAccountSelected: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onSplitModeChanged: (SplitMode) -> Unit,
    onCompanionToggled: (String) -> Unit,
    onAddCompanion: (String) -> Unit,
    onCustomShareChanged: (String) -> Unit,
    onEndTrip: () -> Unit,
    onConfirmAndSave: () -> Unit,
    onDismiss: () -> Unit,
    onVoid: (() -> Unit)? = null,
    onKeyboardFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val amountFocusRequester = remember { FocusRequester() }
    val verticalScroll = rememberScrollState()
    var showRawMessage by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.isNeedsAmount, state.captureId) {
        if (state.isNeedsAmount || state.captureId == null) {
            amountFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(verticalScroll.isScrollInProgress) {
        if (verticalScroll.isScrollInProgress) {
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
                .verticalScroll(verticalScroll),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            // 1. Amount First in Mono (32 Medium)
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
                    value = state.amountText,
                    onValueChange = { input ->
                        if (AmountInputSanitizer.isValidExpressionInput(input)) {
                            onAmountChanged(input)
                        }
                    },
                    enabled = !state.isFinancialLocked,
                    singleLine = true,
                    textStyle = FinanceTheme.typography.amountHero.copy(
                        color = FinanceTheme.colors.onSurface,
                        textAlign = TextAlign.Center
                    ),
                    cursorBrush = SolidColor(FinanceTheme.colors.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        .defaultMinSize(minWidth = FinanceTheme.sizes.emptyStateIllustration)
                        .focusRequester(amountFocusRequester)
                        .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.Center) {
                            if (state.amountText.isEmpty()) {
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

            if (state.amountText.any { it in "+-*/" } && state.amount > 0.0) {
                Text(
                    text = "= ${formatRupeesAuto(state.amount)}",
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = FinanceTheme.spacing.xxs)
                )
            }

            // Unreadable Amount Fallback State - only shown for captured notifications with no parsed amount
            if (state.isNeedsAmount && !state.captureId.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = FinanceTheme.colors.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                    Text(
                        text = stringResource(R.string.amount_not_found_message),
                        style = FinanceTheme.typography.bodyMedium,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))

                LinkButton(
                    text = if (showRawMessage) {
                        stringResource(R.string.action_hide_message)
                    } else {
                        stringResource(R.string.action_show_message)
                    },
                    onClick = { showRawMessage = !showRawMessage }
                )

                AnimatedVisibility(
                    visible = showRawMessage,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = FinanceShapes.card,
                        color = FinanceTheme.colors.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FinanceTheme.spacing.xs)
                    ) {
                        Column(modifier = Modifier.padding(FinanceTheme.spacing.m)) {
                            Text(
                                text = stringResource(R.string.label_original_message),
                                style = FinanceTheme.typography.labelUppercase,
                                color = FinanceTheme.colors.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))
                            Text(
                                text = state.payee.ifBlank { stringResource(R.string.hint_add_description) },
                                style = FinanceTheme.typography.bodySmall,
                                color = FinanceTheme.colors.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 2. What Was This For / Description Field
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_what_was_this_for),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            OutlinedTextField(
                value = state.payee,
                onValueChange = onPayeeChanged,
                placeholder = {
                    Text(
                        text = stringResource(R.string.hint_add_description),
                        style = FinanceTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                shape = FinanceShapes.card,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
            )
        }

        // Date Chips: Today, Yesterday, Pick date (no future dates)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.quick_log_date_label),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            val context = LocalView.current.context
            val tz = remember { TimeZone.getTimeZone("Asia/Kolkata") }
            val nowTs = remember { System.currentTimeMillis() }
            val selectedTs = state.captureTimestamp ?: nowTs

            val isToday = remember(selectedTs) {
                isSameDay(selectedTs, nowTs, tz)
            }
            val yesterdayTs = remember {
                val c = Calendar.getInstance(tz)
                c.add(Calendar.DAY_OF_YEAR, -1)
                c.timeInMillis
            }
            val isYesterday = remember(selectedTs) {
                isSameDay(selectedTs, yesterdayTs, tz)
            }
            val isPickDate = !isToday && !isYesterday

            val defaultPickDateLabel = stringResource(R.string.quick_log_pick_date)
            val pickDateLabel = remember(selectedTs, isPickDate, defaultPickDateLabel) {
                if (isPickDate) {
                    val sdf = SimpleDateFormat("d MMM", Locale.US).apply { timeZone = tz }
                    sdf.format(Date(selectedTs))
                } else {
                    defaultPickDateLabel
                }
            }

            val todayLabel = stringResource(R.string.quick_log_today)
            val yesterdayLabel = stringResource(R.string.quick_log_yesterday)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                AppChip(
                    text = todayLabel,
                    selected = isToday,
                    onClick = {
                        onDateSelected(System.currentTimeMillis())
                    }
                )
                AppChip(
                    text = yesterdayLabel,
                    selected = isYesterday,
                    onClick = {
                        val cal = Calendar.getInstance(tz)
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                        onDateSelected(cal.timeInMillis)
                    }
                )
                AppChip(
                    text = pickDateLabel,
                    selected = isPickDate,
                    onClick = {
                        val cal = Calendar.getInstance(tz).apply { timeInMillis = selectedTs }
                        android.app.DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val pickedCal = Calendar.getInstance(tz).apply {
                                    set(Calendar.YEAR, year)
                                    set(Calendar.MONTH, month)
                                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                }
                                val chosen = minOf(pickedCal.timeInMillis, System.currentTimeMillis())
                                onDateSelected(chosen)
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        ).apply {
                            datePicker.maxDate = System.currentTimeMillis()
                        }.show()
                    }
                )
            }
        }

        // 3. Category Chips: Exactly 10 real categories in wrapping FlowRow
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_category),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            val allCategories = remember {
                listOf(
                    CategoryConstants.CATEGORY_FOOD_AND_DRINK,
                    CategoryConstants.CATEGORY_GROCERIES,
                    CategoryConstants.CATEGORY_TRANSIT,
                    CategoryConstants.CATEGORY_SUBSCRIPTIONS,
                    CategoryConstants.CATEGORY_SHOPPING,
                    CategoryConstants.CATEGORY_TRIP_TRAVEL,
                    CategoryConstants.CATEGORY_HEALTH,
                    CategoryConstants.CATEGORY_FAMILY,
                    CategoryConstants.CATEGORY_SAVINGS,
                    CategoryConstants.CATEGORY_OTHER
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                allCategories.forEach { categoryName ->
                    val isSelected = categoryName.equals(state.selectedCategory, ignoreCase = true)
                    AppChip(
                        text = categoryName,
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus()
                            onCategorySelected(categoryName)
                        },
                        leadingIcon = {
                            CategoryChipIcon(categoryName = categoryName, isSelected = isSelected)
                        }
                    )
                }
            }
        }

        // 4. Account Chips (user-defined accounts, no balances) in wrapping FlowRow
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_paid_from),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            val baseAccountNames = if (state.availableAccounts.isNotEmpty()) {
                state.availableAccounts.map { it.name }
            } else {
                listOf("UPI", "Card", "Cash", "Bank", "Other")
            }
            val accountNames = if (state.selectedAccount.isNotBlank() &&
                baseAccountNames.none { it.equals(state.selectedAccount, ignoreCase = true) }
            ) {
                baseAccountNames + state.selectedAccount
            } else {
                baseAccountNames
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                accountNames.forEach { accountName ->
                    val isSelected = accountName.equals(state.selectedAccount, ignoreCase = true)
                    AppChip(
                        text = accountName,
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus()
                            onAccountSelected(accountName)
                        }
                    )
                }
            }
        }

        // 5. Segmented Control: Just me, Split equally, Custom (shown once)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.prompt_split_mode),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            val justMeLabel = stringResource(R.string.split_just_me)
            val splitEquallyLabel = stringResource(R.string.split_equally)
            val customSplitLabel = stringResource(R.string.split_custom)

            SegmentedChoice(
                options = listOf(SplitMode.JUST_ME, SplitMode.SPLIT_EQUALLY, SplitMode.CUSTOM),
                selectedOption = state.splitMode,
                onOptionSelected = { mode ->
                    focusManager.clearFocus()
                    onSplitModeChanged(mode)
                },
                labelProvider = { mode ->
                    when (mode) {
                        SplitMode.JUST_ME -> justMeLabel
                        SplitMode.SPLIT_EQUALLY -> splitEquallyLabel
                        SplitMode.CUSTOM -> customSplitLabel
                    }
                }
            )
        }

        // 6. Companion Split Content (Split Equally / Custom)
        CompanionSplitSection(
            state = state,
            onCompanionToggled = onCompanionToggled,
            onAddCompanion = onAddCompanion,
            onCustomShareChanged = onCustomShareChanged,
            onKeyboardFocusChanged = onKeyboardFocusChanged
        )

        // 7. Active Trip Tag Banner
        state.activeTripTag?.let { activeTrip ->
            ActiveTripBanner(
                tripName = activeTrip.name,
                onEndTrip = onEndTrip
            )
        }

        // 8. Duplicate Warning (quiet, calm indicator)
        if (state.isPossibleDuplicate) {
            Surface(
                shape = FinanceShapes.card,
                color = FinanceTheme.colors.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(FinanceTheme.spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = FinanceTheme.colors.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                    Text(
                        text = stringResource(R.string.expense_duplicate_warning),
                        style = FinanceTheme.typography.bodySmall,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                }
            }
        }

        // 9. Error Message Display
        state.errorMessage?.let { errorMsg ->
            Text(
                text = errorMsg.asString(),
                style = FinanceTheme.typography.bodySmall,
                color = FinanceTheme.colors.error
            )
        }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // 10. Action Buttons: Pinned FilledButton "Save expense", OutlinedButton "Cancel" as a pair outside scrollable area
        ButtonPair(
            outlinedText = stringResource(R.string.action_cancel),
            onOutlinedClick = onDismiss,
            filledText = stringResource(R.string.btn_save_expense),
            onFilledClick = {
                val hapticConstant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
                view.performHapticFeedback(hapticConstant)
                onConfirmAndSave()
            },
            filledEnabled = state.isSaveEnabled,
            isFilledLoading = state.isSaving
        )

        // Optional Void Action for already-logged transactions
        if (state.transactionId != null && onVoid != null) {
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            OutlinedButton(
                text = stringResource(R.string.action_void),
                onClick = onVoid,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun isSameDay(t1: Long, t2: Long, tz: TimeZone): Boolean {
    val c1 = Calendar.getInstance(tz).apply { timeInMillis = t1 }
    val c2 = Calendar.getInstance(tz).apply { timeInMillis = t2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
           c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

/**
 * Material symbol icon corresponding to each real category.
 */
@Composable
private fun CategoryChipIcon(
    categoryName: String,
    isSelected: Boolean
) {
    val icon = when (categoryName) {
        CategoryConstants.CATEGORY_FOOD_AND_DRINK -> Icons.Rounded.Restaurant
        CategoryConstants.CATEGORY_GROCERIES -> Icons.Rounded.ShoppingCart
        CategoryConstants.CATEGORY_TRANSIT -> Icons.Rounded.Commute
        CategoryConstants.CATEGORY_SUBSCRIPTIONS -> Icons.Rounded.Subscriptions
        CategoryConstants.CATEGORY_SHOPPING -> Icons.Rounded.ShoppingBag
        CategoryConstants.CATEGORY_TRIP_TRAVEL -> Icons.Rounded.Flight
        CategoryConstants.CATEGORY_HEALTH -> Icons.Rounded.MedicalServices
        CategoryConstants.CATEGORY_FAMILY -> Icons.Rounded.FamilyRestroom
        CategoryConstants.CATEGORY_SAVINGS -> Icons.Rounded.Savings
        else -> Icons.Rounded.MoreHoriz
    }

    val tint = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        FinanceTheme.colors.onSurfaceVariant
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
    )
}

@androidx.compose.ui.tooling.preview.Preview(name = "QuickLogSheet Font 1.3 - 360dp", widthDp = 360, fontScale = 1.3f, showBackground = true)
@Composable
private fun QuickLogSheetPreviewLarge() {
    com.financeautomation.presentation.theme.FinanceAutomationTheme {
        androidx.compose.material3.Surface {
            QuickLogSheet(
                state = QuickLogUiState(
                    amountText = "450.00",
                    amount = 450.0,
                    payee = "Swiggy Bangalore",
                    selectedCategory = "Food & Drink",
                    selectedAccount = "HDFC"
                ),
                onAmountChanged = {},
                onPayeeChanged = {},
                onAccountSelected = {},
                onCategorySelected = {},
                onSplitModeChanged = {},
                onCompanionToggled = {},
                onAddCompanion = {},
                onCustomShareChanged = {},
                onEndTrip = {},
                onConfirmAndSave = {},
                onDismiss = {}
            )
        }
    }
}
