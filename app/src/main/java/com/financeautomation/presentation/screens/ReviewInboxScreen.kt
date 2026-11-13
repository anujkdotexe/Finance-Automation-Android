package com.financeautomation.presentation.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.SubscriptionCycleWithSubscription
import com.financeautomation.domain.model.Transaction
import com.financeautomation.presentation.components.AppCard
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.HeaderActionButton
import com.financeautomation.presentation.components.OutlinedButton
import com.financeautomation.presentation.components.SyncStatusLine
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.ReviewInboxUiState
import com.financeautomation.presentation.viewmodel.ReviewInboxViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Frozen Needs Review screen conforming to UI Round 2 specifications.
 * Order:
 * 1. Single-line quiet sync status line
 * 2. Section label "N to review"
 * 3. Subscription due card(s) at top of list when a cycle is in NEEDS_REVIEW
 * 4. Failed sync cards with reason and Retry (filled) / Discard (outlined)
 * 5. Unreviewed transaction cards with Edit (outlined) / Confirm (filled)
 * 6. Empty state: check icon, "All caught up", OutlinedButton "Go to Dashboard"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewInboxScreen(
    reviewInboxViewModel: ReviewInboxViewModel,
    onEditTransaction: ((Transaction) -> Unit)? = null,
    onNavigateToDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by reviewInboxViewModel.uiState.collectAsState()
    val subscriptionsDue by reviewInboxViewModel.subscriptionsDue.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage, state.errorMessage) {
        state.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            reviewInboxViewModel.clearMessages()
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            reviewInboxViewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
    ) { innerPadding ->
        ReviewInboxContent(
            state = state,
            subscriptionsDue = subscriptionsDue,
            onConfirmTransaction = reviewInboxViewModel::confirmTransaction,
            onConfirmAll = reviewInboxViewModel::confirmAll,
            onSelectTransaction = { txn ->
                reviewInboxViewModel.selectTransaction(txn)
                onEditTransaction?.invoke(txn)
            },
            onSubscriptionYes = reviewInboxViewModel::subscriptionYes,
            onSubscriptionNotYet = reviewInboxViewModel::subscriptionNotYet,
            onSubscriptionSkip = reviewInboxViewModel::subscriptionSkip,
            onRetryQueueItem = reviewInboxViewModel::retryQueueItem,
            onDiscardQueueItem = reviewInboxViewModel::discardQueueItem,
            onNavigateToDashboard = onNavigateToDashboard,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

/**
 * Stateless Review Inbox content component.
 */
@Composable
fun ReviewInboxContent(
    state: ReviewInboxUiState,
    subscriptionsDue: List<SubscriptionCycleWithSubscription> = emptyList(),
    onConfirmTransaction: (String, String?) -> Unit,
    onConfirmAll: () -> Unit = {},
    onSelectTransaction: (Transaction) -> Unit,
    onSubscriptionYes: (String) -> Unit = {},
    onSubscriptionNotYet: (String) -> Unit = {},
    onSubscriptionSkip: (String) -> Unit = {},
    onRetryQueueItem: (Long) -> Unit = {},
    onDiscardQueueItem: (Long) -> Unit = {},
    onNavigateToDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalPendingCount = state.items.size + subscriptionsDue.size

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            state.items.isEmpty() && subscriptionsDue.isEmpty() && state.failedQueueItems.isEmpty() -> {
                EmptyInboxState(
                    onNavigateToDashboard = onNavigateToDashboard,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = FinanceTheme.spacing.l,
                        top = FinanceTheme.spacing.s,
                        end = FinanceTheme.spacing.l,
                        bottom = FinanceTheme.spacing.bottomFabClearance
                    ),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    // 1. Single-line quiet sync line
                    item {
                        SyncStatusLine(
                            waitingCount = 0,
                            failedCount = state.failedQueueItems.size,
                            lastSyncedText = "Synced 2m ago"
                        )
                    }

                    // 2. Section Header: "N to review" on left, "Confirm all" on right
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$totalPendingCount to review",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (state.items.isNotEmpty()) {
                                HeaderActionButton(
                                    text = "Confirm all",
                                    onClick = onConfirmAll
                                )
                            }
                        }
                    }

                    // 3. Subscriptions Due Cards at the top
                    items(
                        items = subscriptionsDue,
                        key = { "sub_${it.cycle.id}" }
                    ) { sub ->
                        SubscriptionDueCard(
                            subscriptionName = sub.subscription.name,
                            amount = sub.subscription.amount,
                            dueFormatted = formatDueEpochDay(sub.cycle.dueEpochDay),
                            onYes = { onSubscriptionYes(sub.cycle.id) },
                            onNotYet = { onSubscriptionNotYet(sub.cycle.id) },
                            onSkipThisTime = { onSubscriptionSkip(sub.cycle.id) }
                        )
                    }

                    // 4. Failed sync operations
                    if (state.failedQueueItems.isNotEmpty()) {
                        items(
                            items = state.failedQueueItems,
                            key = { "failed_queue_${it.id}" }
                        ) { queueItem ->
                            FailedQueueItemCard(
                                item = queueItem,
                                onRetry = { onRetryQueueItem(queueItem.id) },
                                onDiscard = { onDiscardQueueItem(queueItem.id) }
                            )
                        }
                    }

                    // 5. Normal unreviewed transaction cards
                    items(
                        items = state.items,
                        key = { it.id }
                    ) { transaction ->
                        ReviewTransactionCard(
                            transaction = transaction,
                            onConfirm = { onConfirmTransaction(transaction.id, null) },
                            onEdit = { onSelectTransaction(transaction) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FailedQueueItemCard(
    item: SyncQueueEntity,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = FinanceTheme.colors.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            val opTitle = when (item.operationType) {
                SyncQueueEntity.OP_LOG_CREDIT -> "Friend Settlement"
                SyncQueueEntity.OP_LOG_PAYABLE -> "Friend Payable"
                SyncQueueEntity.OP_LOG_REPAYMENT -> "Repayment"
                SyncQueueEntity.OP_CARRY_FORWARD -> "Monthly Rollover"
                SyncQueueEntity.OP_YEAR_CLOSE -> "Year-End Close"
                SyncQueueEntity.OP_INSERT -> "Expense Insert"
                SyncQueueEntity.OP_UPDATE -> "Expense Update"
                SyncQueueEntity.OP_VOID -> "Expense Void"
                else -> item.operationType
            }
            Text(
                text = opTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = "Failed after ${item.retryCount} attempts. ID: ${item.transactionId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ButtonPair(
                outlinedText = "Discard",
                onOutlinedClick = onDiscard,
                filledText = "Retry",
                onFilledClick = onRetry
            )
        }
    }
}

@Composable
fun EmptyInboxState(
    onNavigateToDashboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(FinanceTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = "All caught up",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        OutlinedButton(
            text = "Go to Dashboard",
            onClick = onNavigateToDashboard
        )
    }
}

private fun formatDueEpochDay(epochDay: Long): String {
    val todayEpochDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000L)
    return if (epochDay == todayEpochDay) {
        "today"
    } else {
        val millis = epochDay * 24 * 60 * 60 * 1000L
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))
    }
}

@Preview(name = "Empty State - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun EmptyInboxStatePreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        EmptyInboxState(onNavigateToDashboard = {})
    }
}

@Preview(name = "Empty State - Font 1.3", showBackground = true, fontScale = 1.3f)
@Composable
private fun EmptyInboxStatePreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        EmptyInboxState(onNavigateToDashboard = {})
    }
}

@Preview(name = "Empty State - Dark", showBackground = true, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyInboxStatePreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        EmptyInboxState(onNavigateToDashboard = {})
    }
}
