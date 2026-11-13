package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

private val CATEGORY_DOT_COLORS = listOf(
    Color(0xFFF59E0B),
    Color(0xFF006874),
    Color(0xFF0284C7),
    Color(0xFF6366F1),
    Color(0xFF8B5CF6),
    Color(0xFFEC4899),
    Color(0xFF14B8A6),
    Color(0xFFF97316),
    Color(0xFF10B981),
    Color(0xFF64748B),
    Color(0xFF94A3B8)
)

@Composable
fun CategoriesScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showAddCategorySheet by remember { mutableStateOf(false) }

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
            TextButton(onClick = { showAddCategorySheet = true }) {
                Text(
                    text = stringResource(R.string.categories_action_add),
                    style = FinanceTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // List of Categories
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            state.categories.forEachIndexed { index, category ->
                val isLocked = category.name.equals("Savings", ignoreCase = true) ||
                    category.name.equals("Transfer", ignoreCase = true)
                val dotColor = CATEGORY_DOT_COLORS[index % CATEGORY_DOT_COLORS.size]

                CategoryItemCard(
                    category = category,
                    dotColor = dotColor,
                    isLocked = isLocked,
                    onToggleBurn = { enabled ->
                        // Switch is "Count toward living burn" so enabled == true means !isExcludedFromLivingBurn
                        viewModel.onToggleCategoryLivingBurnExclusion(category.id, !enabled)
                    }
                )
            }
        }
    }

    if (showAddCategorySheet) {
        AddCategorySheet(
            onDismiss = { showAddCategorySheet = false },
            onSave = { name ->
                viewModel.onAddCategory(name, isExcludedFromLivingBurn = false)
                showAddCategorySheet = false
            }
        )
    }
}

@Composable
private fun CategoryItemCard(
    category: Category,
    dotColor: Color,
    isLocked: Boolean,
    onToggleBurn: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
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
