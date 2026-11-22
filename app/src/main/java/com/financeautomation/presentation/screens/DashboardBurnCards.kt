package com.financeautomation.presentation.screens

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.presentation.components.AmountText
import com.financeautomation.presentation.components.AmountTone
import com.financeautomation.presentation.components.AppCard
import com.financeautomation.presentation.components.HeaderActionButton
import com.financeautomation.presentation.components.KpiCard
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stacked KPI layout (Y1 final):
 * - Row 1: Full-width Living burn card with large amount style and per-day line.
 * - Row 2: Two equal-width cards: Savings (amount + N% of income) and Income (amount).
 * Accessibility description: "Key figures".
 */
@Composable
fun KpiRow(
    livingBurn: Double,
    dailyAverage: Double,
    paceDeltaPercent: Double?,
    savings: Double,
    income: Double,
    modifier: Modifier = Modifier,
    onLivingBurnClick: () -> Unit = {},
    onLivingBurnLongClick: () -> Unit = {},
    onSavingsClick: () -> Unit = {},
    onSavingsLongClick: () -> Unit = {},
    onIncomeClick: () -> Unit = {},
    onIncomeLongClick: () -> Unit = {}
) {
    val keyFiguresCd = stringResource(R.string.dashboard_key_figures_cd)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = keyFiguresCd },
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.sizes.cardGap)
    ) {
        // Row 1: Full-width Living burn card
        val paceText = paceDeltaPercent?.let { delta ->
            val absDelta = abs(delta).roundToInt()
            if (absDelta == 0) {
                stringResource(R.string.kpi_pace_same)
            } else if (delta <= 0.0) {
                stringResource(R.string.kpi_pace_down, absDelta)
            } else {
                stringResource(R.string.kpi_pace_up, absDelta)
            }
        }
        val dailyText = if (dailyAverage > 0.0) {
            stringResource(R.string.kpi_daily_average, formatRupeesAuto(dailyAverage))
        } else null

        KpiCard(
            label = stringResource(R.string.dashboard_hero_burn_title),
            amount = livingBurn,
            isLargeAmount = true,
            line1Text = paceText,
            line1IsPrimary = (paceDeltaPercent ?: 0.0) <= 0.0,
            line2Text = dailyText,
            onClick = onLivingBurnClick,
            onLongClick = onLivingBurnLongClick,
            modifier = Modifier.fillMaxWidth()
        )

        // Row 2: Two equal-width cards: Savings and Income
        val savingsPercentText = if (income > 0.0) {
            val pct = ((savings / income) * 100).roundToInt()
            stringResource(R.string.kpi_savings_pct_income, pct)
        } else null

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.sizes.cardGap)
        ) {
            KpiCard(
                label = stringResource(R.string.dashboard_savings),
                amount = savings,
                isLargeAmount = false,
                line1Text = savingsPercentText,
                onClick = onSavingsClick,
                onLongClick = onSavingsLongClick,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            KpiCard(
                label = stringResource(R.string.dashboard_income),
                amount = income,
                isLargeAmount = false,
                onClick = onIncomeClick,
                onLongClick = onIncomeLongClick,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

/**
 * Calm secondary container banner for pending reviews: "N to review" with HeaderActionButton "Review".
 */
@Composable
fun NeedsReviewBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = FinanceShapes.appCard,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.compact)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Rule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                )
                Text(
                    text = stringResource(R.string.review_count_to_review, count),
                    style = FinanceTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            HeaderActionButton(
                text = stringResource(R.string.action_review),
                onClick = onClick
            )
        }
    }
}

/**
 * Unified Balances card: one card with two equal columns: "Owed to me" and "I owe".
 * Green only above zero for owed to me, red only above zero for I owe, zero in normal text colour.
 * Tapping opens people and settlements view.
 */
@Composable
fun BalancesCard(
    owedToMe: Double,
    iOwe: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.clickable(
            role = Role.Button,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Owed to me
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = FinanceTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.dashboard_owed_to_me),
                    style = FinanceTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AmountText(
                    amount = owedToMe,
                    tone = if (owedToMe > 0.0) AmountTone.MONEY_IN else AmountTone.DEFAULT,
                    style = FinanceTheme.typography.amountMedium
                )
            }

            // Subtle vertical divider
            Box(
                modifier = Modifier
                    .width(FinanceTheme.sizes.strokeThin)
                    .fillMaxHeight()
                    .padding(vertical = FinanceTheme.spacing.xs)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(FinanceTheme.sizes.strokeThin)
                ) {}
            }

            // Right Column: I owe
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FinanceTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.dashboard_i_owe),
                    style = FinanceTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AmountText(
                    amount = iOwe,
                    tone = if (iOwe > 0.0) AmountTone.MONEY_OUT else AmountTone.DEFAULT,
                    style = FinanceTheme.typography.amountMedium
                )
            }
        }
    }
}

/**
 * Calm offline banner explaining local saving:
 * "You are offline. New expenses are saved on this phone and will sync when you reconnect."
 */
@Composable
fun OfflineBanner(
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier,
        containerColor = FinanceTheme.colors.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
            )
            Text(
                text = stringResource(R.string.dashboard_offline_banner_text),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(name = "KpiRow Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun KpiRowPreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        KpiRow(
            livingBurn = 28549.0,
            dailyAverage = 951.63,
            paceDeltaPercent = -12.0,
            savings = 42000.0,
            income = 115000.0
        )
    }
}

@Preview(name = "KpiRow Font 1.3 - Light", showBackground = true, fontScale = 1.3f)
@Composable
private fun KpiRowPreviewLargeFontLight() {
    FinanceAutomationTheme(darkTheme = false) {
        KpiRow(
            livingBurn = 28549.0,
            dailyAverage = 951.63,
            paceDeltaPercent = -12.0,
            savings = 42000.0,
            income = 115000.0
        )
    }
}

@Preview(name = "BalancesCard Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun BalancesCardPreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        BalancesCard(
            owedToMe = 442.50,
            iOwe = 0.0,
            onClick = {}
        )
    }
}

@Preview(name = "BalancesCard Font 1.3 - Dark", showBackground = true, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BalancesCardPreviewLargeFontDark() {
    FinanceAutomationTheme(darkTheme = true) {
        BalancesCard(
            owedToMe = 442.50,
            iOwe = 350.0,
            onClick = {}
        )
    }
}
