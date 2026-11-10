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
    caption: String = "",
    modifier: Modifier = Modifier,
    tone: AmountTone = AmountTone.DEFAULT,
    includeDecimals: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Card(
        shape = FinanceTheme.shapes.large,
        border = BorderStroke(FinanceTheme.sizes.strokeThin, FinanceTheme.colors.outline),
        colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surface),
        modifier = modifier
            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
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
            label = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.dashboard_hero_burn_title),
            amount = 14250.0,
            tone = AmountTone.MONEY_OUT
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun KpiCardPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        KpiCard(
            label = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.dashboard_owed_to_me),
            amount = 3200.0,
            tone = AmountTone.MONEY_IN
        )
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun KpiCardPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        KpiCard(
            label = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.dashboard_i_owe),
            amount = 1200.0,
            tone = AmountTone.MONEY_OWED
        )
    }
}

