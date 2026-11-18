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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.usecase.RecurringSuggestion
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.HeaderActionButton
import java.time.LocalDate
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
    var showAddSubscriptionSheet by rememberSaveable { mutableStateOf(false) }
    var trackingSuggestion by remember { mutableStateOf<RecurringSuggestion?>(null) }
    var selectedSubscriptionForAction by remember { mutableStateOf<Subscription?>(null) }
    var editingSubscription by remember { mutableStateOf<Subscription?>(null) }
    var deletingSubscription by remember { mutableStateOf<Subscription?>(null) }

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

            state.suggestions.forEach { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    onTrack = { trackingSuggestion = suggestion },
                    onNotNow = { viewModel.dismissSuggestion(suggestion) }
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
                        if (state.activeSubscriptions.isNotEmpty()) {
                            val nextDue = state.nextDueSubscription
                            val nextDueDay = nextDue?.let { state.nextDueByDay[it.id] }
                            val nextDueText = if (nextDue != null && nextDueDay != null) {
                                stringResource(R.string.sub_next_due_formatted, nextDue.name, formatDueDay(nextDueDay))
                            } else null
                            if (state.variesAmountCount > 0) {
                                Text(
                                    text = stringResource(R.string.sub_total_excludes_varies, state.variesAmountCount),
                                    style = FinanceTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (nextDueText != null) {
                                Text(
                                    text = nextDueText,
                                    style = FinanceTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
                Surface(
                    color = FinanceTheme.colors.surfaceContainerLowest,
                    shape = FinanceShapes.large,
                    shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FinanceTheme.spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        Text(
                            text = stringResource(R.string.sub_empty_title),
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.sub_empty_subtitle),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    state.activeSubscriptions.forEach { subscription ->
                        SubscriptionItemCard(
                            subscription = subscription,
                            nextDueDay = state.nextDueByDay[subscription.id],
                            onClick = { selectedSubscriptionForAction = subscription }
                        )
                    }
                }
            }

            // Paused Subscriptions Section
            if (state.pausedSubscriptions.isNotEmpty()) {
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
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                        ) {
                            state.pausedSubscriptions.forEach { subscription ->
                                SubscriptionItemCard(
                                    subscription = subscription,
                                    nextDueDay = null,
                                    onClick = { selectedSubscriptionForAction = subscription }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedSubscriptionForAction?.let { sub ->
        AlertDialog(
            onDismissRequest = { selectedSubscriptionForAction = null },
            title = {
                Text(
                    text = sub.name,
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    OutlinedButton(
                        onClick = {
                            editingSubscription = sub
                            selectedSubscriptionForAction = null
                        },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.sub_action_edit))
                    }
                    OutlinedButton(
                        onClick = {
                            if (sub.active) viewModel.pauseSubscription(sub.id) else viewModel.resumeSubscription(sub.id)
                            selectedSubscriptionForAction = null
                        },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    ) {
                        Text(if (sub.active) stringResource(R.string.sub_action_pause) else stringResource(R.string.sub_action_resume))
                    }
                    OutlinedButton(
                        onClick = {
                            deletingSubscription = sub
                            selectedSubscriptionForAction = null
                        },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.sub_action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { selectedSubscriptionForAction = null },
                    modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    deletingSubscription?.let { sub ->
        AlertDialog(
            onDismissRequest = { deletingSubscription = null },
            title = {
                Text(
                    text = stringResource(R.string.sub_delete_confirm_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.sub_delete_confirm_desc, sub.name),
                    style = FinanceTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSubscription(sub.id)
                        deletingSubscription = null
                    },
                    modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.sub_action_delete))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { deletingSubscription = null },
                    modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    editingSubscription?.let { sub ->
        AddSubscriptionSheet(
            accounts = accounts,
            existing = sub,
            onDismiss = { editingSubscription = null },
            onSave = { name, amount, frequency, account, remind, note ->
                viewModel.updateSubscription(
                    sub.copy(
                        name = name,
                        amount = amount,
                        frequency = frequency,
                        account = account,
                        remind = remind,
                        note = note
                    )
                )
                editingSubscription = null
            }
        )
    }

    trackingSuggestion?.let { suggestion ->
        AddSubscriptionSheet(
            accounts = accounts,
            prefill = suggestion,
            onDismiss = { trackingSuggestion = null },
            onSave = { name, amount, frequency, account, remind, note ->
                viewModel.addSubscription(
                    name = name,
                    amount = amount,
                    frequency = frequency,
                    account = account,
                    remind = remind,
                    anchorEpochDay = suggestion.anchorEpochDay,
                    category = suggestion.category,
                    note = note
                )
                viewModel.dismissSuggestion(suggestion)
                trackingSuggestion = null
            }
        )
    }

    if (showAddSubscriptionSheet) {
        AddSubscriptionSheet(
            accounts = accounts,
            onDismiss = { showAddSubscriptionSheet = false },
            onSave = { name, amount, frequency, account, remind, note ->
                viewModel.addSubscription(name, amount, frequency, account, remind, note = note)
                showAddSubscriptionSheet = false
            }
        )
    }
}

private val DUE_DAY_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US)

/** An epoch day (a calendar date in Asia/Kolkata) as "15 Apr 2026". */
private fun formatDueDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(DUE_DAY_FORMAT)

@Composable
private fun SubscriptionItemCard(
    subscription: Subscription,
    nextDueDay: Long?,
    onClick: () -> Unit,
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
        onClick = onClick,
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
                        text = if (nextDueDay != null) {
                            stringResource(R.string.sub_next_due, formatDueDay(nextDueDay))
                        } else {
                            stringResource(R.string.sub_paused_label)
                        },
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
                    text = stringResource(R.string.subscriptions_per_month),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/** One suggestion: a payment that repeats and is not tracked yet. Track it opens the Add sheet prefilled. */
@Composable
private fun SuggestionCard(
    suggestion: RecurringSuggestion,
    onTrack: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val every = stringResource(
        when (suggestion.frequency) {
            SubscriptionFrequency.WEEKLY -> R.string.sub_suggestion_every_week
            SubscriptionFrequency.MONTHLY -> R.string.sub_suggestion_every_month
            SubscriptionFrequency.YEARLY -> R.string.sub_suggestion_every_year
        }
    )
    com.financeautomation.presentation.components.AppCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.sub_suggestion_text, suggestion.name, every, formatRupeesAuto(suggestion.amount)),
            style = FinanceTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
        ButtonPair(
            outlinedText = stringResource(R.string.sub_suggestion_not_now),
            onOutlinedClick = onNotNow,
            filledText = stringResource(R.string.sub_suggestion_track),
            onFilledClick = onTrack,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
