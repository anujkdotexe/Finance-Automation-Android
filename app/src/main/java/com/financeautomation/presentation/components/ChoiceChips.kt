package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard AppChip: 32dp high, 8dp radius, same size for category, account, filter and friend chips.
 * Selected = tonal primary fill (primaryContainer, onPrimaryContainer).
 * Unselected = outlined neutral (outlineVariant, transparent background, onSurface).
 */
@Composable
fun AppChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }

    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val border = if (selected) {
        null
    } else {
        BorderStroke(FinanceTheme.sizes.strokeThin, MaterialTheme.colorScheme.outlineVariant)
    }

    Surface(
        shape = FinanceShapes.appChip,
        border = border,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .height(FinanceTheme.sizes.chipHeight)
            .clip(FinanceShapes.appChip)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
        ) {
            leadingIcon?.invoke()
            Text(
                text = text,
                style = if (selected) FinanceTheme.typography.labelMedium else FinanceTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * SegmentedChoice: 40dp high container for mutually exclusive segmented options.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String = { it.toString() }
) {
    Surface(
        shape = FinanceShapes.pill,
        color = FinanceTheme.colors.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .height(FinanceTheme.sizes.segmentedChoiceHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                val bgModifier = if (isSelected) {
                    Modifier
                        .clip(FinanceShapes.pill)
                        .background(MaterialTheme.colorScheme.surface)
                } else {
                    Modifier
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(FinanceTheme.sizes.chipHeight)
                        .then(bgModifier)
                        .clickable(
                            role = Role.RadioButton,
                            onClick = { onOptionSelected(option) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelProvider(option),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Standard choice chips container for single selection in sheet steps.
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
            AppChip(
                text = labelProvider(item),
                selected = isSelected,
                onClick = { onSelect(item) }
            )
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
            AppChip(
                text = labelProvider(item),
                selected = isSelected,
                onClick = { onToggle(item) }
            )
        }
    }
}

@Preview(name = "AppChip Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun AppChipPreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Row(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            AppChip(text = "All", selected = true, onClick = {})
            AppChip(text = "UPI", selected = false, onClick = {})
            AppChip(text = "Needs Review", selected = false, onClick = {})
        }
    }
}

@Preview(name = "AppChip Large Font 1.3 - Light", showBackground = true, fontScale = 1.3f)
@Composable
private fun AppChipPreviewLargeFontLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Row(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            AppChip(text = "All", selected = true, onClick = {})
            AppChip(text = "UPI", selected = false, onClick = {})
            AppChip(text = "Needs Review", selected = false, onClick = {})
        }
    }
}

@Preview(name = "SegmentedChoice Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun SegmentedChoicePreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(FinanceTheme.spacing.l)) {
            SegmentedChoice(
                options = listOf("Expense", "Income", "Transfer"),
                selectedOption = "Expense",
                onOptionSelected = {}
            )
        }
    }
}

@Preview(name = "SegmentedChoice Large Font 1.3 - Light", showBackground = true, fontScale = 1.3f)
@Composable
private fun SegmentedChoicePreviewLargeFontLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(FinanceTheme.spacing.l)) {
            SegmentedChoice(
                options = listOf("Expense", "Income", "Transfer"),
                selectedOption = "Expense",
                onOptionSelected = {}
            )
        }
    }
}

@Preview(name = "AppChip Normal Font - Dark", showBackground = true, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppChipPreviewNormalDark() {
    FinanceAutomationTheme(darkTheme = true) {
        Row(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            AppChip(text = "All", selected = true, onClick = {})
            AppChip(text = "Card", selected = false, onClick = {})
            AppChip(text = "Cash", selected = false, onClick = {})
        }
    }
}

@Preview(name = "AppChip Large Font 1.3 - Dark", showBackground = true, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppChipPreviewLargeFontDark() {
    FinanceAutomationTheme(darkTheme = true) {
        Row(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            AppChip(text = "All", selected = true, onClick = {})
            AppChip(text = "Card", selected = false, onClick = {})
            AppChip(text = "Cash", selected = false, onClick = {})
        }
    }
}
