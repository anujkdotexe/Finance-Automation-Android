package com.financeautomation.presentation.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.presentation.components.AmountTone
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.KpiCard
import com.financeautomation.presentation.components.SectionHeader
import com.financeautomation.presentation.components.TransactionRow
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.CategorySpend
import com.financeautomation.presentation.viewmodel.DashboardUiState
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import com.financeautomation.presentation.viewmodel.DebtSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rebuilt Calm Utility Home Screen per Design Guide 5.1.
 * Stateless composable displaying living burn, receivables/payables, category bars, and recent transactions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onNavigateToReview: () -> Unit = {},
    onNavigateToSettlements: () -> Unit = {},
    onAddExpense: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.syncMessage, state.errorMessage) {
        state.syncMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = FinanceTheme.colors.surface,
        modifier = modifier
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { viewModel.syncNow(context) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            DashboardContent(
                state = state,
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onNavigateToReview = onNavigateToReview,
                onNavigateToSettlements = onNavigateToSettlements,
                onAddExpense = onAddExpense,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Pure stateless layout for Dashboard content.
 */
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onNavigateToReview: () -> Unit,
    onNavigateToSettlements: () -> Unit,
    onAddExpense: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllCategories by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.padding(horizontal = FinanceTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        item {
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            MonthHeader(
                monthLabel = state.selectedMonthLabel,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                syncQueueCount = state.syncQueueItems.size,
                isSyncing = state.isSyncing
            )
        }

        if (state.transactionCount == 0 && state.currentBurn == 0.0) {
            item {
                EmptyState(
                    message = "Nothing logged yet. Pay with UPI and the prompt will appear, or tap Add expense.",
                    actionText = "Add expense",
                    onActionClick = onAddExpense,
                    modifier = Modifier.padding(vertical = FinanceTheme.spacing.xxl)
                )
            }
        } else {
            // 1. Hero Living Burn Card
            item {
                HeroBurnCard(
                    currentBurn = state.currentBurn,
                    dailyAverage = state.dailyAverage,
                    lastMonthBurn = state.lastMonthBurn
                )
            }

            // 2. Side-by-Side Owed Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    OwedSummaryCard(
                        title = "Owed to me",
                        amount = state.owedToMe,
                        tone = AmountTone.MONEY_IN,
                        topFriends = state.topOwedToMe,
                        onClick = onNavigateToSettlements,
                        modifier = Modifier.weight(1f)
                    )
                    OwedSummaryCard(
                        title = "I owe",
                        amount = state.iOwePeople,
                        tone = AmountTone.MONEY_OWED,
                        topFriends = state.topIOwe,
                        onClick = onNavigateToSettlements,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Needs Review Chip Row
            if (state.needsReviewCount > 0) {
                item {
                    NeedsReviewBanner(
                        count = state.needsReviewCount,
                        onClick = onNavigateToReview
                    )
                }
            }

            // 4. Where It Went (Category Proportional Bars)
            val displayedCategories = if (showAllCategories) state.allCategories else state.topCategories
            if (displayedCategories.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Where it went",
                        actionText = if (state.allCategories.size > 5) {
                            if (showAllCategories) "Show less" else "See all"
                        } else null,
                        onActionClick = { showAllCategories = !showAllCategories }
                    )
                }

                items(displayedCategories) { item ->
                    CategoryBarRow(item = item)
                }
            }

            // 5. Recent Transactions
            if (state.recentTransactions.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Recent",
                        actionText = "See all",
                        onActionClick = onNavigateToReview
                    )
                }

                items(state.recentTransactions) { txn ->
                    TransactionRow(
                        description = txn.merchant.ifBlank { txn.category },
                        dateText = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(txn.timestamp)),
                        account = txn.account,
                        amount = txn.totalAmount,
                        status = txn.status.value
                    )
                }
            }

            // 6. Below the Fold: Savings and Income
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    KpiCard(
                        label = "Savings",
                        amount = state.savings,
                        tone = AmountTone.INFO,
                        caption = "SIPs and investments",
                        modifier = Modifier.weight(1f)
                    )
                    KpiCard(
                        label = "Income",
                        amount = state.income,
                        tone = AmountTone.MONEY_IN,
                        caption = "Total inflows",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun DashboardScreenPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        DashboardContent(
            state = DashboardUiState(
                selectedMonthLabel = "November 2026",
                currentBurn = 42350.0,
                dailyAverage = 1411.0,
                lastMonthBurn = 38900.0,
                owedToMe = 3200.0,
                topOwedToMe = listOf(
                    DebtSummary("Aditya", 1800.0),
                    DebtSummary("Rohit", 1400.0)
                ),
                needsReviewCount = 2,
                topCategories = listOf(
                    CategorySpend("Food & Drink", 18500.0, 0.43f),
                    CategorySpend("Groceries", 9200.0, 0.22f),
                    CategorySpend("Transit", 4500.0, 0.11f)
                ),
                savings = 25000.0,
                income = 120000.0,
                transactionCount = 15
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onNavigateToReview = {},
            onNavigateToSettlements = {},
            onAddExpense = {}
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun DashboardScreenPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        DashboardContent(
            state = DashboardUiState(
                selectedMonthLabel = "November 2026",
                currentBurn = 42350.0,
                dailyAverage = 1411.0,
                lastMonthBurn = 38900.0,
                owedToMe = 3200.0,
                needsReviewCount = 0,
                topCategories = listOf(
                    CategorySpend("Food & Drink", 18500.0, 0.43f)
                ),
                transactionCount = 5
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onNavigateToReview = {},
            onNavigateToSettlements = {},
            onAddExpense = {}
        )
    }
}
