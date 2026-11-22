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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PrivacyScrubber
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.core.utils.DisplayDateTime
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.core.utils.stripVoidPrefix
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import com.financeautomation.presentation.components.AppCard
import com.financeautomation.presentation.util.MonthCsvExporter
import com.financeautomation.presentation.components.AppChip
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.FilledButton
import com.financeautomation.presentation.components.InflowRow
import com.financeautomation.presentation.components.inflowTypeLabel
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
import com.financeautomation.presentation.viewmodel.TransactionListItem
import com.financeautomation.presentation.viewmodel.TransactionsUiState
import com.financeautomation.presentation.viewmodel.TransactionsViewModel
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel,
    onTransactionClick: (Transaction) -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToNeedsReview: () -> Unit = {},
    onAddExpense: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var selectedInflow by remember { mutableStateOf<Inflow?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { viewModel.syncNow() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TransactionsContent(
                state = state,
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onCurrentMonth = viewModel::currentMonth,
                onSelectMonthOffset = viewModel::setMonthOffset,
                onSearchQueryChange = viewModel::setSearchQuery,
                onSearchScopeChange = viewModel::setSearchScope,
                onFilterChange = viewModel::setFilter,
                onClearFilter = viewModel::clearFilter,
                onTransactionClick = onTransactionClick,
                onInflowClick = { inf ->
                    selectedInflow = inf
                },
                onNavigateToSync = onNavigateToSync,
                onNavigateToNeedsReview = onNavigateToNeedsReview,
                onVoidTransaction = { targetTxn ->
                    viewModel.voidTransaction(targetTxn)
                    coroutineScope.launch {
                        val cleanName = stripVoidPrefix(targetTxn.merchant.ifBlank { targetTxn.category })
                        val result = snackbarHostState.showSnackbar(
                            message = context.getString(R.string.txns_voided_named_msg, cleanName),
                            actionLabel = context.getString(R.string.action_undo),
                            duration = SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoVoid()
                        }
                    }
                },
                onExportThisMonth = {
                    coroutineScope.launch {
                        MonthCsvExporter.exportMonth(context, state.monthOffset)
                            .onFailure { e ->
                                Diagnostics.record("TransactionsScreen", "export_failed: ${SyncErrorKind.safeCode("export", e)} ${PrivacyScrubber.scrub(e.message.orEmpty())}")
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.export_failed, SyncErrorKind.safeCode("export", e))
                                )
                            }
                    }
                },
                onAddExpense = onAddExpense,
                modifier = Modifier.fillMaxSize()
            )
        }

        selectedInflow?.let { inf ->
            IncomeDetailSheet(
                inflow = inf,
                onDismiss = { selectedInflow = null },
                onVoid = {
                    viewModel.voidInflow(inf.id) { success ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(if (success) R.string.txns_entry_voided_msg else R.string.txns_inflow_void_blocked_msg)
                            )
                        }
                    }
                    selectedInflow = null
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
    onSelectMonthOffset: (Int) -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onSearchScopeChange: (SearchScope) -> Unit,
    onFilterChange: (TransactionFilter) -> Unit,
    onClearFilter: () -> Unit,
    onTransactionClick: (Transaction) -> Unit,
    onInflowClick: (Inflow) -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToNeedsReview: () -> Unit = {},
    onVoidTransaction: (Transaction) -> Unit = {},
    onExportThisMonth: () -> Unit = {},
    onAddExpense: () -> Unit,
    modifier: Modifier = Modifier
) {
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = FinanceTheme.spacing.l)
    ) {
        // 1. Single-line quiet sync line
        SyncStatusLine(
            waitingCount = state.waitingCount,
            failedCount = state.failedCount,
            isSyncing = state.isSyncing,
            modifier = Modifier.padding(top = FinanceTheme.spacing.s),
            onFailedClick = onNavigateToNeedsReview,
            onClick = onNavigateToSync
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        // 2. Month Switcher (shared component with Dashboard) + Overflow Menu
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                MonthSwitcher(
                    monthLabel = state.selectedMonthLabel,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onSelectMonthOffset = onSelectMonthOffset
                )
            }

            var showMenu by rememberSaveable { mutableStateOf(false) }
            var showClearVoided by rememberSaveable { mutableStateOf(false) }
            com.financeautomation.presentation.components.ClearVoidedHistoryHost(
                show = showClearVoided,
                onDismiss = { showClearVoided = false }
            )
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(FinanceTheme.sizes.minTouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.action_export_this_month),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_export_this_month)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onExportThisMonth()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.clear_voided_menu)) },
                        onClick = {
                            showMenu = false
                            showClearVoided = true
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        // 3. Month Total Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = FinanceTheme.spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.txn_your_share),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.totalOut > 0.0) {
                    Text(
                        text = stringResource(R.string.txns_total_paid, formatRupeesAuto(state.totalOut)),
                        style = FinanceTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
            Text(
                text = formatRupeesAuto(state.myShare),
                style = FinanceTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        val searchState = com.financeautomation.presentation.components.rememberDebouncedTextState(
            value = state.searchQuery,
            debounceMs = 250L,
            onValueChanged = onSearchQueryChange
        )

        // 4. Search Bar
        OutlinedTextField(
            value = searchState.textFieldValue,
            onValueChange = { searchState.onValueChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { searchState.onFocusChanged(it.isFocused) },
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
                if (searchState.textFieldValue.text.isNotEmpty()) {
                    IconButton(onClick = { searchState.setExplicitText("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = stringResource(R.string.txn_clear_search),
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

        // 5. One Chip Filter Row: All, UPI, Card, Cash, Show void, Needs Review, Income (+ active Category / Living burn chip)
        val currentAccount = state.activeFilter.account
        val currentStatus = state.activeFilter.status
        val currentCategory = state.activeFilter.category
        val isLivingBurn = state.activeFilter.excludeSavingsAndIgnored
        val isShowVoid = state.activeFilter.showVoid
        val isAllSelected = !state.activeFilter.isActive
        val scrollState = rememberScrollState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!currentCategory.isNullOrBlank()) {
                AppChip(
                    text = stringResource(R.string.txns_filter_category_dismiss, currentCategory.orEmpty()),
                    selected = true,
                    onClick = { onFilterChange(state.activeFilter.copy(category = null)) }
                )
            }
            if (isLivingBurn) {
                AppChip(
                    text = stringResource(R.string.txns_filter_living_burn_dismiss),
                    selected = true,
                    onClick = { onFilterChange(state.activeFilter.copy(excludeSavingsAndIgnored = false)) }
                )
            }
            AppChip(
                text = stringResource(R.string.txn_chip_all),
                selected = isAllSelected,
                onClick = onClearFilter
            )
            AppChip(
                text = stringResource(R.string.txn_chip_upi),
                selected = currentAccount.equals("UPI", ignoreCase = true),
                onClick = {
                    val newAcc = if (currentAccount.equals("UPI", ignoreCase = true)) null else "UPI"
                    onFilterChange(state.activeFilter.copy(account = newAcc))
                }
            )
            AppChip(
                text = stringResource(R.string.txn_chip_card),
                selected = currentAccount.equals("Card", ignoreCase = true),
                onClick = {
                    val newAcc = if (currentAccount.equals("Card", ignoreCase = true)) null else "Card"
                    onFilterChange(state.activeFilter.copy(account = newAcc))
                }
            )
            AppChip(
                text = stringResource(R.string.txn_chip_cash),
                selected = currentAccount.equals("Cash", ignoreCase = true),
                onClick = {
                    val newAcc = if (currentAccount.equals("Cash", ignoreCase = true)) null else "Cash"
                    onFilterChange(state.activeFilter.copy(account = newAcc))
                }
            )
            AppChip(
                text = stringResource(R.string.txn_chip_show_void),
                selected = isShowVoid,
                onClick = {
                    onFilterChange(state.activeFilter.copy(showVoid = !isShowVoid))
                }
            )
            AppChip(
                text = stringResource(R.string.txn_chip_needs_review),
                selected = currentStatus.equals("review", ignoreCase = true),
                onClick = {
                    val newStatus = if (currentStatus.equals("review", ignoreCase = true)) null else "review"
                    onFilterChange(state.activeFilter.copy(status = newStatus))
                }
            )
            AppChip(
                text = stringResource(R.string.txn_chip_income),
                selected = currentStatus.equals("income", ignoreCase = true),
                onClick = {
                    val newStatus = if (currentStatus.equals("income", ignoreCase = true)) null else "income"
                    onFilterChange(state.activeFilter.copy(status = newStatus))
                }
            )
            val popularCategories = remember {
                listOf(
                    com.financeautomation.core.constants.CategoryConstants.CATEGORY_FOOD_AND_DRINK,
                    com.financeautomation.core.constants.CategoryConstants.CATEGORY_GROCERIES,
                    com.financeautomation.core.constants.CategoryConstants.CATEGORY_TRANSIT,
                    com.financeautomation.core.constants.CategoryConstants.CATEGORY_SHOPPING
                )
            }
            popularCategories.forEach { cat ->
                if (!cat.equals(currentCategory, ignoreCase = true)) {
                    AppChip(
                        text = cat,
                        selected = false,
                        onClick = {
                            onFilterChange(state.activeFilter.copy(category = cat))
                        }
                    )
                }
            }
        }

        state.categoryBudget?.let { progress ->
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
            com.financeautomation.presentation.components.BudgetBar(progress = progress)
            Text(
                text = androidx.compose.ui.res.stringResource(
                    com.financeautomation.R.string.budget_progress_caption,
                    com.financeautomation.core.utils.formatRupees(progress.spent),
                    com.financeautomation.core.utils.formatRupees(progress.budget)
                ),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    text = formatRupeesAuto(group.dayMyShare),
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
                            items = group.items,
                            key = { it.id }
                        ) { item ->
                            when (item) {
                                is TransactionListItem.ExpenseItem -> {
                                    val txn = item.transaction
                                    val splitText = if (txn.owedToMe > 0.0) stringResource(R.string.transaction_split_indicator, formatRupeesAuto(txn.myShare)) else null
                                    val timeStr = DisplayDateTime.time(txn.timestamp)

                                    SwipeableTransactionRow(
                                        txn = txn,
                                        splitText = splitText,
                                        timeStr = timeStr,
                                        onTransactionClick = onTransactionClick,
                                        onVoid = onVoidTransaction
                                    )
                                }
                                is TransactionListItem.IncomeItem -> {
                                    val inf = item.inflow
                                    val timeStr = DisplayDateTime.time(inf.timestamp)

                                    InflowRow(
                                        source = inf.source,
                                        amount = inf.amount,
                                        inflowType = inf.inflowType,
                                        account = inf.account,
                                        time = timeStr,
                                        status = inf.status,
                                        onClick = { onInflowClick(inf) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTransactionRow(
    txn: Transaction,
    splitText: String?,
    timeStr: String,
    onTransactionClick: (Transaction) -> Unit,
    onVoid: (Transaction) -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onTransactionClick(txn)
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onVoid(txn)
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                else -> Color.Transparent
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Rounded.Edit
                SwipeToDismissBoxValue.EndToStart -> Icons.Rounded.Block
                else -> null
            }
            val iconTint = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onPrimaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
                else -> Color.Transparent
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(FinanceTheme.shapes.card)
                    .background(color)
                    .padding(horizontal = FinanceTheme.spacing.l),
                contentAlignment = alignment
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                            stringResource(R.string.action_edit)
                        } else {
                            stringResource(R.string.action_void)
                        },
                        tint = iconTint,
                        modifier = Modifier.size(FinanceTheme.sizes.iconLarge)
                    )
                }
            }
        },
        modifier = modifier
    ) {
        TransactionRow(
            merchant = txn.merchant.ifBlank { txn.category },
            amount = txn.totalAmount,
            category = txn.category,
            account = txn.account,
            time = timeStr,
            status = txn.status.value,
            splitText = splitText,
            onClick = { onTransactionClick(txn) },
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}

/**
 * Income / Inflow Detail Bottom Sheet Modal.
 * Layout:
 * - Header: Source as title + Inflow type / Status chip
 * - Positive mono amount (+₹Amount)
 * - Details card: Source, Type, Account, Date & Time, Note
 * - Outlined "Void" button with confirm step
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomeDetailSheet(
    inflow: Inflow,
    onDismiss: () -> Unit,
    onVoid: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showConfirmVoid by rememberSaveable { mutableStateOf(false) }

    val displayTitle = inflow.source.ifBlank {
        when (inflow.inflowType.uppercase()) {
            "FRIEND_SETTLEMENT" -> "Friend settlement"
            "SELF_TRANSFER" -> "Self transfer"
            "REFUND" -> "Refund"
            else -> "Income"
        }
    }

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
            val isVoid = inflow.status.equals("VOID", ignoreCase = true)
            val cleanTitle = stripVoidPrefix(displayTitle)

            // Header: Source + Inflow type / Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cleanTitle,
                    style = if (isVoid) {
                        FinanceTheme.typography.titleLarge.copy(textDecoration = TextDecoration.LineThrough)
                    } else {
                        FinanceTheme.typography.titleLarge
                    },
                    color = if (isVoid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                StatusChip(status = if (isVoid) "VOID" else inflowTypeLabel(inflow.inflowType))
            }

            // Big Mono Amount in positive color (or muted if voided)
            Text(
                text = "+${formatRupeesAuto(inflow.amount)}",
                style = MaterialTheme.typography.headlineLarge,
                color = if (isVoid) MaterialTheme.colorScheme.onSurfaceVariant else FinanceTheme.colors.moneyIn
            )

            // Info items: Source, Type, Account, Date & Time, Note
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = FinanceTheme.colors.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                    if (inflow.source.isNotBlank()) {
                        DetailRow(label = stringResource(R.string.txn_detail_source), value = inflow.source)
                    }
                    DetailRow(label = stringResource(R.string.txn_detail_type), value = inflowTypeLabel(inflow.inflowType))
                    DetailRow(label = stringResource(R.string.txn_detail_account), value = inflow.account)
                    DetailRow(label = stringResource(R.string.txn_detail_datetime), value = DisplayDateTime.full(inflow.timestamp))
                    if (inflow.note.isNotBlank()) {
                        DetailRow(label = stringResource(R.string.txn_detail_note), value = inflow.note)
                    }
                }
            }

            if (!inflow.status.equals("VOID", ignoreCase = true)) {
                if (showConfirmVoid) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = FinanceTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                    ) {
                        Text(
                            text = stringResource(R.string.txn_void_entry_confirm),
                            style = FinanceTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ButtonPair(
                            outlinedText = stringResource(R.string.action_cancel),
                            onOutlinedClick = { showConfirmVoid = false },
                            filledText = stringResource(R.string.action_void),
                            onFilledClick = onVoid
                        )
                    }
                } else {
                    OutlinedButton(
                        text = stringResource(R.string.action_void),
                        onClick = { showConfirmVoid = true },
                        modifier = Modifier.fillMaxWidth()
                    )
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
