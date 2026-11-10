package com.financeautomation.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Transaction
import com.financeautomation.presentation.components.ExpenseModalBottomSheet
import com.financeautomation.presentation.components.StatusChip
import com.financeautomation.presentation.viewmodel.ExpenseViewModel
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
    expenseViewModel: ExpenseViewModel? = null,
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
                expenseViewModel?.loadTransaction(txn.id)
            },
            modifier = Modifier.padding(innerPadding)
        )
    }

    // Modal Bottom Sheet for detailed review and split editing
    if (state.selectedTransaction != null && expenseViewModel != null) {
        ExpenseModalBottomSheet(
            viewModel = expenseViewModel,
            onDismiss = {
                reviewInboxViewModel.clearSelection()
            }
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
            state.items.isEmpty() -> {
                EmptyInboxState(modifier = Modifier.align(Alignment.Center))
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Receipt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${state.items.size} transaction${if (state.items.size > 1) "s" else ""} awaiting confirmation",
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

