package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Category
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.HeaderActionButton
import com.financeautomation.presentation.theme.CategoryColorTokens
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
    var selectedCategoryForColor by remember { mutableStateOf<Category?>(null) }
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

        if (state.categories.isEmpty()) {
            EmptyState(
                message = stringResource(R.string.categories_empty_message)
            )
        } else {
            CategoryList(
                categories = state.categories,
                onReorderCategories = { viewModel.onReorderCategories(it) },
                budgetByName = budgetByName.mapValues { it.value.monthlyAmount },
                onOpenBudget = { category ->
                    val onOpenBudget = { budgetCategory = category.name }
                    onOpenBudget()
                },
                onToggleBurn = { catId, enabled ->
                    viewModel.onToggleCategoryLivingBurnExclusion(catId, !enabled)
                },
                onClick = { category -> selectedCategoryForEdit = category },
                onChangeColor = { selectedCategoryForColor = it },
                onArchive = { viewModel.onArchiveCategory(it) }
            )
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

    selectedCategoryForColor?.let { cat ->
        AlertDialog(
            onDismissRequest = { selectedCategoryForColor = null },
            title = {
                Text(
                    text = stringResource(R.string.categories_color_dialog_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    val palette = CategoryColorTokens.PALETTE
                    val chunked = palette.chunked(4)
                    chunked.forEach { rowTokens ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            rowTokens.forEach { token ->
                                val isSelected = cat.iconName.equals(token.name, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(FinanceTheme.sizes.iconLarge)
                                        .clip(CircleShape)
                                        .background(token.light)
                                        .clickable {
                                            viewModel.onUpdateCategoryColor(cat.id, token.name)
                                            selectedCategoryForColor = null
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // "No colour" option
                    val isNoColorSelected = com.financeautomation.presentation.theme.isNoColor(cat)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.onUpdateCategoryColor(cat.id, CategoryColorTokens.NO_COLOR)
                                selectedCategoryForColor = null
                            }
                            .padding(vertical = FinanceTheme.spacing.xs, horizontal = FinanceTheme.spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(FinanceTheme.sizes.iconLarge)
                                .clip(CircleShape)
                                .border(
                                    width = FinanceTheme.sizes.strokeThick,
                                    color = if (isNoColorSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isNoColorSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.category_color_none),
                            style = FinanceTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedCategoryForColor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
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
internal fun CategoryList(
    categories: List<Category>,
    onReorderCategories: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    budgetByName: Map<String, Double?> = emptyMap(),
    onOpenBudget: (Category) -> Unit = {},
    onToggleBurn: (String, Boolean) -> Unit = { _, _ -> },
    onClick: (Category) -> Unit = {},
    onChangeColor: (Category) -> Unit = {},
    onArchive: (String) -> Unit = {}
) {
    var localCategories by remember(categories) { mutableStateOf(categories) }
    var draggingCategoryId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var measuredRowHeightPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    val defaultRowHeightPx = FinanceTheme.sizes.minRowHeight.value * density
    val moveUpLabel = stringResource(R.string.accessibility_action_move_up)
    val moveDownLabel = stringResource(R.string.accessibility_action_move_down)
    val currentCategories by rememberUpdatedState(categories)
    val currentLocalCategories by rememberUpdatedState(localCategories)
    val currentOnReorder by rememberUpdatedState(onReorderCategories)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
    ) {
        localCategories.forEachIndexed { index, category ->
            key(category.id) {
                val isLocked = CategoryConstants.isProtectedCategory(category.name)
                val dotColor = categoryDotColor(category)
                val isDragging = category.id == draggingCategoryId

                CategoryItemCard(
                    category = category,
                    dotColor = dotColor,
                    isLocked = isLocked,
                    isDragging = isDragging,
                    budgetAmount = budgetByName[category.name.trim().lowercase()],
                    onOpenBudget = { onOpenBudget(category) },
                    onToggleBurn = { enabled -> onToggleBurn(category.id, enabled) },
                    onClick = { onClick(category) },
                    onChangeColor = { onChangeColor(category) },
                    onArchive = { onArchive(category.id) },
                    onMoveUp = if (index > 0) {
                        {
                            val mutable = localCategories.toMutableList()
                            val item = mutable.removeAt(index)
                            mutable.add(index - 1, item)
                            localCategories = mutable
                            onReorderCategories(mutable.map { it.id })
                        }
                    } else null,
                    onMoveDown = if (index < localCategories.size - 1) {
                        {
                            val mutable = localCategories.toMutableList()
                            val item = mutable.removeAt(index)
                            mutable.add(index + 1, item)
                            localCategories = mutable
                            onReorderCategories(mutable.map { it.id })
                        }
                    } else null,
                    modifier = Modifier
                        .onSizeChanged { size ->
                            if (size.height > 0) {
                                measuredRowHeightPx = size.height.toFloat()
                            }
                        }
                        .semantics {
                            customActions = buildList {
                                if (index > 0) {
                                    add(CustomAccessibilityAction(label = moveUpLabel) {
                                        val mutable = localCategories.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index - 1, item)
                                        localCategories = mutable
                                        onReorderCategories(mutable.map { it.id })
                                        true
                                    })
                                }
                                if (index < localCategories.size - 1) {
                                    add(CustomAccessibilityAction(label = moveDownLabel) {
                                        val mutable = localCategories.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index + 1, item)
                                        localCategories = mutable
                                        onReorderCategories(mutable.map { it.id })
                                        true
                                    })
                                }
                            }
                        },
                    dragHandleModifier = Modifier.pointerInput(category.id) {
                        detectDragGestures(
                            onDragStart = {
                                draggingCategoryId = category.id
                                dragOffsetY = 0f
                            },
                            onDragEnd = {
                                val finalIds = currentLocalCategories.map { it.id }
                                if (ReorderLogic.hasOrderChanged(finalIds, currentCategories.map { it.id })) {
                                    currentOnReorder(finalIds)
                                }
                                draggingCategoryId = null
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                draggingCategoryId = null
                                dragOffsetY = 0f
                                localCategories = ReorderLogic.resolveCancelReset(currentCategories)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                                val rowHeight = ReorderLogic.calculateEffectiveRowHeight(measuredRowHeightPx, defaultRowHeightPx)
                                val swapThreshold = ReorderLogic.calculateSwapThreshold(rowHeight)
                                val stepResult = ReorderLogic.processDragStep(
                                    currentList = currentLocalCategories,
                                    draggingId = draggingCategoryId ?: "",
                                    currentOffsetY = dragOffsetY,
                                    rowHeightPx = rowHeight,
                                    swapThresholdPx = swapThreshold,
                                    idSelector = { it.id }
                                )
                                if (stepResult.didSwap) {
                                    localCategories = stepResult.updatedList
                                }
                                dragOffsetY = stepResult.remainingOffsetY
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CategoryItemCard(
    category: Category,
    dotColor: Color,
    isLocked: Boolean,
    isDragging: Boolean = false,
    onClick: () -> Unit = {},
    budgetAmount: Double?,
    onOpenBudget: () -> Unit,
    onToggleBurn: (Boolean) -> Unit,
    onChangeColor: () -> Unit = {},
    onArchive: () -> Unit = {},
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = if (isDragging) FinanceTheme.colors.surfaceContainerHigh else FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = if (isDragging) FinanceTheme.sizes.shadowElevationMedium else FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier
            .fillMaxWidth()
            .clip(FinanceShapes.large)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                .padding(start = FinanceTheme.spacing.s, end = FinanceTheme.spacing.xs, top = FinanceTheme.spacing.xs, bottom = FinanceTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Drag Handle
            val reorderCd = stringResource(R.string.cd_reorder_category, category.name)
            Box(
                modifier = Modifier
                    .size(FinanceTheme.sizes.minTouchTarget)
                    .then(dragHandleModifier)
                    .semantics {
                        contentDescription = reorderCd
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 2 & 3. Category info (dot, name and budget)
            val editDescription = stringResource(R.string.budget_row_edit_cd, category.name)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    .clip(FinanceShapes.small)
                    .clickable(enabled = !isLocked, onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                if (dotColor != Color.Transparent) {
                    Box(
                        modifier = Modifier
                            .size(FinanceTheme.spacing.compact)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }

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
                            text = stringResource(R.string.categories_locked_subtitle),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

            // 4. Living Burn Switch
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

            // 5. Overflow Menu (Rename, Colour, Archive)
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(FinanceTheme.sizes.minTouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.cd_category_options, category.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (isLocked) {
                        DropdownMenuItem(
                            text = { Text("System category (locked)") },
                            onClick = { menuExpanded = false },
                            enabled = false
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.categories_menu_rename)) },
                            onClick = {
                                menuExpanded = false
                                onClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.categories_menu_color)) },
                            onClick = {
                                menuExpanded = false
                                onChangeColor()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.categories_hide),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onArchive()
                            }
                        )
                    }
                }
            }
        }
    }
}
