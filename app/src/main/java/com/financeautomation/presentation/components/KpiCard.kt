package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * KPI card for dashboard metrics row.
 * Design: 16dp radius, surfaceContainerLow fill, no border, equal-weight layout.
 */
@Composable
fun KpiCard(
    label: String,
    amount: Double,
    modifier: Modifier = Modifier,
    line1Text: String? = null,
    line1IsPrimary: Boolean = false,
    line2Text: String? = null,
    onClick: (() -> Unit)? = null
) {
    Card(
        shape = FinanceShapes.appCard,
        colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = FinanceTheme.spacing.none),
        border = null,
        modifier = modifier
            .defaultMinSize(minHeight = FinanceTheme.sizes.kpiCardMinHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = FinanceTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = formatRupeesAuto(amount),
                style = FinanceTheme.typography.amountMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = FinanceTheme.spacing.xs)
            )

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
            line2Text = "₹951.63 / day",
            modifier = Modifier.width(104.dp)
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
            line2Text = "₹951.63 / day",
            modifier = Modifier.width(104.dp)
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
            line1Text = "36.5% of income",
            modifier = Modifier.width(104.dp)
        )
    }
}
