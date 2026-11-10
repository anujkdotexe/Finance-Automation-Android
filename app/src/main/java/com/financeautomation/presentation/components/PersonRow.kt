package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * Renders an individual companion with balance owed and oldest transaction date (Design Guide 4).
 */
@Composable
fun PersonRow(
    name: String,
    amount: Double,
    oldestDateText: String? = null,
    modifier: Modifier = Modifier,
    isOwedToMe: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            )
            .padding(vertical = FinanceTheme.spacing.s, horizontal = FinanceTheme.spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = name,
                style = FinanceTheme.typography.bodyMedium,
                color = FinanceTheme.colors.onSurface
            )
            if (!oldestDateText.isNullOrBlank()) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.format_since_date, oldestDateText),
                    style = FinanceTheme.typography.labelMedium,
                    color = FinanceTheme.colors.onSurfaceVariant
                )
            }
        }

        AmountText(
            amount = amount,
            tone = if (isOwedToMe) AmountTone.MONEY_IN else AmountTone.MONEY_OWED,
            style = FinanceTheme.typography.bodyMedium,
            includeDecimals = false
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun PersonRowPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        PersonRow(name = "Rohan Sharma", amount = 1450.0, oldestDateText = "15 Oct", isOwedToMe = true)
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun PersonRowPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        PersonRow(name = "Aman Gupta", amount = 650.0, oldestDateText = "02 Nov", isOwedToMe = false)
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun PersonRowPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        PersonRow(name = "Neha Verma", amount = 2300.0, oldestDateText = "28 Sep", isOwedToMe = true)
    }
}

