package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.core.utils.formatRupeesCompact
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import kotlin.math.abs

/**
 * Auto-shrinking amount text composable for KPI cards.
 * Displays formatted amount and gracefully reduces font size down to 16sp floor
 * if width overflows under large font scales before falling back to compact representation.
 */
@Composable
fun ShrinkToFitAmount(
    amount: Double,
    isLargeAmount: Boolean,
    showExact: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    floorFontSize: TextUnit = 16.sp
) {
    val baseStyle = if (isLargeAmount) FinanceTheme.typography.amountHero else FinanceTheme.typography.amountMedium
    val exactText = formatRupeesAuto(amount)
    val compactText = formatRupeesCompact(amount)

    // Primary display text according to threshold rules:
    // Full-width: exact up to 7 digits (< 10,000,000), compact beyond
    // Half-width: exact up to 5 digits (< 100,000), compact beyond
    val primaryText = if (showExact) {
        exactText
    } else if (isLargeAmount) {
        if (abs(amount) < 10000000.0) exactText else compactText
    } else {
        if (abs(amount) < 100000.0) exactText else compactText
    }

    var fontSize by remember(primaryText) { mutableStateOf(baseStyle.fontSize) }
    var useFallbackCompact by remember(primaryText) { mutableStateOf(false) }

    val currentText = if (useFallbackCompact) compactText else primaryText

    Text(
        text = currentText,
        style = baseStyle.copy(fontSize = fontSize),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.didOverflowWidth) {
                if (fontSize.value > floorFontSize.value) {
                    fontSize = (fontSize.value - 1f).sp
                } else if (!useFallbackCompact && primaryText != compactText) {
                    useFallbackCompact = true
                    fontSize = baseStyle.fontSize
                }
            }
        }
    )
}

/**
 * KPI card for dashboard metrics row (Y1 final).
 * Design: 16dp radius, surfaceContainerLow fill, no border.
 * Supports full-width hero card (large amount style) and half-width compact cards.
 */
@Composable
fun KpiCard(
    label: String,
    amount: Double,
    modifier: Modifier = Modifier,
    isLargeAmount: Boolean = false,
    line1Text: String? = null,
    line1IsPrimary: Boolean = false,
    line2Text: String? = null,
    onClick: (() -> Unit)? = null
) {
    var showExact by remember { mutableStateOf(false) }

    Card(
        shape = FinanceShapes.appCard,
        colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = FinanceTheme.spacing.none),
        border = null,
        modifier = modifier
            .defaultMinSize(minHeight = FinanceTheme.sizes.kpiCardMinHeight)
            .clickable(
                role = Role.Button,
                onClick = {
                    if (onClick != null) {
                        onClick()
                    } else {
                        showExact = !showExact
                    }
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = label,
                style = FinanceTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            ShrinkToFitAmount(
                amount = amount,
                isLargeAmount = isLargeAmount,
                showExact = showExact,
                modifier = Modifier.padding(top = FinanceTheme.spacing.xs, bottom = FinanceTheme.spacing.xs)
            )

            if (isLargeAmount) {
                if (!line1Text.isNullOrBlank() || !line2Text.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xxs)) {
                        if (!line1Text.isNullOrBlank()) {
                            Text(
                                text = line1Text,
                                style = FinanceTheme.typography.labelSmall,
                                color = if (line1IsPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!line2Text.isNullOrBlank()) {
                            Text(
                                text = line2Text,
                                style = FinanceTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // Half-width cards: sub-line directly below amount.
                // Renders line1Text if provided (e.g. "0% of income" for Savings), or an empty line for Income,
                // ensuring both cards have identical structure, amount position, and height.
                Text(
                    text = line1Text ?: " ",
                    style = FinanceTheme.typography.labelSmall,
                    color = if (line1IsPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Preview(name = "KPI Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun KpiCardPreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        KpiCard(
            label = "Living burn",
            amount = 28549.0,
            line1Text = "Down 12% vs last month",
            line1IsPrimary = true,
            line2Text = "₹951.63 / day"
        )
    }
}

@Preview(name = "KPI Font 1.3 - Light", showBackground = true, fontScale = 1.3f)
@Composable
private fun KpiCardPreviewLargeFontLight() {
    FinanceAutomationTheme(darkTheme = false) {
        KpiCard(
            label = "Living burn",
            amount = 28549.0,
            line1Text = "Down 12% vs last month",
            line1IsPrimary = true,
            line2Text = "₹951.63 / day"
        )
    }
}

@Preview(name = "KPI Normal Font - Dark", showBackground = true, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun KpiCardPreviewNormalDark() {
    FinanceAutomationTheme(darkTheme = true) {
        KpiCard(
            label = "Savings",
            amount = 42000.0,
            line1Text = "36.5% of income"
        )
    }
}
