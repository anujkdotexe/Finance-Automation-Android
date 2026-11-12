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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard transaction entry row.
 * Line 1: merchant and amount.
 * Line 2: category, account and time with status chip.
 * Optional Line 3: "My share X, Owed Y" for split rows.
 */
@Composable
fun TransactionRow(
    merchant: String,
    amount: Double,
    modifier: Modifier = Modifier,
    category: String = "",
    account: String = "",
    time: String = "",
    status: String = "",
    splitText: String? = null,
    isExpense: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val subtitleParts = listOfNotNull(
        category.takeIf { it.isNotBlank() },
        account.takeIf { it.isNotBlank() },
        time.takeIf { it.isNotBlank() }
    )
    val subtitle = subtitleParts.joinToString(" • ")

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
            modifier = Modifier.weight(1f).padding(end = FinanceTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Line 1: Merchant
            Text(
                text = merchant,
                style = FinanceTheme.typography.bodyMedium,
                color = FinanceTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Line 2: Category, account, time
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = FinanceTheme.typography.bodySmall,
                    color = FinanceTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Optional Line 3: Split details
            if (!splitText.isNullOrBlank()) {
                Text(
                    text = splitText,
                    style = FinanceTheme.typography.numeric,
                    color = FinanceTheme.colors.moneyOwed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AmountText(
                amount = amount,
                tone = if (isExpense) AmountTone.DEFAULT else AmountTone.MONEY_IN,
                style = FinanceTheme.typography.kpiMedium,
                prefix = if (isExpense) "" else "+"
            )
            if (status.isNotBlank()) {
                StatusChip(status = status)
            }
        }
    }
}

/**
 * Backward compatibility overload.
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
    TransactionRow(
        merchant = description,
        amount = amount,
        modifier = modifier,
        category = "",
        account = account,
        time = dateText,
        status = status,
        isExpense = isExpense,
        onClick = onClick
    )
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun TransactionRowPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        TransactionRow(
            merchant = "Blue Tokai Coffee",
            amount = 340.0,
            category = "Food & Drink",
            account = "HDFC ••4921",
            time = "Today, 2:15 PM",
            status = "Needs review"
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun TransactionRowPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        TransactionRow(
            merchant = "Dinner with team",
            amount = 2400.0,
            category = "Food & Drink",
            account = "ICICI ••1028",
            time = "Yesterday",
            status = "Pending",
            splitText = "My share 600, Owed 1,800"
        )
    }
}

@Preview(name = "Font Scale 1.3x", fontScale = 1.3f, showBackground = true)
@Composable
private fun TransactionRowPreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        TransactionRow(
            merchant = "Shell Petrol",
            amount = 1200.0,
            category = "Transit",
            account = "ICICI ••1028",
            time = "24 Nov",
            status = "Synced"
        )
    }
}
