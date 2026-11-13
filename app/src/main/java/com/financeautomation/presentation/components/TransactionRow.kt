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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.core.utils.stripVoidPrefix
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
    val isVoid = status.equals("VOID", ignoreCase = true)
    val cleanMerchant = stripVoidPrefix(merchant)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                Text(
                    text = cleanMerchant,
                    style = if (isVoid) {
                        FinanceTheme.typography.titleMedium.copy(textDecoration = TextDecoration.LineThrough)
                    } else {
                        FinanceTheme.typography.titleMedium
                    },
                    color = if (isVoid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (isVoid) {
                    StatusChip(status = "VOID")
                }
            }

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
                color = if (isVoid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )

            if (!splitText.isNullOrBlank()) {
                Text(
                    text = splitText,
                    style = FinanceTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            } else if (status.isNotBlank() && !isVoid) {
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

/**
 * Standard income / refund / settlement entry row for Transactions ledger.
 * Layout:
 * - Left: Inflow icon (CallReceived in positive color), source as title, type chip, account chip, time
 * - Right: Positive mono amount (+₹Amount), optional VOID status chip
 */
@Composable
fun InflowRow(
    source: String,
    amount: Double,
    inflowType: String,
    account: String,
    time: String = "",
    status: String = "",
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isVoid = status.equals("VOID", ignoreCase = true)
    val rawDisplayTitle = source.ifBlank {
        when (inflowType.uppercase()) {
            "FRIEND_SETTLEMENT" -> "Friend settlement"
            "SELF_TRANSFER" -> "Self transfer"
            "REFUND" -> "Refund"
            else -> "Income"
        }
    }
    val cleanTitle = stripVoidPrefix(rawDisplayTitle)
    val typeLabel = when (inflowType.uppercase()) {
        "FRIEND_SETTLEMENT" -> "Settlement"
        "SELF_TRANSFER" -> "Transfer"
        "REFUND" -> "Refund"
        else -> "Income"
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
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = FinanceTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.CallReceived,
                contentDescription = stringResource(R.string.cd_income),
                tint = if (isVoid) MaterialTheme.colorScheme.onSurfaceVariant else FinanceTheme.colors.moneyIn,
                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
            )

            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xxs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Text(
                        text = cleanTitle,
                        style = if (isVoid) {
                            FinanceTheme.typography.titleMedium.copy(textDecoration = TextDecoration.LineThrough)
                        } else {
                            FinanceTheme.typography.titleMedium
                        },
                        color = if (isVoid) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (isVoid) {
                        StatusChip(status = "VOID")
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    TransactionAccountChip(text = typeLabel)

                    if (account.isNotBlank()) {
                        TransactionAccountChip(text = account)
                    }

                    if (time.isNotBlank()) {
                        Text(
                            text = time,
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xxs)
        ) {
            Text(
                text = "+${formatRupeesAuto(amount)}",
                style = FinanceTheme.typography.numeric.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (isVoid) MaterialTheme.colorScheme.onSurfaceVariant else FinanceTheme.colors.moneyIn
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
