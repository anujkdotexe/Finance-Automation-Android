package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.domain.model.Category
import com.financeautomation.presentation.components.HeaderActionButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.theme.categoryDotColor
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun CategoriesScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showAddCategorySheet by rememberSaveable { mutableStateOf(false) }
    var selectedCategoryForEdit by remember { mutableStateOf<Category?>(null) }
    var budgetCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val budgetRepository = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.financeautomation.FinanceApplication)
        .appContainer.budgetRepository
    val budgets by budgetRepository.observeBudgets().collectAsState(initial = emptyList())
    val budgetByName = remember(budgets) { budgets.associateBy { it.category.trim().lowercase() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        // Section Header with Add category button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.categories_section_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.categories_section_subtitle),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HeaderActionButton(
                text = stringResource(R.string.categories_action_add),
                onClick = { showAddCategorySheet = true }
            )
        }

        // List of Categories
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            state.categories.forEachIndexed { index, category ->
                val isLocked = category.name.equals("Savings", ignoreCase = true) ||
                    category.name.equals("Transfer", ignoreCase = true)
                val dotColor = categoryDotColor(category)

                CategoryItemCard(
                    category = category,
                    dotColor = dotColor,
                    isLocked = isLocked,
                    onClick = {
                        if (!isLocked) {
                            selectedCategoryForEdit = category
                        }
                    },
                    budgetAmount = budgetByName[category.name.trim().lowercase()]?.monthlyAmount,
                    onOpenBudget = { budgetCategory = category.name },
                    onToggleBurn = { enabled ->
                        viewModel.onToggleCategoryLivingBurnExclusion(category.id, !enabled)
                    }
                )
            }
        }
    }

    budgetCategory?.let { name ->
        BudgetSheet(
            category = name,
            existingAmount = budgetByName[name.trim().lowercase()]?.monthlyAmount,
            repository = budgetRepository,
            onDismiss = { budgetCategory = null }
        )
    }

    if (showAddCategorySheet) {
        AddCategorySheet(
            onDismiss = { showAddCategorySheet = false },
            onSave = { name, colorName ->
                viewModel.onAddCategory(name, isExcludedFromLivingBurn = false, colorName = colorName)
                showAddCategorySheet = false
            }
        )
    }

    selectedCategoryForEdit?.let { cat ->
        var editName by rememberSaveable { mutableStateOf(cat.name) }
        AlertDialog(
            onDismissRequest = { selectedCategoryForEdit = null },
            title = {
                Text(
                    text = stringResource(R.string.categories_edit_title),
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
                            text = stringResource(R.string.categories_field_name),
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

                    OutlinedButton(
                        onClick = {
                            viewModel.onArchiveCategory(cat.id)
                            selectedCategoryForEdit = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.categories_hide), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank()) {
                            viewModel.onRenameCategory(cat.id, editName.trim())
                            selectedCategoryForEdit = null
                        }
                    },
                    enabled = editName.isNotBlank() && editName.trim() != cat.name
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { selectedCategoryForEdit = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun CategoryItemCard(
    category: Category,
    dotColor: Color,
    isLocked: Boolean,
    onClick: () -> Unit = {},
    budgetAmount: Double?,
    onOpenBudget: () -> Unit,
    onToggleBurn: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier
            .fillMaxWidth()
            .clip(FinanceShapes.large)
            .clickable(enabled = !isLocked, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val editDescription = stringResource(R.string.budget_row_edit_cd, category.name)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    .clip(FinanceShapes.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Box(
                    modifier = Modifier
                        .size(FinanceTheme.spacing.compact)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category.name,
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isLocked) {
                        Text(
                            text = stringResource(R.string.categories_always_excluded),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isLocked) {
                        Text(
                            text = if (budgetAmount != null) {
                                stringResource(R.string.budget_row_amount, com.financeautomation.core.utils.formatRupees(budgetAmount))
                            } else {
                                stringResource(R.string.budget_row_none)
                            },
                            style = FinanceTheme.typography.bodySmall,
                            color = if (budgetAmount != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .defaultMinSize(
                                    minWidth = FinanceTheme.sizes.minTouchTarget,
                                    minHeight = FinanceTheme.sizes.minTouchTarget
                                )
                                .clickable(onClickLabel = editDescription, onClick = onOpenBudget)
                        )
                    }
                }
            }

            Switch(
                checked = if (isLocked) false else !category.isExcludedFromLivingBurn,
                onCheckedChange = { if (!isLocked) onToggleBurn(it) },
                enabled = !isLocked,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = FinanceTheme.colors.surfaceContainerHighest
                )
            )
        }
    }
}
