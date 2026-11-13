package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.domain.model.Subscription
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.FilledButton
import com.financeautomation.presentation.components.HeaderActionButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SubscriptionsViewModel

@Composable
fun SubscriptionsScreen(
    viewModel: SubscriptionsViewModel,
    accounts: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    var showAddSubscriptionSheet by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m)
                .padding(bottom = FinanceTheme.spacing.bottomFabClearance),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
        ) {
            // Header with Add button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sub_title),
                    style = FinanceTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HeaderActionButton(
                    text = stringResource(R.string.accounts_action_add),
                    onClick = { showAddSubscriptionSheet = true }
                )
            }

            // Summary Card
            Surface(
                color = FinanceTheme.colors.surfaceContainerLowest,
                shape = FinanceShapes.large,
                shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FinanceTheme.spacing.m),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.sub_total_monthly),
                            style = FinanceTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = FinanceShapes.small
                        ) {
                            Text(
                                text = stringResource(R.string.sub_active_count, state.activeSubscriptions.size),
                                style = FinanceTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                        ) {
                            Text(
                                text = formatRupeesAuto(state.totalMonthlySpend),
                                style = FinanceTheme.typography.kpiLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.sub_per_month),
                                style = FinanceTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = FinanceTheme.spacing.xxs)
                            )
                        }
                        Text(
                            text = stringResource(R.string.sub_next_due_today),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Proportion bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FinanceTheme.sizes.proportionBarHeight)
                            .clip(CircleShape)
                            .background(FinanceTheme.colors.surfaceContainerHigh)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .height(FinanceTheme.sizes.proportionBarHeight)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            // Active Subscriptions Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sub_active_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    color = FinanceTheme.colors.surfaceContainerHigh,
                    shape = FinanceShapes.small
                ) {
                    Text(
                        text = state.activeSubscriptions.size.toString(),
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                    )
                }
            }

            if (state.activeSubscriptions.isEmpty()) {
                EmptyState(
                    message = stringResource(R.string.sub_empty_title)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    state.activeSubscriptions.forEach { subscription ->
                        SubscriptionItemCard(subscription = subscription)
                    }
                }
            }

            // Paused Subscriptions Section
            Surface(
                color = FinanceTheme.colors.surfaceContainerLowest,
                shape = FinanceShapes.large,
                shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FinanceTheme.spacing.m),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sub_paused_title),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        color = FinanceTheme.colors.surfaceContainer,
                        shape = FinanceShapes.small
                    ) {
                        Text(
                            text = state.pausedSubscriptions.size.toString(),
                            style = FinanceTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                        )
                    }
                }
            }
        }

        // Pinned Bottom Button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .padding(FinanceTheme.spacing.l)
        ) {
            FilledButton(
                text = stringResource(R.string.sub_add_title),
                onClick = { showAddSubscriptionSheet = true },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showAddSubscriptionSheet) {
        AddSubscriptionSheet(
            accounts = accounts,
            onDismiss = { showAddSubscriptionSheet = false },
            onSave = { name, amount, frequency, account, remind ->
                viewModel.addSubscription(name, amount, frequency, account, remind)
                showAddSubscriptionSheet = false
            }
        )
    }
}

@Composable
private fun SubscriptionItemCard(
    subscription: Subscription,
    modifier: Modifier = Modifier
) {
    val icon = remember(subscription.name) {
        val lower = subscription.name.lowercase()
        when {
            lower.contains("netflix") || lower.contains("prime") || lower.contains("disney") || lower.contains("hotstar") -> Icons.Rounded.Movie
            lower.contains("spotify") || lower.contains("music") || lower.contains("youtube") -> Icons.Rounded.GraphicEq
            lower.contains("cloud") || lower.contains("drive") || lower.contains("icloud") -> Icons.Rounded.Cloud
            else -> Icons.Rounded.Subscriptions
        }
    }

    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                .padding(FinanceTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Surface(
                    color = FinanceTheme.colors.surfaceContainer,
                    shape = RoundedCornerShape(FinanceTheme.spacing.s),
                    modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.name,
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.sub_next_due, "20 Dec 2026"),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = FinanceTheme.spacing.m)
            ) {
                Text(
                    text = if (subscription.amount != null) {
                        formatRupeesAuto(subscription.amount)
                    } else {
                        stringResource(R.string.subscription_amount_varies)
                    },
                    style = FinanceTheme.typography.kpiMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "per month",
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

