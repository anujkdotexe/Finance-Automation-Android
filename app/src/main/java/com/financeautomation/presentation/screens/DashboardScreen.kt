package com.financeautomation.presentation.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.domain.model.Transaction
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.SectionTitle
import com.financeautomation.presentation.components.SyncStatusLine
import com.financeautomation.presentation.components.TonalCard
import com.financeautomation.presentation.components.TransactionRow
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.CategorySpend
import com.financeautomation.presentation.viewmodel.DashboardUiState
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import com.financeautomation.presentation.viewmodel.DebtSummary
import com.financeautomation.presentation.viewmodel.QueuedItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rebuilt Calm Utility Dashboard screen matching November 2026 reference specifications.
 * Renders living burn hero metrics, receivables/payables, category distribution, and offline status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onNavigateToReview: () -> Unit = {},
    onNavigateToTransactions: () -> Unit = onNavigateToReview,
    onNavigateToSettlements: (personName: String, owedToMe: Boolean) -> Unit,
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
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                onNavigateToTransactions = onNavigateToTransactions,
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
    onNavigateToTransactions: () -> Unit,
    onNavigateToSettlements: (personName: String, owedToMe: Boolean) -> Unit,
    onAddExpense: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllCategories by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.padding(horizontal = FinanceTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m),
        contentPadding = PaddingValues(top = FinanceTheme.spacing.s)
    ) {
        item {
            // Single-line sync status indicator starts 8dp below the top bar
            SyncStatusLine(
                waitingCount = state.waitingCount,
                failedCount = state.failedCount,
                isSyncing = state.isSyncing,
                isOffline = state.isOffline
            )
        }

        // Offline Banner & Queued Items
        if (state.isOffline) {
            item {
                OfflineSection(queuedItems = state.queuedItems)
            }
        }

        // 1. Month Switcher
        item {
            MonthSwitcher(
                monthLabel = state.selectedMonthLabel,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth
            )
        }

        // Empty State vs Populated Dashboard
        if (state.transactionCount == 0 && state.currentBurn == 0.0) {
            item {
                EmptyState(
                    message = stringResource(R.string.dashboard_empty_message),
                    actionText = stringResource(R.string.dashboard_empty_action),
                    onActionClick = onAddExpense,
                    modifier = Modifier.padding(vertical = FinanceTheme.spacing.xxl)
                )
            }
        } else {
            // 2. Hero KPI Card: Living Burn
            item {
                HeroBurnCard(
                    currentBurn = state.currentBurn,
                    dailyAverage = state.dailyAverage,
                    lastMonthSameDayBurn = state.lastMonthSameDayBurn,
                    paceDeltaPercent = state.paceDeltaPercent,
                    onTrack = state.onTrack
                )
            }

            // 3. Owed to me and I owe cards
            item {
                OwedSummarySection(
                    owedToMe = state.owedToMe,
                    topOwedToMe = state.topOwedToMe,
                    iOwePeople = state.iOwePeople,
                    topIOwe = state.topIOwe,
                    onPersonClick = onNavigateToSettlements
                )
            }

            // 4. Needs Review Action Banner (calm tonal colors, not red)
            if (state.needsReviewTotal > 0 || state.needsReviewCount > 0) {
                val reviewCount = if (state.needsReviewTotal > 0) state.needsReviewTotal else state.needsReviewCount
                item {
                    NeedsReviewBanner(
                        count = reviewCount,
                        onClick = onNavigateToReview
                    )
                }
            }

            // 5. Where It Went (Category Proportional Bars)
            val displayedCategories = if (showAllCategories) state.allCategories else state.topCategories
            if (displayedCategories.isNotEmpty()) {
                item {
                    CategoryBreakdownSection(
                        categories = displayedCategories,
                        hasMore = state.allCategories.size > 5,
                        isExpanded = showAllCategories,
                        onToggleExpand = { showAllCategories = !showAllCategories }
                    )
                }
            }

            // 6. Two Small Cards: Savings and Income
            item {
                SavingsAndIncomeSection(
                    savings = state.savings,
                    income = state.income
                )
            }

            // 7. Recent Transactions (last 3 items)
            if (state.recentTransactions.isNotEmpty()) {
                item {
                    SectionTitle(
                        title = stringResource(R.string.dashboard_recent),
                        actionText = stringResource(R.string.action_see_all),
                        onActionClick = onNavigateToTransactions
                    )
                    TonalCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.recentTransactions.take(3).forEach { txn ->
                                val dateStr = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
                                    .format(Date(txn.timestamp))
                                TransactionRow(
                                    merchant = txn.merchant.ifBlank { txn.category },
                                    amount = txn.totalAmount,
                                    category = txn.category,
                                    account = txn.account,
                                    time = dateStr,
                                    status = txn.status.value,
                                    splitText = if (txn.owedToMe > 0.0) {
                                        "My share ${com.financeautomation.core.utils.formatRupeesAuto(txn.myShare)}, Owed ${com.financeautomation.core.utils.formatRupeesAuto(txn.owedToMe)}"
                                    } else null,
                                    isExpense = true
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(88.dp))
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
                currentBurn = 6549.0,
                dailyAverage = 273.0,
                lastMonthBurn = 7100.0,
                lastMonthSameDayBurn = 7100.0,
                paceDeltaPercent = -7.8,
                onTrack = true,
                owedToMe = 2050.0,
                topOwedToMe = listOf(
                    DebtSummary("Rahul V.", 1400.0),
                    DebtSummary("Priya S.", 650.0)
                ),
                iOwePeople = 1120.0,
                topIOwe = listOf(
                    DebtSummary("Karan M.", 1120.0)
                ),
                needsReviewTotal = 3,
                topCategories = listOf(
                    CategorySpend("Shopping", 1800.0, 0.27f),
                    CategorySpend("Transit", 1490.0, 0.23f),
                    CategorySpend("Groceries", 1200.0, 0.18f),
                    CategorySpend("Food & Drink", 990.0, 0.15f),
                    CategorySpend("Subscriptions", 649.0, 0.10f),
                    CategorySpend("Health", 420.0, 0.06f)
                ),
                savings = 25000.0,
                income = 85000.0,
                transactionCount = 14
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onNavigateToReview = {},
            onNavigateToTransactions = {},
            onNavigateToSettlements = { _, _ -> },
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
                currentBurn = 34820.0,
                dailyAverage = 1240.0,
                lastMonthSameDayBurn = 36000.0,
                paceDeltaPercent = -3.2,
                onTrack = true,
                owedToMe = 1500.0,
                topOwedToMe = listOf(DebtSummary("Aman", 1500.0)),
                iOwePeople = 0.0,
                needsReviewTotal = 0,
                savings = 10000.0,
                income = 95000.0,
                transactionCount = 28
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onNavigateToReview = {},
            onNavigateToTransactions = {},
            onNavigateToSettlements = { _, _ -> },
            onAddExpense = {}
        )
    }
}

@Preview(name = "Font Scale 1.3x", fontScale = 1.3f, showBackground = true)
@Composable
private fun DashboardScreenPreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        DashboardContent(
            state = DashboardUiState(
                selectedMonthLabel = "November 2026",
                currentBurn = 12500.0,
                dailyAverage = 416.0,
                lastMonthSameDayBurn = 13000.0,
                paceDeltaPercent = -3.8,
                onTrack = true,
                owedToMe = 450.0,
                topOwedToMe = listOf(DebtSummary("Neha", 450.0)),
                iOwePeople = 200.0,
                topIOwe = listOf(DebtSummary("Vikram", 200.0)),
                needsReviewTotal = 1,
                savings = 5000.0,
                income = 40000.0,
                transactionCount = 8
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onNavigateToReview = {},
            onNavigateToTransactions = {},
            onNavigateToSettlements = { _, _ -> },
            onAddExpense = {}
        )
    }
}
