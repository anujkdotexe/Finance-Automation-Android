package com.financeautomation.presentation.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navDeepLink
import com.financeautomation.R
import com.financeautomation.presentation.components.AccountAvatar
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
import com.financeautomation.presentation.theme.FinanceShapes
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
    const val SUBSCRIPTIONS = "subscriptions"
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
    DrawerDestination(AppRoutes.TRANSACTIONS, "Transactions", Icons.Rounded.Receipt),
    DrawerDestination(AppRoutes.ACCOUNTS, "Accounts", Icons.Rounded.AccountBalance),
    DrawerDestination(AppRoutes.CATEGORIES, "Categories", Icons.Rounded.Category),
    DrawerDestination(AppRoutes.PEOPLE_TRIPS, "People and trips", Icons.Rounded.FlightTakeoff),
    DrawerDestination(AppRoutes.SUBSCRIPTIONS, "Subscriptions", Icons.Rounded.Subscriptions),
    DrawerDestination(AppRoutes.CAPTURE_PERMISSIONS, "Capture and permissions", Icons.Rounded.Security),
    DrawerDestination(AppRoutes.SYNC_BACKUP, "Sync and backup", Icons.Rounded.Sync),
    DrawerDestination(AppRoutes.PREFERENCES, "Preferences", Icons.Rounded.Tune),
    DrawerDestination(AppRoutes.DIAGNOSTICS, "Diagnostics", Icons.Rounded.BugReport, requiresDeveloperMode = true),
    DrawerDestination(AppRoutes.ABOUT, "About", Icons.Rounded.Info)
)

private data class SpeedDialItem(
    val label: String,
    val icon: ImageVector,
    val flow: String,
    val preselectedFriend: String? = null
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
    val showAvatarMenu = currentRoute == AppRoutes.DASHBOARD ||
        currentRoute == AppRoutes.NEEDS_REVIEW ||
        currentRoute == AppRoutes.TRANSACTIONS

    var accountMenuExpanded by remember { mutableStateOf(false) }
    var isSpeedDialOpen by remember { mutableStateOf(false) }

    val speedDialRotation by animateFloatAsState(
        targetValue = if (isSpeedDialOpen) 45f else 0f,
        label = "fab_rotate"
    )

    BackHandler(enabled = isSpeedDialOpen) {
        isSpeedDialOpen = false
    }

    val speedDialActions = listOf(
        SpeedDialItem(stringResource(R.string.speed_dial_expense), Icons.AutoMirrored.Rounded.ReceiptLong, "EXPENSE"),
        SpeedDialItem(stringResource(R.string.speed_dial_friend_settled), Icons.Rounded.CheckCircle, "INCOME"),
        SpeedDialItem(stringResource(R.string.speed_dial_friend_paid), Icons.Rounded.People, "FRIEND_PAID"),
        SpeedDialItem(stringResource(R.string.speed_dial_repaid_friend), Icons.Rounded.Payments, "REPAID"),
        SpeedDialItem(stringResource(R.string.speed_dial_trip_mode), Icons.Rounded.FlightTakeoff, "TRIP"),
        SpeedDialItem(stringResource(R.string.speed_dial_income_refund), Icons.Rounded.Savings, "INCOME")
    )

    fun launchFlow(flow: String, preselectedFriend: String? = null) {
        val authIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val intent = Intent(context, ModalBottomSheetActivity::class.java).apply {
            putExtra(ModalBottomSheetActivity.EXTRA_FLOW, flow)
            putExtra(ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH, authIntent)
            if (!preselectedFriend.isNullOrBlank()) {
                putExtra(ModalBottomSheetActivity.EXTRA_PRESELECT_FRIEND, preselectedFriend)
            }
        }
        context.startActivity(intent)
    }

    val isDebugOrBeta = com.financeautomation.BuildConfig.DEBUG ||
        com.financeautomation.BuildConfig.BUILD_TYPE == "beta"

    val accountEmail = settingsState.googleAccountEmail
    val accountDisplayName = remember(accountEmail) {
        if (!accountEmail.isNullOrBlank()) {
            if (accountEmail.contains("@")) {
                accountEmail.substringBefore("@")
            } else {
                accountEmail
            }
        } else {
            "Authorized User"
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = FinanceTheme.colors.surfaceContainerHigh,
                modifier = Modifier.fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FinanceTheme.spacing.l),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                    ) {
                        AccountAvatar(
                            email = accountEmail
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = accountDisplayName,
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = accountEmail ?: "Local offline storage",
                                style = FinanceTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m))
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

                    DRAWER_ITEMS.forEach { item ->
                        if (item.route == AppRoutes.SUBSCRIPTIONS && !isDebugOrBeta) {
                            return@forEach
                        }
                        if (!item.requiresDeveloperMode || settingsState.developerMode) {
                            val isSelected = currentRoute == item.route
                            NavigationDrawerItem(
                                label = {
                                    Column {
                                        Text(
                                            text = item.label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        if (item.route == AppRoutes.SUBSCRIPTIONS && isDebugOrBeta) {
                                            Text(
                                                text = "Coming in round 4",
                                                style = FinanceTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
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
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                                    AppRoutes.PEOPLE_TRIPS -> "People and trips"
                                    AppRoutes.SUBSCRIPTIONS -> "Subscriptions"
                                    AppRoutes.CAPTURE_PERMISSIONS -> "Capture and permissions"
                                    AppRoutes.SYNC_BACKUP -> "Sync and backup"
                                    AppRoutes.PREFERENCES -> "Preferences"
                                    AppRoutes.DIAGNOSTICS -> "Diagnostics"
                                    AppRoutes.ABOUT -> "About"
                                    else -> "Finance Automation"
                                },
                                style = FinanceTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        navigationIcon = {
                            if (isRootBottomBarRoute) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Menu,
                                        contentDescription = "Open navigation menu",
                                        tint = MaterialTheme.colorScheme.onSurface
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
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        },
                        actions = {
                            if (showAvatarMenu) {
                                Box {
                                    AccountAvatar(
                                        email = settingsState.googleAccountEmail,
                                        onClick = { accountMenuExpanded = true }
                                    )
                                    DropdownMenu(
                                        expanded = accountMenuExpanded,
                                        onDismissRequest = { accountMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = "Google account",
                                                        style = FinanceTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = settingsState.googleAccountEmail ?: "Local offline storage",
                                                        style = FinanceTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            },
                                            onClick = {},
                                            enabled = false
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_sync_backup)) },
                                            onClick = {
                                                accountMenuExpanded = false
                                                navController.navigate(AppRoutes.SYNC_BACKUP)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_sign_out), color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                accountMenuExpanded = false
                                                settingsViewModel.onGoogleSignOut()
                                                onReRunSetup()
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
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
                        FloatingActionButton(
                            onClick = { isSpeedDialOpen = !isSpeedDialOpen },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = if (isSpeedDialOpen) "Close add menu" else "Add",
                                modifier = Modifier
                                    .size(28.dp)
                                    .rotate(speedDialRotation)
                            )
                        }
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
                            onNavigateToTransactions = { navController.navigate(AppRoutes.TRANSACTIONS) },
                            onNavigateToSettlements = { personName, owedToMe ->
                                val extras = FlowAuthGate.mapSettlementToExtras(personName, owedToMe)
                                launchFlow(extras.flow, extras.preselectedFriend)
                            },
                            onAddExpense = { launchFlow("EXPENSE") }
                        )
                    }

                    composable(AppRoutes.NEEDS_REVIEW) {
                        ReviewInboxScreen(
                            reviewInboxViewModel = reviewInboxViewModel,
                            onEditTransaction = { txn ->
                                val authIntent = PendingIntent.getActivity(
                                    context,
                                    0,
                                    Intent(),
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )
                                context.startActivity(Intent(context, ModalBottomSheetActivity::class.java).apply {
                                    putExtra(ModalBottomSheetActivity.EXTRA_TRANSACTION_ID, txn.id)
                                    putExtra(ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH, authIntent)
                                })
                            }
                        )
                    }

                    composable(AppRoutes.TRANSACTIONS) {
                        TransactionsScreen(
                            viewModel = transactionsViewModel,
                            onTransactionClick = { txn ->
                                val authIntent = PendingIntent.getActivity(
                                    context,
                                    0,
                                    Intent(),
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )
                                context.startActivity(Intent(context, ModalBottomSheetActivity::class.java).apply {
                                    putExtra(ModalBottomSheetActivity.EXTRA_TRANSACTION_ID, txn.id)
                                    putExtra(ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH, authIntent)
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

                    composable(AppRoutes.SUBSCRIPTIONS) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.nav_subscriptions_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = FinanceTheme.colors.onSurfaceVariant
                            )
                        }
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

            // Speed Dial Expanded Overlay & Actions
            if (isSpeedDialOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isSpeedDialOpen = false
                        }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 88.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    speedDialActions.forEach { item ->
                        Surface(
                            shape = FinanceShapes.pill,
                            color = FinanceTheme.colors.surfaceContainerLowest,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable {
                                    isSpeedDialOpen = false
                                    launchFlow(item.flow, item.preselectedFriend)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = item.label,
                                    style = FinanceTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(FinanceTheme.colors.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
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
