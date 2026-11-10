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
 * Standard transaction entry row (Design Guide 4).
 * Displays description, date, account badge, amount, and settlement status chip.
 */
@Composable
fun TransactionRow(
    description: String,
    dateText: String,
    account: String,
    amount: Double,
    status: String,
    modifier: Modifier = Modifier,
    isExpense: Boolean = true,
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
                text = description,
                style = FinanceTheme.typography.bodyMedium,
                color = FinanceTheme.colors.onSurface
            )
            Text(
                text = "$dateText • $account",
                style = FinanceTheme.typography.labelMedium,
                color = FinanceTheme.colors.onSurfaceVariant
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AmountText(
                amount = amount,
                tone = if (isExpense) AmountTone.MONEY_OUT else AmountTone.MONEY_IN,
                style = FinanceTheme.typography.bodyMedium,
                includeDecimals = true,
                prefix = if (isExpense) "-" else "+"
            )
            StatusChip(status = status)
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun TransactionRowPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        TransactionRow(
            description = "Blue Tokai Coffee",
            dateText = "10 Nov",
            account = "SBI",
            amount = 240.0,
            status = "Personal"
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun TransactionRowPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        TransactionRow(
            description = "Dinner at Toit",
            dateText = "09 Nov",
            account = "Canara",
            amount = 1850.0,
            status = "Pending"
        )
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun TransactionRowPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        TransactionRow(
            description = "Uber to Airport",
            dateText = "08 Nov",
            account = "SBI",
            amount = 640.0,
            status = "Settled"
        )
    }
}

