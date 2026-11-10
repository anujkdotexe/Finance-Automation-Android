package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.domain.model.Companion
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.ExpenseUiState

/**
 * Unified bottom sheet content for adding, reviewing, and editing expenses (Design Guide 4 and 5.3).
 * Stateless composable built with SheetScaffold.
 */
@Composable
fun ExpenseSheet(
    state: ExpenseUiState,
    onAmountChanged: (String) -> Unit,
    onMerchantChanged: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onSplitModeChanged: (SplitMode) -> Unit,
    onCompanionToggled: (String) -> Unit,
    onAddCompanion: (String) -> Unit,
    onCustomShareChanged: (String) -> Unit,
    onConfirmAndSave: () -> Unit,
    onVoid: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showNewFriendDialog by remember { mutableStateOf(false) }
    var newFriendName by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val categories = remember(state.availableCategories) {
        if (state.availableCategories.isNotEmpty()) {
            state.availableCategories.map { it.name }
        } else {
            CategoryConstants.DEFAULT_CATEGORIES
        }
    }

    SheetScaffold(
        title = if (state.transactionId == null) "Add expense" else "Review expense",
        onDismiss = onCancel,
        modifier = modifier,
        stickyAction = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                PrimaryButton(
                    text = stringResource(R.string.action_save),
                    onClick = onConfirmAndSave,
                    enabled = state.amount > 0.0 && !state.isSaving,
                    isLoading = state.isSaving
                )

                if (state.transactionId != null) {
                    SecondaryButton(
                        text = stringResource(R.string.action_void),
                        onClick = onVoid,
                        enabled = !state.isSaving
                    )
                }

                TextAction(
                    text = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            // Status and possible duplicate warning
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(status = state.status)

                if (state.amount > 0.0) {
                    AmountText(amount = state.amount, tone = AmountTone.MONEY_OUT)
                }
            }

            if (state.isPossibleDuplicate) {
                Surface(
                    color = FinanceTheme.colors.moneyOwed.copy(alpha = 0.12f),
                    shape = FinanceTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.expense_duplicate_warning),
                        style = FinanceTheme.typography.bodyMedium,
                        color = FinanceTheme.colors.moneyOwed,
                        modifier = Modifier.padding(FinanceTheme.spacing.m)
                    )
                }
            }

            if (state.errorMessage != null) {
                Surface(
                    color = FinanceTheme.colors.moneyOut.copy(alpha = 0.12f),
                    shape = FinanceTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.errorMessage,
                        style = FinanceTheme.typography.bodyMedium,
                        color = FinanceTheme.colors.moneyOut,
                        modifier = Modifier.padding(FinanceTheme.spacing.m)
                    )
                }
            }

            // Amount Field
            FieldRow(label = stringResource(R.string.label_amount)) {
                OutlinedTextField(
                    value = state.amountText,
                    onValueChange = onAmountChanged,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.placeholder_amount_zero)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = FinanceTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FinanceTheme.colors.brand,
                        unfocusedBorderColor = FinanceTheme.colors.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Paid To / Merchant
            FieldRow(label = stringResource(R.string.label_paid_to)) {
                OutlinedTextField(
                    value = state.merchant,
                    onValueChange = onMerchantChanged,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.expense_merchant_placeholder)) },
                    shape = FinanceTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FinanceTheme.colors.brand,
                        unfocusedBorderColor = FinanceTheme.colors.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Category Chips
            FieldRow(label = stringResource(R.string.label_category)) {
                ChoiceChips(
                    items = categories,
                    selectedItem = state.selectedCategory,
                    onSelect = onCategorySelected
                )
            }

            // Split Mode Chips
            FieldRow(label = stringResource(R.string.label_split)) {
                ChoiceChips(
                    items = listOf(SplitMode.JUST_ME, SplitMode.SPLIT_EQUALLY, SplitMode.CUSTOM),
                    selectedItem = state.splitMode,
                    onSelect = onSplitModeChanged,
                    labelProvider = { mode ->
                        when (mode) {
                            SplitMode.JUST_ME -> "Just me"
                            SplitMode.SPLIT_EQUALLY -> "Split equally"
                            SplitMode.CUSTOM -> "Custom split"
                        }
                    }
                )
            }

            // Split companions / breakdown
            when (state.splitMode) {
                SplitMode.JUST_ME -> {
                    // No companion row needed
                }
                SplitMode.SPLIT_EQUALLY -> {
                    FieldRow(label = stringResource(R.string.label_friends)) {
                        Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                            if (state.allCompanions.isNotEmpty()) {
                                MultiChoiceChips(
                                    items = state.allCompanions,
                                    selectedItems = state.allCompanions.filter { it.id in state.selectedCompanionIds }.toSet(),
                                    onToggle = { companion -> onCompanionToggled(companion.id) },
                                    labelProvider = { it.name }
                                )
                            }

                            if (!showNewFriendDialog) {
                                TextAction(
                                    text = stringResource(R.string.action_add_friend),
                                    onClick = { showNewFriendDialog = true }
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = newFriendName,
                                        onValueChange = { newFriendName = it },
                                        placeholder = { Text(stringResource(R.string.placeholder_friend_name)) },
                                        singleLine = true,
                                        shape = FinanceTheme.shapes.small,
                                        modifier = Modifier.weight(1f)
                                    )
                                    PrimaryButton(
                                        text = stringResource(R.string.action_add),
                                        onClick = {
                                            if (newFriendName.isNotBlank()) {
                                                onAddCompanion(newFriendName.trim())
                                                newFriendName = ""
                                                showNewFriendDialog = false
                                            }
                                        },
                                        enabled = newFriendName.isNotBlank()
                                    )
                                }
                            }

                            if (state.selectedCompanionIds.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.expense_my_share_format, formatRupees(state.myShare, includeDecimals = true)),
                                        style = FinanceTheme.typography.bodyMedium,
                                        color = FinanceTheme.colors.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.expense_owed_to_me_format, formatRupees(state.owedToMe, includeDecimals = true)),
                                        style = FinanceTheme.typography.bodyMedium,
                                        color = FinanceTheme.colors.moneyIn
                                    )
                                }
                            }
                        }
                    }
                }
                SplitMode.CUSTOM -> {
                    FieldRow(label = stringResource(R.string.label_my_share_rs)) {
                        Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                            OutlinedTextField(
                                value = state.customShareText,
                                onValueChange = onCustomShareChanged,
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.placeholder_amount_zero)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                shape = FinanceTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (state.owedToMe > 0.0) {
                                Text(
                                    text = stringResource(R.string.expense_owed_to_me_format, formatRupees(state.owedToMe, includeDecimals = true)),
                                    style = FinanceTheme.typography.bodyMedium,
                                    color = FinanceTheme.colors.moneyIn
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun ExpenseSheetPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        ExpenseSheet(
            state = ExpenseUiState(
                amount = 450.0,
                amountText = "450",
                merchant = "Swiggy",
                selectedCategory = "Food & Drink",
                splitMode = SplitMode.JUST_ME,
                status = TransactionStatus.PERSONAL
            ),
            onAmountChanged = {},
            onMerchantChanged = {},
            onCategorySelected = {},
            onSplitModeChanged = {},
            onCompanionToggled = {},
            onAddCompanion = {},
            onCustomShareChanged = {},
            onConfirmAndSave = {},
            onVoid = {},
            onCancel = {}
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ExpenseSheetPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        ExpenseSheet(
            state = ExpenseUiState(
                amount = 1200.0,
                amountText = "1200",
                merchant = "Dinner with friends",
                selectedCategory = "Food & Drink",
                splitMode = SplitMode.SPLIT_EQUALLY,
                allCompanions = listOf(
                    Companion(id = "1", name = "Aditya"),
                    Companion(id = "2", name = "Rohit")
                ),
                selectedCompanionIds = setOf("1"),
                myShare = 600.0,
                owedToMe = 600.0,
                status = TransactionStatus.PENDING
            ),
            onAmountChanged = {},
            onMerchantChanged = {},
            onCategorySelected = {},
            onSplitModeChanged = {},
            onCompanionToggled = {},
            onAddCompanion = {},
            onCustomShareChanged = {},
            onConfirmAndSave = {},
            onVoid = {},
            onCancel = {}
        )
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun ExpenseSheetPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        ExpenseSheet(
            state = ExpenseUiState(
                amount = 250.0,
                amountText = "250",
                merchant = "Coffee",
                selectedCategory = "Food & Drink"
            ),
            onAmountChanged = {},
            onMerchantChanged = {},
            onCategorySelected = {},
            onSplitModeChanged = {},
            onCompanionToggled = {},
            onAddCompanion = {},
            onCustomShareChanged = {},
            onConfirmAndSave = {},
            onVoid = {},
            onCancel = {}
        )
    }
}

