package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard choice chips container for single selection in sheet steps (Design Guide 4).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    items: List<T>,
    selectedItem: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String = { it.toString() }
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            val border = if (isSelected) {
                BorderStroke(1.dp, FinanceTheme.colors.brand)
            } else {
                BorderStroke(1.dp, FinanceTheme.colors.outline)
            }
            val bgColor = if (isSelected) {
                FinanceTheme.colors.brand.copy(alpha = 0.12f)
            } else {
                FinanceTheme.colors.surface
            }
            val textColor = if (isSelected) {
                FinanceTheme.colors.brand
            } else {
                FinanceTheme.colors.onSurface
            }

            Surface(
                shape = FinanceTheme.shapes.chip,
                border = border,
                color = bgColor,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(item) })
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.s),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelProvider(item),
                        style = FinanceTheme.typography.labelMedium,
                        color = textColor
                    )
                }
            }
        }
    }
}

/**
 * Multi-selection choice chips container for companions / tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> MultiChoiceChips(
    items: List<T>,
    selectedItems: Set<T>,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String = { it.toString() }
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
    ) {
        items.forEach { item ->
            val isSelected = item in selectedItems
            val border = if (isSelected) {
                BorderStroke(1.dp, FinanceTheme.colors.brand)
            } else {
                BorderStroke(1.dp, FinanceTheme.colors.outline)
            }
            val bgColor = if (isSelected) {
                FinanceTheme.colors.brand.copy(alpha = 0.12f)
            } else {
                FinanceTheme.colors.surface
            }
            val textColor = if (isSelected) {
                FinanceTheme.colors.brand
            } else {
                FinanceTheme.colors.onSurface
            }

            Surface(
                shape = FinanceTheme.shapes.chip,
                border = border,
                color = bgColor,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Checkbox, onClick = { onToggle(item) })
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.s),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelProvider(item),
                        style = FinanceTheme.typography.labelMedium,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun ChoiceChipsPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        ChoiceChips(
            items = listOf("Just Me", "Split Equally", "Custom Split"),
            selectedItem = "Split Equally",
            onSelect = {}
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ChoiceChipsPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        ChoiceChips(
            items = listOf("Account 1", "Account 2", "Cash"),
            selectedItem = "Account 1",
            onSelect = {}
        )
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun ChoiceChipsPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        ChoiceChips(
            items = listOf("Food & Drink", "Travel", "Groceries"),
            selectedItem = "Food & Drink",
            onSelect = {}
        )
    }
}
