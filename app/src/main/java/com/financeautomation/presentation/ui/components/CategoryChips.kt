package com.financeautomation.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.core.constants.CategoryConstants

@Composable
fun CategoryChips(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<String> = CategoryConstants.DEFAULT_CATEGORIES
) {
    com.financeautomation.presentation.components.CategoryChips(
        selectedCategory = selectedCategory,
        onCategorySelected = onCategorySelected,
        modifier = modifier,
        categories = categories
    )
}
