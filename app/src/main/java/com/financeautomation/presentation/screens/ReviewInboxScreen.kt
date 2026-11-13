package com.financeautomation.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Transaction
import com.financeautomation.presentation.components.StatusChip
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.ReviewInboxUiState
import com.financeautomation.presentation.viewmodel.ReviewInboxViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Review Inbox screen observing unreviewed transactions.
 * Styled with modern high-contrast fintech aesthetic and soft elevated cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewInboxScreen(
    reviewInboxViewModel: ReviewInboxViewModel,
    onEditTransaction: ((Transaction) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by reviewInboxViewModel.uiState.collectAsState()
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
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
    ) { innerPadding ->
        ReviewInboxContent(
            state = state,
            onConfirmTransaction = reviewInboxViewModel::confirmTransaction,
            onVoidTransaction = { txnId ->
                reviewInboxViewModel.voidTransaction(txnId)
            },
            onSelectTransaction = { txn ->
                reviewInboxViewModel.selectTransaction(txn)
                onEditTransaction?.invoke(txn)
            },
            onRetryQueueItem = reviewInboxViewModel::retryQueueItem,
            onDiscardQueueItem = reviewInboxViewModel::discardQueueItem,
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
    onConfirmTransaction: (String, String?) -> Unit,
    onVoidTransaction: (String) -> Unit,
    onSelectTransaction: (Transaction) -> Unit,
    onRetryQueueItem: (Long) -> Unit = {},
    onDiscardQueueItem: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
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
            state.items.isEmpty() && state.failedQueueItems.isEmpty() -> {
                EmptyInboxState(modifier = Modifier.align(Alignment.Center))
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(FinanceTheme.spacing.l),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    if (state.failedQueueItems.isNotEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                shape = FinanceTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                    )
                                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                                    Text(
                                        text = "Failed Sync Operations (${state.failedQueueItems.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

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

                    if (state.items.isNotEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                shape = FinanceTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Receipt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                    )
                                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                                    Text(
                                        text = stringResource(R.string.review_awaiting_confirmation, state.items.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        items(
                            items = state.items,
                            key = { it.id }
                        ) { transaction ->
                            ReviewTransactionCard(
                                transaction = transaction,
                                onConfirm = { onConfirmTransaction(transaction.id, null) },
                                onVoid = { onVoidTransaction(transaction.id) },
                                onEdit = { onSelectTransaction(transaction) }
                            )
                        }
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
    Card(
        shape = FinanceTheme.shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.m)
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
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            Text(
                text = "Failed after ${item.retryCount} attempts. ID: ${item.transactionId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Discard", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

