package com.financeautomation.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.presentation.components.AmountText
import com.financeautomation.presentation.components.AmountTone
import com.financeautomation.presentation.components.PersonDebtRow
import com.financeautomation.presentation.components.TonalCard
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.DebtSummary
import com.financeautomation.presentation.viewmodel.QueuedItem
import kotlin.math.abs

/**
 * Hero living-burn card showing total monthly burn, daily average,
 * vs last month pace delta, vs same day last month, and On track status chip.
 */
@Composable
fun HeroBurnCard(
    currentBurn: Double,
    dailyAverage: Double,
    lastMonthSameDayBurn: Double,
    paceDeltaPercent: Double? = null,
    onTrack: Boolean = true,
    modifier: Modifier = Modifier
) {
    val hasLastMonthData = lastMonthSameDayBurn > 0.0 || paceDeltaPercent != null

    TonalCard(modifier = modifier) {
        // Top Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.dashboard_hero_burn_title).uppercase(),
                style = FinanceTheme.typography.labelSmall.copy(
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (onTrack && hasLastMonthData) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = stringResource(R.string.dashboard_on_track).uppercase(),
                        style = FinanceTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        // Hero Figure
        AmountText(
            amount = currentBurn,
            tone = AmountTone.DEFAULT,
            style = FinanceTheme.typography.kpiLarge
        )

        Text(
            text = stringResource(R.string.dashboard_excludes_savings),
            style = FinanceTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
        HorizontalDivider(color = FinanceTheme.colors.surfaceContainer)
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        // Comparisons & Run Rates: Rebuilt as two equal-weight columns separated by 12dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left column: Daily average
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = stringResource(R.string.dashboard_daily_average_label),
                    style = FinanceTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatRupeesAuto(dailyAverage)} / day",
                    style = FinanceTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right column: Same day last month
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = stringResource(R.string.dashboard_same_day_last_month_label),
                    style = FinanceTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val rightValueText = if (hasLastMonthData) {
                    formatRupeesAuto(lastMonthSameDayBurn)
                } else {
                    stringResource(R.string.dashboard_no_data)
                }
                Text(
                    text = rightValueText,
                    style = FinanceTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                    color = if (hasLastMonthData) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasLastMonthData) {
                    if (paceDeltaPercent != null) {
                        val isDown = paceDeltaPercent <= 0.0
                        val deltaAbs = abs(paceDeltaPercent)
                        val deltaFormatted = String.format(java.util.Locale.US, "%.1f", deltaAbs)
                        val deltaText = if (isDown) "Down $deltaFormatted%" else "Up $deltaFormatted%"
                        val deltaColor = if (isDown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        val deltaIcon = if (isDown) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = deltaIcon,
                                contentDescription = null,
                                tint = deltaColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = deltaText,
                                style = FinanceTheme.typography.numeric.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                ),
                                color = deltaColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (onTrack) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = stringResource(R.string.dashboard_on_track).uppercase(),
                                style = FinanceTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 9.sp
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Side-by-side cards for Owed to me and I owe summaries with per-person rows.
 */
@Composable
fun OwedSummarySection(
    owedToMe: Double,
    topOwedToMe: List<DebtSummary>,
    iOwePeople: Double,
    topIOwe: List<DebtSummary>,
    onPersonClick: (personName: String, owedToMe: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        // Owed to me card
        TonalCard(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dashboard_owed_to_me).uppercase(),
                    style = FinanceTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.CallReceived,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            AmountText(
                amount = owedToMe,
                tone = AmountTone.MONEY_IN,
                style = FinanceTheme.typography.kpiMedium
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            if (topOwedToMe.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    topOwedToMe.take(3).forEach { friend ->
                        PersonDebtRow(
                            name = friend.name,
                            amount = friend.amount,
                            isOwedToMe = true,
                            onClick = { onPersonClick(friend.name, true) }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FinanceShapes.small)
                        .background(FinanceTheme.colors.surfaceContainerLow)
                        .padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.s),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_balances_clear),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // I owe card
        TonalCard(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dashboard_i_owe).uppercase(),
                    style = FinanceTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.CallMade,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            AmountText(
                amount = iOwePeople,
                tone = AmountTone.MONEY_OUT,
                style = FinanceTheme.typography.kpiMedium
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            if (topIOwe.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    topIOwe.take(3).forEach { friend ->
                        PersonDebtRow(
                            name = friend.name,
                            amount = friend.amount,
                            isOwedToMe = false,
                            onClick = { onPersonClick(friend.name, false) }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FinanceShapes.small)
                        .background(FinanceTheme.colors.surfaceContainerLow)
                        .padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.s),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_balances_clear),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Calm Needs Review banner in soft secondary-container tones (never alarm red).
 */
@Composable
fun NeedsReviewBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = FinanceShapes.card,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.l),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = FinanceTheme.spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Rule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "$count to review",
                        style = FinanceTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Need your confirmation",
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
            }

            Button(
                onClick = onClick,
                shape = FinanceShapes.pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minHeight = 40.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_review),
                    style = FinanceTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

/**
 * Small cards for Savings (SIPs and investments) and Income (salary and inflows).
 */
@Composable
fun SavingsAndIncomeSection(
    savings: Double,
    income: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        TonalCard(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dashboard_savings).uppercase(),
                style = FinanceTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            AmountText(
                amount = savings,
                tone = AmountTone.DEFAULT,
                style = FinanceTheme.typography.kpiMedium
            )
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            Text(
                text = stringResource(R.string.dashboard_savings_caption),
                style = FinanceTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TonalCard(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dashboard_income).uppercase(),
                style = FinanceTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            AmountText(
                amount = income,
                tone = AmountTone.DEFAULT,
                style = FinanceTheme.typography.kpiMedium
            )
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            Text(
                text = stringResource(R.string.dashboard_income_caption),
                style = FinanceTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Offline banner and waiting-to-sync captures list.
 */
@Composable
fun OfflineSection(
    queuedItems: List<QueuedItem>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = FinanceShapes.card,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FinanceTheme.spacing.l),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dashboard_offline_banner_title),
                        style = FinanceTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.dashboard_offline_banner_body),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                    )
                }
            }
        }

        if (queuedItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                Text(
                    text = stringResource(R.string.dashboard_waiting_to_sync),
                    style = FinanceTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TonalCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        queuedItems.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 40.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.name,
                                    style = FinanceTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                AmountText(
                                    amount = item.amount,
                                    tone = AmountTone.DEFAULT,
                                    style = FinanceTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
