package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard KPI summary card displaying label, formatted figure, and context caption (Design Guide 4).
 * Single tap target when navigation is attached.
 */
@Composable
fun KpiCard(
    label: String,
    amount: Double,
    caption: String,
    modifier: Modifier = Modifier,
    tone: AmountTone = AmountTone.DEFAULT,
    includeDecimals: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Card(
        shape = FinanceTheme.shapes.large,
        border = BorderStroke(1.dp, FinanceTheme.colors.outline),
        colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surface),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
        ) {
            Text(
                text = label,
                style = FinanceTheme.typography.labelMedium,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            AmountText(
                amount = amount,
                tone = tone,
                style = FinanceTheme.typography.amountLarge,
                includeDecimals = includeDecimals
            )

            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = FinanceTheme.typography.labelMedium,
                    color = FinanceTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun KpiCardPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        KpiCard(
            label = "Living burn this month",
            amount = 14250.0,
            caption = "Daily average: ₹475 | Last month: ₹21,300",
            tone = AmountTone.MONEY_OUT
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun KpiCardPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        KpiCard(
            label = "Owed to me",
            amount = 3200.0,
            caption = "Rohan, Aman, Neha",
            tone = AmountTone.MONEY_IN
        )
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun KpiCardPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        KpiCard(
            label = "I owe",
            amount = 1200.0,
            caption = "Siddharth",
            tone = AmountTone.MONEY_OWED
        )
    }
}

