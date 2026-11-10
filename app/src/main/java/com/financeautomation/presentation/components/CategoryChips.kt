package com.financeautomation.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Category

/**
 * Interactive category selector displaying dynamic categories.
 * Strictly enforces plural "Savings" and CategoryConstants palette rules.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryChips(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<String> = CategoryConstants.DEFAULT_CATEGORIES
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val isSelected = category.equals(selectedCategory, ignoreCase = true)
            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                label = {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/**
 * Overload for CategoryChips taking domain [Category] models directly.
 */
@Composable
fun CategoryChips(
    categories: List<Category>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeNames = categories.filter { !it.isArchived }.map { it.name }
    val displayNames = if (activeNames.isNotEmpty()) activeNames else CategoryConstants.DEFAULT_CATEGORIES
    CategoryChips(
        selectedCategory = selectedCategory,
        onCategorySelected = onCategorySelected,
        modifier = modifier,
        categories = displayNames
    )
}
