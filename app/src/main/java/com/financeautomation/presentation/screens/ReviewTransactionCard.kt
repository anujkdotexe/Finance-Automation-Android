package com.financeautomation.presentation.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.domain.model.Transaction
import com.financeautomation.presentation.components.AppCard
import com.financeautomation.presentation.components.AppChip
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.FilledButton
import com.financeautomation.presentation.components.OutlinedButton
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interactive card displaying an unreviewed transaction.
 * Layout:
 * - Merchant, account chip, time, mono amount
 * - ButtonPair: OutlinedButton "Edit" on left, FilledButton "Confirm" on right (equal width, equal height 48dp)
 * - Duplicate warning banner if flagged
 * - No Void button on the card (Void stays in Transaction detail sheet)
 * - No Batch ID, no auto-confirm, no "Auto-Categorized"
 */
@Composable
fun ReviewTransactionCard(
    transaction: Transaction,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = FinanceTheme.colors.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            // Duplicate Warning Banner if applicable
            if (transaction.isPossibleDuplicate) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = FinanceShapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = stringResource(R.string.cd_warning),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                        Text(
                            text = stringResource(R.string.review_duplicate_warning),
                            style = FinanceTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Top Row: Merchant + chips on left, Mono Amount on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Text(
                        text = transaction.merchant.ifBlank { transaction.category },
                        style = FinanceTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        if (transaction.account.isNotBlank()) {
                            AppChip(
                                text = transaction.account,
                                selected = false,
                                onClick = {}
                            )
                        }

                        val dateStr = formatTransactionTime(transaction.timestamp)
                        Text(
                            text = dateStr,
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = formatRupeesAuto(transaction.totalAmount),
                    style = FinanceTheme.typography.amountMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = FinanceTheme.spacing.m)
                )
            }

            // Button Pair: Outlined "Edit" on left, Filled "Confirm" on right (equal width, 48dp height)
            ButtonPair(
                outlinedText = stringResource(R.string.action_edit),
                onOutlinedClick = onEdit,
                filledText = stringResource(R.string.action_confirm),
                onFilledClick = onConfirm
            )
        }
    }
}

/**
 * Subscription due card shown at top of Needs Review list when a cycle is in NEEDS_REVIEW state:
 * - "Did you pay <name>?"
 * - "<amount or Amount varies>, due <date>"
 * - Three buttons: Yes (Filled), Not yet (Outlined), Skip this time (Outlined)
 */
@Composable
fun SubscriptionDueCard(
    subscriptionName: String,
    amount: Double?,
    dueFormatted: String,
    onYes: () -> Unit,
    onNotYet: () -> Unit,
    onSkipThisTime: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = FinanceTheme.colors.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xxs)) {
                Text(
                    text = stringResource(R.string.subscription_review_title, subscriptionName),
                    style = FinanceTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val amountText = if (amount != null && amount > 0.0) formatRupeesAuto(amount) else stringResource(R.string.subscription_amount_varies)
                Text(
                    text = stringResource(R.string.subscription_review_subtitle, amountText, dueFormatted),
                    style = FinanceTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledButton(
                    text = stringResource(R.string.action_yes),
                    onClick = onYes,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    text = stringResource(R.string.action_not_yet),
                    onClick = onNotYet,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    text = stringResource(R.string.action_skip_this_time),
                    onClick = onSkipThisTime,
                    modifier = Modifier.weight(1.3f)
                )
            }
        }
    }
}

private fun formatTransactionTime(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Date()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date) ==
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)

    return if (sameDay) {
        "Today, " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(date)
    }
}

@Preview(name = "Review Card Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun ReviewTransactionCardPreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        ReviewTransactionCard(
            transaction = Transaction(
                id = "1",
                timestamp = System.currentTimeMillis(),
                dateSerial = 45600,
                timeSerial = 0.5,
                merchant = "Swiggy",
                totalAmount = 600.0,
                category = "Food & Drink",
                account = "HDFC ••4921",
                myShare = 600.0,
                owedToMe = 0.0
            ),
            onConfirm = {},
            onEdit = {}
        )
    }
}

@Preview(name = "Review Card Font 1.3 - Light", showBackground = true, fontScale = 1.3f)
@Composable
private fun ReviewTransactionCardPreviewLargeFontLight() {
    FinanceAutomationTheme(darkTheme = false) {
        ReviewTransactionCard(
            transaction = Transaction(
                id = "1",
                timestamp = System.currentTimeMillis(),
                dateSerial = 45600,
                timeSerial = 0.5,
                merchant = "Swiggy",
                totalAmount = 600.0,
                category = "Food & Drink",
                account = "HDFC ••4921",
                myShare = 600.0,
                owedToMe = 0.0
            ),
            onConfirm = {},
            onEdit = {}
        )
    }
}

@Preview(name = "Subscription Due Card - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun SubscriptionDueCardPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        SubscriptionDueCard(
            subscriptionName = "Netflix",
            amount = 649.0,
            dueFormatted = "today",
            onYes = {},
            onNotYet = {},
            onSkipThisTime = {}
        )
    }
}
