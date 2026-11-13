package com.financeautomation.presentation.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.domain.model.Transaction
import com.financeautomation.presentation.components.AmountText
import com.financeautomation.presentation.components.TransactionRow
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.DayTransactionGroup
import com.financeautomation.presentation.viewmodel.TransactionsUiState
import com.financeautomation.presentation.viewmodel.TransactionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel,
    onTransactionClick: (Transaction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    TransactionsContent(
        state = state,
        onPreviousMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onCurrentMonth = viewModel::currentMonth,
        onSearchQueryChange = viewModel::setSearchQuery,
        onClearFilter = viewModel::clearFilter,
        onTransactionClick = onTransactionClick,
        modifier = modifier
    )
}

@Composable
fun TransactionsContent(
    state: TransactionsUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCurrentMonth: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearFilter: () -> Unit,
    onTransactionClick: (Transaction) -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FinanceTheme.colors.surface)
            .padding(horizontal = FinanceTheme.spacing.m)
    ) {
        // 1. Month Navigation Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = FinanceTheme.spacing.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousMonth) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Previous month",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = state.selectedMonthLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            IconButton(onClick = onNextMonth) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = "Next month",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 2. Month Totals Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surfaceVariant),
            shape = FinanceTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FinanceTheme.spacing.m),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Total Out",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))
                    AmountText(
                        amount = state.totalOut,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Box(
                    modifier = Modifier
                        .height(FinanceTheme.sizes.icon32)
                        .width(FinanceTheme.sizes.strokeThin)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "My Share",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))
                    AmountText(
                        amount = state.myShare,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        // 3. Search and Active Filter Bar
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search merchant, companion, category...") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            singleLine = true,
            shape = FinanceTheme.shapes.medium
        )

        if (state.activeFilter.isActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = FinanceTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                Text(
                    text = "Active filter:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = true,
                    onClick = onClearFilter,
                    label = {
                        Text(
                            listOfNotNull(
                                state.activeFilter.category,
                                state.activeFilter.account,
                                state.activeFilter.person,
                                state.activeFilter.tripTag,
                                state.activeFilter.status
                            ).joinToString(", ")
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = "Clear filter",
                            modifier = Modifier.size(FinanceTheme.sizes.icon16)
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        // 4. Ledger Content
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.groupedTransactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(FinanceTheme.spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (state.searchQuery.isNotBlank() || state.activeFilter.isActive) {
                            "No matching transactions"
                        } else {
                            "No transactions in this month"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
                    if (state.searchQuery.isNotBlank() || state.activeFilter.isActive) {
                        OutlinedButton(onClick = {
                            onSearchQueryChange("")
                            onClearFilter()
                        }) {
                            Text("Clear search & filters")
                        }
                    } else {
                        Button(onClick = onCurrentMonth) {
                            Text("Go to current month")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m),
                contentPadding = PaddingValues(bottom = FinanceTheme.spacing.bottomFabClearance)
            ) {
                state.groupedTransactions.forEach { group ->
                    item(key = group.dayTimestamp) {
                        DayHeader(group = group)
                    }

                    items(group.transactions, key = { it.id }) { txn ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = FinanceTheme.shapes.small
                        ) {
                            TransactionRow(
                                description = txn.merchant,
                                dateText = "${txn.account} | ${txn.category} | ${timeFormat.format(Date(txn.timestamp))}",
                                account = txn.account,
                                amount = txn.totalAmount,
                                status = txn.status.name,
                                onClick = { onTransactionClick(txn) },
                                modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(group: DayTransactionGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FinanceTheme.spacing.xs, bottom = FinanceTheme.spacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = group.dateLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Total: ${formatRupeesAuto(group.dayTotalOut)} (Mine: ${formatRupeesAuto(group.dayMyShare)})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

