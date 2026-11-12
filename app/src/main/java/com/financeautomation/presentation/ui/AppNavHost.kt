package com.financeautomation.presentation.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navDeepLink
import com.financeautomation.R
import com.financeautomation.presentation.overlay.FlowAuthGate
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.screens.DashboardScreen
import com.financeautomation.presentation.screens.OnboardingScreen
import com.financeautomation.presentation.screens.ReviewInboxScreen
import com.financeautomation.presentation.screens.TransactionsScreen
import com.financeautomation.presentation.screens.drawer.AboutScreen
import com.financeautomation.presentation.screens.drawer.AccountsScreen
import com.financeautomation.presentation.screens.drawer.CategoriesScreen
import com.financeautomation.presentation.screens.drawer.CapturePermissionsScreen
import com.financeautomation.presentation.screens.drawer.DiagnosticsScreen
import com.financeautomation.presentation.screens.drawer.PeopleTripsScreen
import com.financeautomation.presentation.screens.drawer.PreferencesScreen
import com.financeautomation.presentation.screens.drawer.SyncBackupScreen
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import com.financeautomation.presentation.viewmodel.OnboardingViewModel
import com.financeautomation.presentation.viewmodel.ReviewInboxViewModel
import com.financeautomation.presentation.viewmodel.SettingsViewModel
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel
import com.financeautomation.presentation.viewmodel.TransactionsViewModel
import kotlinx.coroutines.launch

object AppRoutes {
    const val DASHBOARD = "dashboard"
    const val NEEDS_REVIEW = "needsReview"
    const val TRANSACTIONS = "transactions"
    const val ACCOUNTS = "accounts"
    const val CATEGORIES = "categories"
    const val PEOPLE_TRIPS = "peopleTrips"
    const val CAPTURE_PERMISSIONS = "capturePermissions"
    const val SYNC_BACKUP = "syncBackup"
    const val PREFERENCES = "preferences"
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"
    const val ONBOARDING = "onboarding"
}

private data class DrawerDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val requiresDeveloperMode: Boolean = false
)

private val DRAWER_ITEMS = listOf(
    DrawerDestination(AppRoutes.DASHBOARD, "Dashboard", Icons.Rounded.Dashboard),
    DrawerDestination(AppRoutes.NEEDS_REVIEW, "Needs Review", Icons.AutoMirrored.Rounded.ReceiptLong),
    DrawerDestination(AppRoutes.TRANSACTIONS, "Transactions", Icons.Rounded.Receipt),
    DrawerDestination(AppRoutes.ACCOUNTS, "Accounts", Icons.Rounded.AccountBalance),
    DrawerDestination(AppRoutes.CATEGORIES, "Categories", Icons.Rounded.Category),
    DrawerDestination(AppRoutes.PEOPLE_TRIPS, "People & Trips", Icons.Rounded.FlightTakeoff),
    DrawerDestination(AppRoutes.CAPTURE_PERMISSIONS, "Capture & Permissions", Icons.Rounded.Security),
    DrawerDestination(AppRoutes.SYNC_BACKUP, "Sync & Backup", Icons.Rounded.Sync),
    DrawerDestination(AppRoutes.PREFERENCES, "Preferences", Icons.Rounded.Tune),
    DrawerDestination(AppRoutes.DIAGNOSTICS, "Diagnostics", Icons.Rounded.BugReport, requiresDeveloperMode = true),
    DrawerDestination(AppRoutes.ABOUT, "About", Icons.Rounded.Info)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    navController: NavHostController,
    dashboardViewModel: DashboardViewModel,
    reviewInboxViewModel: ReviewInboxViewModel,
    transactionsViewModel: TransactionsViewModel,
    settingsViewModel: SettingsViewModel,
    setupHealthViewModel: SetupHealthViewModel,
    onboardingViewModel: OnboardingViewModel,
    overlayControllerProvider: () -> OverlayController,
    onReRunSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppRoutes.DASHBOARD

    val settingsState by settingsViewModel.uiState.collectAsState()
    val dashboardState by dashboardViewModel.uiState.collectAsState()

    val isRootBottomBarRoute = currentRoute == AppRoutes.DASHBOARD || currentRoute == AppRoutes.NEEDS_REVIEW

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = FinanceTheme.colors.surface,
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Column(modifier = Modifier.padding(FinanceTheme.spacing.l)) {
                    Text(
                        text = "Finance Automation",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = settingsState.googleAccountEmail ?: "Local offline storage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

                DRAWER_ITEMS.forEach { item ->
                    if (!item.requiresDeveloperMode || settingsState.developerMode) {
                        val isSelected = currentRoute == item.route
                        NavigationDrawerItem(
                            label = { Text(item.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                            icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                            selected = isSelected,
                            onClick = {
                                scope.launch { drawerState.close() }
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(AppRoutes.DASHBOARD) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentRoute) {
                                AppRoutes.DASHBOARD -> stringResource(R.string.nav_dashboard_title)
                                AppRoutes.NEEDS_REVIEW -> stringResource(R.string.nav_inbox_title)
                                AppRoutes.TRANSACTIONS -> "Transactions"
                                AppRoutes.ACCOUNTS -> "Accounts"
                                AppRoutes.CATEGORIES -> "Categories"
                                AppRoutes.PEOPLE_TRIPS -> "People & Trips"
                                AppRoutes.CAPTURE_PERMISSIONS -> "Capture & Permissions"
                                AppRoutes.SYNC_BACKUP -> "Sync & Backup"
                                AppRoutes.PREFERENCES -> "Preferences"
                                AppRoutes.DIAGNOSTICS -> "Diagnostics"
                                AppRoutes.ABOUT -> "About"
                                else -> "Finance Automation"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    navigationIcon = {
                        if (isRootBottomBarRoute) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Rounded.Menu,
                                    contentDescription = "Open navigation menu",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                if (!navController.popBackStack()) {
                                    navController.navigate(AppRoutes.DASHBOARD)
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Navigate back",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                if (isRootBottomBarRoute) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = FinanceTheme.spacing.xs
                    ) {
                        val isDashboardSelected = currentRoute == AppRoutes.DASHBOARD
                        NavigationBarItem(
                            selected = isDashboardSelected,
                            onClick = {
                                if (!isDashboardSelected) {
                                    navController.navigate(AppRoutes.DASHBOARD) {
                                        popUpTo(AppRoutes.DASHBOARD) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            label = {
                                Text(
                                    text = "Dashboard",
                                    fontWeight = if (isDashboardSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.Dashboard,
                                    contentDescription = "Dashboard",
                                    modifier = Modifier.size(FinanceTheme.sizes.iconLarge)
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )

                        val isInboxSelected = currentRoute == AppRoutes.NEEDS_REVIEW
                        NavigationBarItem(
                            selected = isInboxSelected,
                            onClick = {
                                if (!isInboxSelected) {
                                    navController.navigate(AppRoutes.NEEDS_REVIEW) {
                                        popUpTo(AppRoutes.DASHBOARD) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            label = {
                                Text(
                                    text = "Needs Review",
                                    fontWeight = if (isInboxSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            icon = {
                                if (dashboardState.needsReviewTotal > 0) {
                                    BadgedBox(badge = {
                                        Badge {
                                            Text(dashboardState.needsReviewTotal.toString())
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
                                            contentDescription = "Needs Review",
                                            modifier = Modifier.size(FinanceTheme.sizes.iconLarge)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
                                        contentDescription = "Needs Review",
                                        modifier = Modifier.size(FinanceTheme.sizes.iconLarge)
                                    )
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            },
            floatingActionButton = {
                if (isRootBottomBarRoute) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            val authIntent = android.app.PendingIntent.getActivity(
                                context,
                                0,
                                Intent(),
                                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            context.startActivity(Intent(context, ModalBottomSheetActivity::class.java).apply {
                                putExtra(ModalBottomSheetActivity.EXTRA_FLOW, "MENU")
                                putExtra(ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH, authIntent)
                            })
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.quick_add_tile_label)
                            )
                        },
                        text = {
                            Text(
                                text = stringResource(R.string.quick_add_tile_label),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                }
            },
            modifier = modifier
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = AppRoutes.DASHBOARD,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(AppRoutes.DASHBOARD) {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onNavigateToReview = { navController.navigate(AppRoutes.NEEDS_REVIEW) },
                        onNavigateToSettlements = { personName, owedToMe ->
                            val extras = FlowAuthGate.mapSettlementToExtras(personName, owedToMe)
                            val authIntent = android.app.PendingIntent.getActivity(
                                context,
                                0,
                                Intent(),
                                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            val intent = Intent(context, ModalBottomSheetActivity::class.java).apply {
                                putExtra(ModalBottomSheetActivity.EXTRA_FLOW, extras.flow)
                                putExtra(ModalBottomSheetActivity.EXTRA_PRESELECT_FRIEND, extras.preselectedFriend)
                                putExtra(ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH, authIntent)
                            }
                            context.startActivity(intent)
                        },
                        onAddExpense = {
                            context.startActivity(Intent(context, ModalBottomSheetActivity::class.java))
                        }
                    )
                }

                composable(AppRoutes.NEEDS_REVIEW) {
                    ReviewInboxScreen(
                        reviewInboxViewModel = reviewInboxViewModel,
                        onEditTransaction = { txn ->
                            context.startActivity(Intent(context, ModalBottomSheetActivity::class.java).apply {
                                putExtra(ModalBottomSheetActivity.EXTRA_TRANSACTION_ID, txn.id)
                            })
                        }
                    )
                }

                composable(AppRoutes.TRANSACTIONS) {
                    TransactionsScreen(
                        viewModel = transactionsViewModel,
                        onTransactionClick = { txn ->
                            context.startActivity(Intent(context, ModalBottomSheetActivity::class.java).apply {
                                putExtra(ModalBottomSheetActivity.EXTRA_TRANSACTION_ID, txn.id)
                            })
                        }
                    )
                }

                composable(AppRoutes.ACCOUNTS) {
                    AccountsScreen(
                        viewModel = settingsViewModel,
                        state = settingsState,
                        onReRunSetup = onReRunSetup
                    )
                }

                composable(AppRoutes.CATEGORIES) {
                    CategoriesScreen(
                        viewModel = settingsViewModel,
                        state = settingsState
                    )
                }

                composable(AppRoutes.PEOPLE_TRIPS) {
                    PeopleTripsScreen(
                        viewModel = settingsViewModel,
                        state = settingsState
                    )
                }

                composable(
                    route = AppRoutes.CAPTURE_PERMISSIONS,
                    deepLinks = listOf(navDeepLink { uriPattern = "financeautomation://route/capturePermissions" })
                ) {
                    CapturePermissionsScreen(
                        state = settingsState,
                        setupHealthViewModel = setupHealthViewModel,
                        overlayControllerProvider = overlayControllerProvider,
                        onReRunSetup = onReRunSetup
                    )
                }

                composable(
                    route = AppRoutes.SYNC_BACKUP,
                    deepLinks = listOf(navDeepLink { uriPattern = "financeautomation://route/syncBackup" })
                ) {
                    SyncBackupScreen(
                        viewModel = settingsViewModel,
                        state = settingsState
                    )
                }

                composable(AppRoutes.PREFERENCES) {
                    PreferencesScreen(
                        viewModel = settingsViewModel,
                        state = settingsState
                    )
                }

                composable(AppRoutes.DIAGNOSTICS) {
                    DiagnosticsScreen(state = settingsState)
                }

                composable(AppRoutes.ABOUT) {
                    AboutScreen(
                        viewModel = settingsViewModel,
                        state = settingsState
                    )
                }

                composable(AppRoutes.ONBOARDING) {
                    OnboardingScreen(
                        viewModel = onboardingViewModel,
                        onOnboardingFinished = { navController.navigate(AppRoutes.DASHBOARD) }
                    )
                }
            }
        }
    }
}

