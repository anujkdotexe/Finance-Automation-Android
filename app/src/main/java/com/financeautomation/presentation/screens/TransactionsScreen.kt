package com.financeautomation.presentation.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.financeautomation.presentation.components.LinkButton
import com.financeautomation.presentation.components.OutlinedButton
import com.financeautomation.presentation.components.StatusChip
import com.financeautomation.presentation.components.SyncStatusLine
import com.financeautomation.presentation.components.TransactionRow
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.DayTransactionGroup
import com.financeautomation.presentation.viewmodel.SearchScope
import com.financeautomation.presentation.viewmodel.TransactionFilter
import com.financeautomation.presentation.viewmodel.TransactionsUiState
import com.financeautomation.presentation.viewmodel.TransactionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Frozen Transactions screen conforming to UI Round 2 specifications.
 * Elements:
 * - Top bar, quiet sync line, month switcher (same component as Dashboard)
 * - "Month total" as one number
 * - Search field
 * - One chip row: All, UPI, Card, Cash, Needs Review
 * - Rows grouped by day, day total on day header, rows 56dp minimum
 * - Empty month: calendar icon, "No transactions in <Month>", FilledButton "Add expense", OutlinedButton "Go to current month"
 * - No results: search icon, "No matches found", OutlinedButton "Clear search", OutlinedButton "Search all months"
 * - Transaction detail bottom sheet with split summary, Void (outlined), Edit (filled), Details link
 */
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel,
    onTransactionClick: (Transaction) -> Unit = {},
    onAddExpense: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
    ) { innerPadding ->
        TransactionsContent(
            state = state,
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onCurrentMonth = viewModel::currentMonth,
            onSearchQueryChange = viewModel::setSearchQuery,
            onSearchScopeChange = viewModel::setSearchScope,
            onFilterChange = viewModel::setFilter,
            onClearFilter = viewModel::clearFilter,
            onTransactionClick = { txn ->
                selectedTransaction = txn
                onTransactionClick(txn)
            },
            onAddExpense = onAddExpense,
            modifier = Modifier.padding(innerPadding)
        )

        selectedTransaction?.let { txn ->
            TransactionDetailSheet(
                transaction = txn,
                onDismiss = { selectedTransaction = null },
                onEdit = {
                    selectedTransaction = null
                    onTransactionClick(txn)
                },
                onVoid = {
                    viewModel.voidTransaction(txn.id)
                    selectedTransaction = null
                }
            )
        }
    }
}

@Composable
fun TransactionsContent(
    state: TransactionsUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCurrentMonth: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchScopeChange: (SearchScope) -> Unit,
    onFilterChange: (TransactionFilter) -> Unit,
    onClearFilter: () -> Unit,
    onTransactionClick: (Transaction) -> Unit,
    onAddExpense: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = FinanceTheme.spacing.l)
    ) {
        // 1. Single-line quiet sync line
        SyncStatusLine(
            lastSyncedText = "Synced 2m ago",
            modifier = Modifier.padding(top = FinanceTheme.spacing.s)
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        // 2. Month Switcher (shared component with Dashboard)
        MonthSwitcher(
            monthLabel = state.selectedMonthLabel,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        // 3. Month Total Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = FinanceTheme.spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.transactions_month_total),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatRupeesAuto(state.totalOut),
                style = FinanceTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        // 4. Search Bar
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.transactions_search_placeholder),
                    style = FinanceTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
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
            shape = FinanceShapes.card,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = FinanceTheme.colors.surfaceContainerLowest,
                unfocusedContainerColor = FinanceTheme.colors.surfaceContainerLowest,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        // 5. One Chip Filter Row: All, UPI, Card, Cash, Needs Review
        val currentAccount = state.activeFilter.account
        val currentStatus = state.activeFilter.status
        val isAllSelected = currentAccount == null && currentStatus == null
        val scrollState = rememberScrollState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppChip(
                text = "All",
                selected = isAllSelected,
                onClick = onClearFilter
            )
            AppChip(
                text = "UPI",
                selected = currentAccount.equals("UPI", ignoreCase = true),
                onClick = {
                    if (currentAccount.equals("UPI", ignoreCase = true)) onClearFilter()
                    else onFilterChange(TransactionFilter(account = "UPI"))
                }
            )
            AppChip(
                text = "Card",
                selected = currentAccount.equals("Card", ignoreCase = true),
                onClick = {
                    if (currentAccount.equals("Card", ignoreCase = true)) onClearFilter()
                    else onFilterChange(TransactionFilter(account = "Card"))
                }
            )
            AppChip(
                text = "Cash",
                selected = currentAccount.equals("Cash", ignoreCase = true),
                onClick = {
                    if (currentAccount.equals("Cash", ignoreCase = true)) onClearFilter()
                    else onFilterChange(TransactionFilter(account = "Cash"))
                }
            )
            AppChip(
                text = "Needs Review",
                selected = currentStatus.equals("review", ignoreCase = true),
                onClick = {
                    if (currentStatus.equals("review", ignoreCase = true)) onClearFilter()
                    else onFilterChange(TransactionFilter(status = "review"))
                }
            )
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        // 6. Content Section
        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            state.searchQuery.isNotBlank() && state.groupedTransactions.isEmpty() -> {
                // No Results State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = FinanceTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.icon48)
                    )
                    Text(
                        text = stringResource(R.string.transactions_no_matches_title),
                        style = FinanceTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.transactions_no_matches_subtitle,
                            state.searchQuery,
                            state.selectedMonthLabel
                        ),
                        style = FinanceTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .padding(top = FinanceTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                    ) {
                        OutlinedButton(
                            text = stringResource(R.string.action_clear_search),
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            text = stringResource(R.string.action_search_all_months),
                            onClick = { onSearchScopeChange(SearchScope.ALL_MONTHS) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            state.groupedTransactions.isEmpty() -> {
                // Empty Month State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = FinanceTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.icon48)
                    )
                    Text(
                        text = stringResource(R.string.transactions_empty_month_title, state.selectedMonthLabel),
                        style = FinanceTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .padding(top = FinanceTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                    ) {
                        FilledButton(
                            text = stringResource(R.string.action_add_expense),
                            onClick = onAddExpense,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            text = stringResource(R.string.action_go_to_current_month),
                            onClick = onCurrentMonth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            else -> {
                // Day Groups Ledger
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FinanceTheme.spacing.bottomFabClearance),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    state.groupedTransactions.forEach { group ->
                        item(key = "header_${group.dateLabel}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = FinanceTheme.spacing.xs),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = group.dateLabel,
                                    style = FinanceTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatRupeesAuto(group.dayTotalOut),
                                    style = FinanceTheme.typography.numericMicro,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = FinanceTheme.sizes.strokeThin
                            )
                        }

                        items(
                            items = group.transactions,
                            key = { it.id }
                        ) { txn ->
                            val splitText = if (txn.owedToMe > 0.0) "Split (${formatRupeesAuto(txn.myShare)})" else null
                            val timeStr = timeFormat.format(Date(txn.timestamp))

                            TransactionRow(
                                merchant = txn.merchant.ifBlank { txn.category },
                                amount = txn.totalAmount,
                                category = txn.category,
                                account = txn.account,
                                time = timeStr,
                                status = txn.status.value,
                                splitText = splitText,
                                onClick = { onTransactionClick(txn) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Transaction Detail Bottom Sheet Modal.
 * Layout:
 * - Merchant name (18sp SemiBold)
 * - Mono amount (28sp SemiBold)
 * - Category, account, date and time
 * - Split summary ("My share ₹200 · Owed ₹400")
 * - Status chip
 * - Buttons row: OutlinedButton "Void" on left, FilledButton "Edit" on right (equal width, 48dp)
 * - Expandable metadata info on clicking LinkButton "Details"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onVoid: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDetails by remember { mutableStateOf(false) }
    val fullDateFormat = remember { SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FinanceShapes.sheet,
        containerColor = FinanceTheme.colors.surfaceContainerLowest,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceTheme.spacing.l)
                .padding(bottom = FinanceTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            // Header: Merchant + Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = transaction.merchant.ifBlank { transaction.category },
                    style = FinanceTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                StatusChip(status = transaction.status.value)
            }

            // Big Mono Amount
            Text(
                text = formatRupeesAuto(transaction.totalAmount),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Info items: Category, Account, Date & Time
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = FinanceTheme.colors.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                    DetailRow(label = "Category", value = transaction.category)
                    DetailRow(label = "Account", value = transaction.account)
                    DetailRow(label = "Date & time", value = fullDateFormat.format(Date(transaction.timestamp)))

                    val splitSummary = if (transaction.owedToMe > 0.0) {
                        "My share ${formatRupeesAuto(transaction.myShare)} · Owed ${formatRupeesAuto(transaction.owedToMe)}"
                    } else {
                        "Personal (My share ${formatRupeesAuto(transaction.myShare)})"
                    }
                    DetailRow(label = "Split", value = splitSummary)
                }
            }

            // Action Buttons: Void on left, Edit on right (equal width, 48dp height)
            ButtonPair(
                outlinedText = stringResource(R.string.action_void),
                onOutlinedClick = onVoid,
                filledText = stringResource(R.string.action_edit),
                onFilledClick = onEdit
            )

            // Subtle "Details" link button
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinkButton(
                    text = if (showDetails) stringResource(R.string.action_hide_details) else stringResource(R.string.action_details),
                    onClick = { showDetails = !showDetails }
                )

                if (showDetails) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FinanceTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        Text(
                            text = "Sheet row: ${transaction.sheetRowIndex ?: "Unassigned"}",
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "ID: ${transaction.id}",
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = FinanceTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = FinanceTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Preview(name = "Transactions Light Normal Font", showBackground = true, fontScale = 1.0f)
@Composable
private fun TransactionsPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        TransactionsContent(
            state = TransactionsUiState(
                selectedMonthLabel = "November 2026",
                totalOut = 28549.0,
                myShare = 24000.0,
                groupedTransactions = listOf(
                    DayTransactionGroup(
                        dateLabel = "Today",
                        dayTimestamp = System.currentTimeMillis(),
                        dayTotalOut = 1240.0,
                        dayMyShare = 840.0,
                        transactions = listOf(
                            Transaction(
                                id = "1",
                                timestamp = System.currentTimeMillis(),
                                dateSerial = 45600,
                                timeSerial = 0.5,
                                merchant = "Swiggy",
                                totalAmount = 600.0,
                                category = "Food",
                                account = "HDFC",
                                myShare = 200.0,
                                owedToMe = 400.0
                            )
                        )
                    )
                ),
                isLoading = false
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onCurrentMonth = {},
            onSearchQueryChange = {},
            onSearchScopeChange = {},
            onFilterChange = {},
            onClearFilter = {},
            onTransactionClick = {},
            onAddExpense = {}
        )
    }
}

@Preview(name = "Transactions Light Font 1.3", showBackground = true, fontScale = 1.3f)
@Composable
private fun TransactionsPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        TransactionsContent(
            state = TransactionsUiState(
                selectedMonthLabel = "November 2026",
                totalOut = 28549.0,
                myShare = 24000.0,
                groupedTransactions = listOf(
                    DayTransactionGroup(
                        dateLabel = "Today",
                        dayTimestamp = System.currentTimeMillis(),
                        dayTotalOut = 1240.0,
                        dayMyShare = 840.0,
                        transactions = listOf(
                            Transaction(
                                id = "1",
                                timestamp = System.currentTimeMillis(),
                                dateSerial = 45600,
                                timeSerial = 0.5,
                                merchant = "Swiggy",
                                totalAmount = 600.0,
                                category = "Food",
                                account = "HDFC",
                                myShare = 200.0,
                                owedToMe = 400.0
                            )
                        )
                    )
                ),
                isLoading = false
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onCurrentMonth = {},
            onSearchQueryChange = {},
            onSearchScopeChange = {},
            onFilterChange = {},
            onClearFilter = {},
            onTransactionClick = {},
            onAddExpense = {}
        )
    }
}
