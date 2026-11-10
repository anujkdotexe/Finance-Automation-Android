package com.financeautomation.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.presentation.components.AmountText
import com.financeautomation.presentation.components.AmountTone
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.DebtSummary

/**
 * Hero living-burn card showing total monthly burn, daily average, and last month comparison (Design Guide 5.1).
 */
@Composable
fun HeroBurnCard(
    currentBurn: Double,
    dailyAverage: Double,
    lastMonthBurn: Double
) {
    Card(
        shape = FinanceTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, FinanceTheme.colors.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.l)
        ) {
            Text(
                text = "Living burn this month",
                style = FinanceTheme.typography.labelMedium,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            AmountText(
                amount = currentBurn,
                tone = AmountTone.MONEY_OUT,
                style = FinanceTheme.typography.amountHero
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

            Text(
                text = "Daily avg ${formatRupees(dailyAverage)} · Last month ${formatRupees(lastMonthBurn)}",
                style = FinanceTheme.typography.labelMedium,
                color = FinanceTheme.colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Side-by-side card for Owed to me and I owe summaries with top 3 friends (Design Guide 5.1).
 */
@Composable
fun OwedSummaryCard(
    title: String,
    amount: Double,
    tone: AmountTone,
    topFriends: List<DebtSummary>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = FinanceTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, FinanceTheme.colors.outline),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.m)
        ) {
            Text(
                text = title,
                style = FinanceTheme.typography.labelMedium,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            AmountText(
                amount = amount,
                tone = tone,
                style = FinanceTheme.typography.amountLarge
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            if (topFriends.isNotEmpty()) {
                topFriends.take(3).forEach { friend ->
                    Text(
                        text = "${friend.name} ${formatRupees(friend.amount)}",
                        style = FinanceTheme.typography.labelMedium,
                        color = FinanceTheme.colors.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "None pending",
                    style = FinanceTheme.typography.labelMedium,
                    color = FinanceTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Needs review banner shown when unconfirmed alerts exist (Design Guide 5.1).
 */
@Composable
fun NeedsReviewBanner(
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        color = FinanceTheme.colors.moneyOwed.copy(alpha = 0.12f),
        shape = FinanceTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = FinanceTheme.colors.moneyOwed,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "$count transactions need review",
                    style = FinanceTheme.typography.bodyMedium,
                    color = FinanceTheme.colors.moneyOwed
                )
            }

            Text(
                text = "Review",
                style = FinanceTheme.typography.titleMedium,
                color = FinanceTheme.colors.moneyOwed
            )
        }
    }
}

