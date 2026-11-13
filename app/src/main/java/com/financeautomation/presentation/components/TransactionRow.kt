package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard transaction entry row (56dp minimum height).
 * Layout:
 * - Left: Merchant name (16sp), bottom row with account chip and "time · category"
 * - Right: Mono amount (15sp Medium), optional split subtext
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
    val metaText = when {
        time.isNotBlank() && category.isNotBlank() -> "$time · $category"
        time.isNotBlank() -> time
        category.isNotBlank() -> category
        else -> ""
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FinanceTheme.sizes.minRowHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            )
            .padding(vertical = FinanceTheme.spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = FinanceTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xxs)
        ) {
            Text(
                text = merchant,
                style = FinanceTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                if (account.isNotBlank()) {
                    TransactionAccountChip(text = account)
                }

                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xxs)
        ) {
            Text(
                text = formatRupeesAuto(amount),
                style = FinanceTheme.typography.numeric.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!splitText.isNullOrBlank()) {
                Text(
                    text = splitText,
                    style = FinanceTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (status.isNotBlank()) {
                StatusChip(status = status)
            }
        }
    }
}

/**
 * Account chip for transaction ledger rows.
 * Conforms to frozen specifications:
 * - Left aligned with merchant text
 * - Uniform horizontal padding and minimum width
 * - Low height to keep row at 56dp minimum without extra vertical space
 */
@Composable
fun TransactionAccountChip(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = FinanceShapes.appChip,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .defaultMinSize(
                minWidth = FinanceTheme.sizes.accountChipMinWidth,
                minHeight = FinanceTheme.sizes.accountChipHeight
            )
            .height(FinanceTheme.sizes.accountChipHeight)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = FinanceTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Preview(name = "TransactionRow Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun TransactionRowPreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(FinanceTheme.spacing.l)) {
            TransactionRow(
                merchant = "Swiggy",
                amount = 600.0,
                category = "Food",
                account = "HDFC",
                time = "02:15 PM",
                splitText = "Split (₹200)",
                onClick = {}
            )
        }
    }
}

@Preview(name = "TransactionRow Font 1.3 - Light", showBackground = true, fontScale = 1.3f)
@Composable
private fun TransactionRowPreviewLargeFontLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(FinanceTheme.spacing.l)) {
            TransactionRow(
                merchant = "Swiggy",
                amount = 600.0,
                category = "Food",
                account = "HDFC",
                time = "02:15 PM",
                splitText = "Split (₹200)",
                onClick = {}
            )
        }
    }
}

@Preview(name = "TransactionRow Normal Font - Dark", showBackground = true, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TransactionRowPreviewNormalDark() {
    FinanceAutomationTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(FinanceTheme.spacing.l)) {
            TransactionRow(
                merchant = "Blue Tokai Coffee",
                amount = 340.0,
                category = "Food & Drink",
                account = "ICICI",
                time = "11:30 AM",
                onClick = {}
            )
        }
    }
}
