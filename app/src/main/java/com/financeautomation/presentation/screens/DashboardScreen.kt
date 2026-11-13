package com.financeautomation.presentation.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.FirstRunHintCard
import com.financeautomation.presentation.components.SyncStatusLine
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.CategorySpend
import com.financeautomation.presentation.viewmodel.DashboardUiState
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch

/**
 * Frozen Dashboard screen conforming to UI Round 2 specifications.
 * Order:
 * 1. Single-line quiet sync status line
 * 2. Month switcher
 * 3. KPI row (3 equal-width cards: Living burn, Savings, Income)
 * 4. Needs Review banner (only when count > 0)
 * 5. Balances card (one card, two columns "Owed to me" and "I owe", mono amounts)
 * 6. "Where it went" top 3 categories with thin bars and "See all"
 * 7. Offline banner (when offline)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onNavigateToReview: () -> Unit = {},
    onNavigateToTransactions: (monthOffset: Int, category: String?, type: String?) -> Unit = { _, _, _ -> },
    onNavigateToSettlements: (personName: String, owedToMe: Boolean) -> Unit = { _, _ -> },
    onNavigateToBalances: () -> Unit = { onNavigateToSettlements("", true) },
    onNavigateToSync: () -> Unit = {},
    onNavigateToInsights: () -> Unit = {},
    onAddExpense: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun showExactSnackbar(label: String, amount: Double) {
        if (amount >= 100_000.0) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.dashboard_exact_amount, label, formatRupees(amount, includeDecimals = true))
                )
            }
        }
    }

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
                onSelectMonthOffset = viewModel::setMonthOffset,
                onNavigateToReview = onNavigateToReview,
                onNavigateToTransactions = onNavigateToTransactions,
                onNavigateToBalances = onNavigateToBalances,
                onNavigateToSync = onNavigateToSync,
                onNavigateToInsights = onNavigateToInsights,
                onShowExact = ::showExactSnackbar,
                onDismissHint = viewModel::dismissFirstRunHint,
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
    onSelectMonthOffset: (Int) -> Unit = {},
    onNavigateToReview: () -> Unit,
    onNavigateToTransactions: (monthOffset: Int, category: String?, type: String?) -> Unit,
    onNavigateToBalances: () -> Unit,
    onNavigateToSync: () -> Unit = {},
    onNavigateToInsights: () -> Unit = {},
    onShowExact: (String, Double) -> Unit,
    onDismissHint: () -> Unit = {},
    onAddExpense: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllCategories by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.padding(horizontal = FinanceTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m),
        contentPadding = PaddingValues(
            top = FinanceTheme.spacing.s,
            bottom = FinanceTheme.spacing.bottomFabClearance
        )
    ) {
        // 1. Quiet Sync Status Line
        item {
            SyncStatusLine(
                waitingCount = state.waitingCount,
                failedCount = state.failedCount,
                isSyncing = state.isSyncing,
                isOffline = state.isOffline,
                onFailedClick = onNavigateToReview,
                onClick = onNavigateToSync
            )
        }

        // Offline Banner
        if (state.isOffline) {
            item {
                OfflineBanner()
            }
        }

        // 2. Month Switcher
        item {
            MonthSwitcher(
                monthLabel = state.selectedMonthLabel,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onSelectMonthOffset = onSelectMonthOffset
            )
        }

        // Empty Month State vs Populated Dashboard
        if (state.transactionCount == 0 && state.currentBurn == 0.0) {
            if (!state.isHintDismissed) {
                item {
                    FirstRunHintCard(
                        text = stringResource(R.string.hint_dashboard_first_run),
                        onDismiss = onDismissHint
                    )
                }
            }
            item {
                EmptyState(
                    message = stringResource(R.string.dashboard_empty_message),
                    actionText = stringResource(R.string.dashboard_empty_action),
                    onActionClick = onAddExpense,
                    modifier = Modifier.padding(vertical = FinanceTheme.spacing.xxl)
                )
            }
        } else {
            // 3. KPI row (3 equal-width cards: Living burn, Savings, Income)
            item {
                val heroBurnLabel = stringResource(R.string.dashboard_hero_burn_title)
                val savingsLabel = stringResource(R.string.dashboard_savings)
                val incomeLabel = stringResource(R.string.dashboard_income)

                KpiRow(
                    livingBurn = state.currentBurn,
                    dailyAverage = state.dailyAverage,
                    paceDeltaPercent = state.paceDeltaPercent,
                    savings = state.savings,
                    income = state.income,
                    onLivingBurnClick = {
                        onNavigateToTransactions(state.monthOffset, null, "living_burn")
                    },
                    onLivingBurnLongClick = {
                        onShowExact(heroBurnLabel, state.currentBurn)
                    },
                    onSavingsClick = {
                        onNavigateToTransactions(state.monthOffset, CategoryConstants.CATEGORY_SAVINGS, null)
                    },
                    onSavingsLongClick = {
                        onShowExact(savingsLabel, state.savings)
                    },
                    onIncomeClick = {
                        onNavigateToTransactions(state.monthOffset, null, "income")
                    },
                    onIncomeLongClick = {
                        onShowExact(incomeLabel, state.income)
                    }
                )
            }

            // 4. Needs Review Calm Banner (only when count above zero)
            val reviewCount = if (state.needsReviewTotal > 0) state.needsReviewTotal else state.needsReviewCount
            if (reviewCount > 0) {
                item {
                    NeedsReviewBanner(
                        count = reviewCount,
                        onClick = onNavigateToReview
                    )
                }
            }

            // 5. One Balances Card (two columns "Owed to me" and "I owe")
            item {
                BalancesCard(
                    owedToMe = state.owedToMe,
                    iOwe = state.iOwePeople,
                    onClick = onNavigateToBalances
                )
            }

            // 6. "Where it went" Category Proportional Bars
            val baseCategories = if (showAllCategories) state.allCategories else state.topCategories.take(3)
            if (baseCategories.isNotEmpty()) {
                item {
                    CategoryBreakdownSection(
                        categories = baseCategories,
                        hasMore = state.allCategories.size > 3,
                        isExpanded = showAllCategories,
                        onToggleExpand = { showAllCategories = !showAllCategories },
                        onCategoryClick = { categoryName ->
                            onNavigateToTransactions(state.monthOffset, categoryName, null)
                        },
                        onSeeAllClick = {
                            onNavigateToTransactions(state.monthOffset, null, null)
                        },
                        onInsightsClick = onNavigateToInsights
                    )
                }
            }
        }
    }
}

@Preview(name = "Dashboard Light Normal Font", showBackground = true, fontScale = 1.0f)
@Composable
private fun DashboardPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        DashboardContent(
            state = DashboardUiState(
                currentBurn = 28549.0,
                dailyAverage = 951.63,
                paceDeltaPercent = -12.0,
                savings = 42000.0,
                income = 115000.0,
                needsReviewCount = 3,
                owedToMe = 442.50,
                iOwePeople = 0.0,
                selectedMonthLabel = "November 2026",
                topCategories = listOf(
                    CategorySpend("Food & Drink", 255.0, 0.68f),
                    CategorySpend("Transit", 120.0, 0.32f)
                ),
                allCategories = listOf(
                    CategorySpend("Food & Drink", 255.0, 0.68f),
                    CategorySpend("Transit", 120.0, 0.32f)
                ),
                transactionCount = 2
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onNavigateToReview = {},
            onNavigateToTransactions = { _, _, _ -> },
            onNavigateToBalances = {},
            onShowExact = { _, _ -> },
            onAddExpense = {}
        )
    }
}

@Preview(name = "Dashboard Light Font 1.3", showBackground = true, fontScale = 1.3f)
@Composable
private fun DashboardPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        DashboardContent(
            state = DashboardUiState(
                currentBurn = 28549.0,
                dailyAverage = 951.63,
                paceDeltaPercent = -12.0,
                savings = 42000.0,
                income = 115000.0,
                needsReviewCount = 3,
                owedToMe = 442.50,
                iOwePeople = 0.0,
                selectedMonthLabel = "November 2026",
                topCategories = listOf(
                    CategorySpend("Food & Drink", 255.0, 0.68f),
                    CategorySpend("Transit", 120.0, 0.32f)
                ),
                allCategories = listOf(
                    CategorySpend("Food & Drink", 255.0, 0.68f),
                    CategorySpend("Transit", 120.0, 0.32f)
                ),
                transactionCount = 2
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onNavigateToReview = {},
            onNavigateToTransactions = { _, _, _ -> },
            onNavigateToBalances = {},
            onShowExact = { _, _ -> },
            onAddExpense = {}
        )
    }
}

@Preview(name = "Dashboard Dark Normal Font", showBackground = true, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        DashboardContent(
            state = DashboardUiState(
                currentBurn = 28549.0,
                dailyAverage = 951.63,
                paceDeltaPercent = -12.0,
                savings = 42000.0,
                income = 115000.0,
                needsReviewCount = 3,
                owedToMe = 442.50,
                iOwePeople = 0.0,
                selectedMonthLabel = "November 2026",
                topCategories = listOf(
                    CategorySpend("Food & Drink", 255.0, 0.68f),
                    CategorySpend("Transit", 120.0, 0.32f)
                ),
                allCategories = listOf(
                    CategorySpend("Food & Drink", 255.0, 0.68f),
                    CategorySpend("Transit", 120.0, 0.32f)
                ),
                transactionCount = 2
            ),
            onPreviousMonth = {},
            onNextMonth = {},
            onNavigateToReview = {},
            onNavigateToTransactions = { _, _, _ -> },
            onNavigateToBalances = {},
            onShowExact = { _, _ -> },
            onAddExpense = {}
        )
    }
}
