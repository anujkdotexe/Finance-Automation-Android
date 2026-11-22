package com.financeautomation.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.R
import com.financeautomation.domain.usecase.BudgetProgress
import com.financeautomation.domain.usecase.BudgetState
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import kotlin.math.roundToInt

/**
 * Thin progress bar for a category budget. The fill follows the percent used (capped at a full bar), and the colour
 * follows the state: primary for NORMAL, the amber money tone for WARNING and the error container for OVER (a soft
 * tone, never bright red). The bar carries a text description for screen readers, so colour is never the only signal.
 */
@Composable
fun BudgetBar(
    categoryName: String,
    percentUsed: Double,
    state: BudgetState,
    modifier: Modifier = Modifier
) {
    val percent = percentUsed.roundToInt().coerceAtLeast(0)
    val fraction = (percentUsed / 100.0).coerceIn(0.0, 1.0).toFloat()
    val description = if (state == BudgetState.OVER) {
        stringResource(R.string.budget_bar_cd_over, categoryName, percent)
    } else {
        stringResource(R.string.budget_bar_cd_used, categoryName, percent)
    }
    val fill: Color = when (state) {
        BudgetState.NORMAL -> MaterialTheme.colorScheme.primary
        BudgetState.WARNING -> FinanceTheme.colors.moneyOwed
        BudgetState.OVER -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FinanceTheme.sizes.proportionBarHeight)
            .clip(FinanceShapes.chip)
            .background(FinanceTheme.colors.surfaceContainerHigh)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(FinanceShapes.chip)
                .background(fill)
        )
    }
}

/** Convenience overload taking the result of the budget progress calculation. */
@Composable
fun BudgetBar(progress: BudgetProgress, modifier: Modifier = Modifier) {
    BudgetBar(
        categoryName = progress.category,
        percentUsed = progress.percentUsed,
        state = progress.state,
        modifier = modifier
    )
}

@Preview(name = "Budget bars light", showBackground = true)
@Composable
private fun BudgetBarPreviewLight() {
    FinanceAutomationTheme(themeMode = com.financeautomation.domain.model.ThemeMode.LIGHT) {
        BudgetBarPreviewContent()
    }
}

@Preview(name = "Budget bars dark", showBackground = true, backgroundColor = 0xFF0F1416)
@Composable
private fun BudgetBarPreviewDark() {
    FinanceAutomationTheme(themeMode = com.financeautomation.domain.model.ThemeMode.DARK) {
        BudgetBarPreviewContent()
    }
}

@Composable
private fun BudgetBarPreviewContent() {
    Column(
        modifier = Modifier
            .padding(FinanceTheme.spacing.l)
            .widthIn(max = FinanceTheme.sizes.maxSheetHeight),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        BudgetBar("Food", 45.0, BudgetState.NORMAL)
        BudgetBar("Transit", 92.0, BudgetState.WARNING)
        BudgetBar("Shopping", 130.0, BudgetState.OVER)
    }
}
