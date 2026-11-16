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
import androidx.compose.material.icons.rounded.BarChart
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.financeautomation.R
import com.financeautomation.presentation.components.AccountAvatar
import com.financeautomation.presentation.overlay.FlowAuthGate
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.screens.DashboardScreen
import com.financeautomation.presentation.screens.ErrorApiNotEnabledScreen
import com.financeautomation.presentation.screens.ErrorPermissionNotGrantedScreen
import com.financeautomation.presentation.screens.ErrorRateLimitedScreen
import com.financeautomation.presentation.screens.OnboardingScreen
import com.financeautomation.presentation.screens.ReviewInboxScreen
import com.financeautomation.presentation.screens.TransactionsScreen
import com.financeautomation.presentation.screens.drawer.AboutScreen
import com.financeautomation.presentation.screens.drawer.AccountsScreen
import com.financeautomation.presentation.screens.drawer.CategoriesScreen
import com.financeautomation.presentation.screens.drawer.CapturePermissionsScreen
import com.financeautomation.presentation.screens.drawer.DiagnosticsScreen
import com.financeautomation.presentation.screens.drawer.OpenSourceLicencesScreen
import com.financeautomation.presentation.screens.drawer.PrivacyPolicyScreen
import com.financeautomation.presentation.screens.drawer.PeopleTripsScreen
import com.financeautomation.presentation.screens.drawer.PreferencesScreen
import com.financeautomation.presentation.screens.drawer.SubscriptionsScreen
import com.financeautomation.presentation.screens.drawer.SyncBackupScreen
import com.financeautomation.presentation.screens.onboarding.NotificationAccessScreen
import com.financeautomation.presentation.theme.FinanceAlphas
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import com.financeautomation.presentation.viewmodel.OnboardingViewModel
import com.financeautomation.presentation.viewmodel.ReviewInboxViewModel
import com.financeautomation.presentation.viewmodel.SettingsViewModel
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel
import com.financeautomation.presentation.viewmodel.SubscriptionsViewModel
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
    const val INSIGHTS = "insights"
    const val CAPTURE_PERMISSIONS = "capturePermissions"
    const val SYNC_BACKUP = "syncBackup"
    const val PREFERENCES = "preferences"
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"
    const val PRIVACY_POLICY = "privacyPolicy"
    const val OPEN_SOURCE_LICENCES = "openSourceLicences"
    const val ONBOARDING = "onboarding"
    const val NOTIFICATION_ACCESS = "notificationAccess"
    const val ERROR_API_NOT_ENABLED = "errorApiNotEnabled"
    const val ERROR_PERMISSION_NOT_GRANTED = "errorPermissionNotGranted"
    const val ERROR_RATE_LIMITED = "errorRateLimited"
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
    DrawerDestination(AppRoutes.INSIGHTS, "Insights", Icons.Rounded.BarChart),
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

/**
 * Goes to the Dashboard and clears everything above it. The back stack is reset to the Dashboard alone, with no saved
 * state restored, so the action works from any screen and from any leftover back stack.
 */
internal fun NavHostController.goToDashboard() {
    if (popBackStack(AppRoutes.DASHBOARD, false)) return
    navigate(AppRoutes.DASHBOARD) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/** Opens [route] without stacking a second copy of it when it is already on top. */
internal fun NavHostController.navigateTop(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Back arrow for screens that can be entered from outside (a notification or a shortcut): when nothing is below the
 * screen, go to the Dashboard instead of popping the host empty.
 */
internal fun NavHostController.goBackOrDashboard() {
    if (previousBackStackEntry != null) popBackStack() else goToDashboard()
}

/** Opens a route that arrived from outside the app, keeping the Dashboard below it and never stacking duplicates. */
internal fun NavHostController.navigateToRoute(route: String) {
    when (route.substringBefore("?")) {
        AppRoutes.DASHBOARD -> goToDashboard()
        AppRoutes.NEEDS_REVIEW -> goToNeedsReview()
        else -> navigate(route) {
            popUpTo(AppRoutes.DASHBOARD)
            launchSingleTop = true
        }
    }
}

/** The Transactions route with its arguments percent encoded, because category and type come from user text. */
internal fun transactionsRoute(monthOffset: Int, category: String?, type: String?): String = buildString {
    fun enc(v: String) = java.net.URLEncoder.encode(v.trim(), "UTF-8").replace("+", "%20")
    append(AppRoutes.TRANSACTIONS)
    append("?monthOffset=").append(monthOffset)
    if (!category.isNullOrBlank()) append("&category=").append(enc(category))
    if (!type.isNullOrBlank()) append("&type=").append(enc(type))
}

/** Opens the Needs Review tab on top of the Dashboard without restoring any saved back stack state. */
internal fun NavHostController.goToNeedsReview() {
    navigate(AppRoutes.NEEDS_REVIEW) {
        popUpTo(AppRoutes.DASHBOARD)
        launchSingleTop = true
    }
}

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
    subscriptionsViewModel: SubscriptionsViewModel? = null,
    onReRunSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppRoutes.DASHBOARD
    val baseRoute = currentRoute.substringBefore('?')

    val settingsState by settingsViewModel.uiState.collectAsState()
    val dashboardState by dashboardViewModel.uiState.collectAsState()
    val reviewInboxState by reviewInboxViewModel.uiState.collectAsState()

    val isRootBottomBarRoute = baseRoute == AppRoutes.DASHBOARD || baseRoute == AppRoutes.NEEDS_REVIEW
    val showAvatarMenu = baseRoute == AppRoutes.DASHBOARD ||
        baseRoute == AppRoutes.NEEDS_REVIEW ||
        baseRoute == AppRoutes.TRANSACTIONS

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
                        if (!item.requiresDeveloperMode || settingsState.developerMode) {
                            val isSelected = baseRoute == item.route
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = item.label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                                selected = isSelected,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    if (baseRoute != item.route) {
                                        navController.navigate(item.route) {
                                            popUpTo(AppRoutes.DASHBOARD)
                                            launchSingleTop = true
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
            val fullScreenFlowRoutes = remember {
                setOf(
                    AppRoutes.ONBOARDING,
                    AppRoutes.NOTIFICATION_ACCESS,
                    AppRoutes.ERROR_API_NOT_ENABLED,
                    AppRoutes.ERROR_PERMISSION_NOT_GRANTED,
                    AppRoutes.ERROR_RATE_LIMITED
                )
            }

            Scaffold(
                topBar = {
                    if (baseRoute !in fullScreenFlowRoutes) {
                        TopAppBar(
                            title = {
                                Text(
                                    text = when (baseRoute) {
                                        AppRoutes.DASHBOARD -> stringResource(R.string.nav_dashboard_title)
                                        AppRoutes.NEEDS_REVIEW -> stringResource(R.string.nav_inbox_title)
                                        AppRoutes.TRANSACTIONS -> stringResource(R.string.nav_transactions_title)
                                        AppRoutes.ACCOUNTS -> stringResource(R.string.nav_accounts_title)
                                        AppRoutes.CATEGORIES -> stringResource(R.string.nav_categories_title)
                                        AppRoutes.PEOPLE_TRIPS -> stringResource(R.string.nav_people_trips_title)
                                        AppRoutes.SUBSCRIPTIONS -> stringResource(R.string.nav_subscriptions_title)
                                        AppRoutes.INSIGHTS -> stringResource(R.string.insights_title)
                                        AppRoutes.CAPTURE_PERMISSIONS -> stringResource(R.string.nav_capture_permissions_title)
                                        AppRoutes.SYNC_BACKUP -> stringResource(R.string.nav_sync_backup_title)
                                        AppRoutes.PREFERENCES -> stringResource(R.string.nav_preferences_title)
                                        AppRoutes.DIAGNOSTICS -> stringResource(R.string.nav_diagnostics_title)
                                        AppRoutes.ABOUT -> stringResource(R.string.nav_about_title)
                                        else -> stringResource(R.string.app_name)
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
                                            contentDescription = stringResource(R.string.cd_open_drawer),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                } else {
                                    IconButton(onClick = {
                                        navController.goBackOrDashboard()
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = stringResource(R.string.cd_back),
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
                                                    navController.navigateTop(AppRoutes.SYNC_BACKUP)
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
                    }
                },
                bottomBar = {
                    if (isRootBottomBarRoute) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = FinanceTheme.spacing.xs
                        ) {
                            val isDashboardSelected = baseRoute == AppRoutes.DASHBOARD
                            NavigationBarItem(
                                selected = isDashboardSelected,
                                onClick = {
                                    if (!isDashboardSelected) {
                                        navController.goToDashboard()
                                    }
                                },
                                label = {
                                    Text(
                                        text = stringResource(R.string.nav_dashboard_title),
                                        fontWeight = if (isDashboardSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Dashboard,
                                        contentDescription = stringResource(R.string.nav_dashboard_title),
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

                            val isInboxSelected = baseRoute == AppRoutes.NEEDS_REVIEW
                            NavigationBarItem(
                                selected = isInboxSelected,
                                onClick = {
                                    if (!isInboxSelected) {
                                        navController.goToNeedsReview()
                                    }
                                },
                                label = {
                                    Text(
                                        text = stringResource(R.string.nav_inbox_title),
                                        fontWeight = if (isInboxSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                icon = {
                                    val reviewBadgeCount = maxOf(
                                        reviewInboxState.totalCount,
                                        dashboardState.needsReviewTotal
                                    )
                                    if (reviewBadgeCount > 0) {
                                        BadgedBox(badge = {
                                            Badge {
                                                Text(reviewBadgeCount.toString())
                                            }
                                        }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
                                                contentDescription = stringResource(R.string.nav_inbox_title),
                                                modifier = Modifier.size(FinanceTheme.sizes.iconLarge)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
                                            contentDescription = stringResource(R.string.nav_inbox_title),
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
                            modifier = Modifier.size(FinanceTheme.sizes.fabSize)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = if (isSpeedDialOpen) "Close add menu" else "Add",
                                modifier = Modifier
                                    .size(FinanceTheme.sizes.icon28)
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
                            onNavigateToReview = { navController.goToNeedsReview() },
                            onNavigateToTransactions = { monthOffset, category, type ->
                                navController.navigateTop(transactionsRoute(monthOffset, category, type))
                            },
                            onNavigateToSettlements = { personName, owedToMe ->
                                val extras = FlowAuthGate.mapSettlementToExtras(personName, owedToMe)
                                launchFlow(extras.flow, extras.preselectedFriend)
                            },
                            onNavigateToBalances = {
                                navController.navigateTop(AppRoutes.PEOPLE_TRIPS)
                            },
                            onNavigateToSync = {
                                navController.navigateTop(AppRoutes.SYNC_BACKUP)
                            },
                            onNavigateToInsights = {
                                navController.navigateTop(AppRoutes.INSIGHTS)
                            },
                            onAddExpense = { launchFlow("EXPENSE") }
                        )
                    }

                    composable(AppRoutes.NEEDS_REVIEW) {
                        ReviewInboxScreen(
                            reviewInboxViewModel = reviewInboxViewModel,
                            onNavigateToDashboard = { navController.goToDashboard() },
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

                    composable(
                        route = "${AppRoutes.TRANSACTIONS}?monthOffset={monthOffset}&category={category}&type={type}",
                        arguments = listOf(
                            navArgument("monthOffset") {
                                type = NavType.IntType
                                defaultValue = 0
                            },
                            navArgument("category") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("type") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStackEntry ->
                        val monthOffset = backStackEntry.arguments?.getInt("monthOffset") ?: 0
                        val category = backStackEntry.arguments?.getString("category")
                        val type = backStackEntry.arguments?.getString("type")

                        LaunchedEffect(backStackEntry.id) {
                            transactionsViewModel.setMonthOffset(monthOffset)
                            if (!category.isNullOrBlank()) {
                                transactionsViewModel.setCategoryFilter(category)
                            } else if (!type.isNullOrBlank()) {
                                transactionsViewModel.setTypeFilter(type)
                            } else {
                                transactionsViewModel.clearFilter()
                            }
                        }

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
                            },
                            onNavigateToSync = {
                                navController.navigateTop(AppRoutes.SYNC_BACKUP)
                            },
                            onAddExpense = { launchFlow("EXPENSE") }
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
                            state = settingsState,
                            onStartTrip = { launchFlow("TRIP") }
                        )
                    }

                    composable(AppRoutes.SUBSCRIPTIONS) {
                        if (subscriptionsViewModel != null) {
                            SubscriptionsScreen(
                                viewModel = subscriptionsViewModel,
                                accounts = settingsState.accounts.map { it.name }
                            )
                        } else {
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
                    }

                    composable(AppRoutes.INSIGHTS) {
                        val container = (context.applicationContext as com.financeautomation.FinanceApplication).appContainer
                        val otherLabel = stringResource(R.string.insights_other)
                        val insightsViewModel: com.financeautomation.presentation.viewmodel.InsightsViewModel =
                            androidx.lifecycle.viewmodel.compose.viewModel(
                                factory = com.financeautomation.presentation.viewmodel.InsightsViewModel.provideFactory(
                                    transactionRepository = container.transactionRepository,
                                    categoryRepository = container.categoryRepository,
                                    inflowRepository = container.inflowRepository,
                                    otherLabel = otherLabel
                                )
                            )
                        com.financeautomation.presentation.screens.InsightsScreen(
                            viewModel = insightsViewModel,
                            onOpenCategory = { month, category ->
                                // Open Transactions on that month, filtered to the category
                                transactionsViewModel.clearSearch()
                                transactionsViewModel.currentMonth()
                                repeat(insightsViewModel.monthsBeforeCurrent(month)) { transactionsViewModel.previousMonth() }
                                transactionsViewModel.setFilter(category = category)
                                navController.navigate(AppRoutes.TRANSACTIONS) { launchSingleTop = true }
                            }
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
                            onReRunSetup = onReRunSetup,
                            onNavigateGoogleSignIn = { navController.navigateTop(AppRoutes.SYNC_BACKUP) }
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
                        DiagnosticsScreen(
                            state = settingsState,
                            onSyncNow = settingsViewModel::onSyncNow
                        )
                    }

                    composable(AppRoutes.ABOUT) {
                        AboutScreen(
                            viewModel = settingsViewModel,
                            state = settingsState,
                            onNavigateToPrivacy = { navController.navigateTop(AppRoutes.PRIVACY_POLICY) },
                            onNavigateToLicences = { navController.navigateTop(AppRoutes.OPEN_SOURCE_LICENCES) }
                        )
                    }

                    composable(AppRoutes.PRIVACY_POLICY) {
                        PrivacyPolicyScreen(
                            onNavigateBack = { navController.goBackOrDashboard() }
                        )
                    }

                    composable(AppRoutes.OPEN_SOURCE_LICENCES) {
                        OpenSourceLicencesScreen(
                            onNavigateBack = { navController.goBackOrDashboard() }
                        )
                    }

                    composable(AppRoutes.ONBOARDING) {
                        OnboardingScreen(
                            viewModel = onboardingViewModel,
                            onOnboardingFinished = { navController.goToDashboard() }
                        )
                    }

                    composable(AppRoutes.NOTIFICATION_ACCESS) {
                        NotificationAccessScreen(
                            onNavigateBack = { navController.goBackOrDashboard() },
                            onSkip = { navController.goToDashboard() }
                        )
                    }

                    composable(AppRoutes.ERROR_API_NOT_ENABLED) {
                        ErrorApiNotEnabledScreen(
                            onNavigateBack = { navController.goBackOrDashboard() },
                            onRetry = { navController.goToDashboard() }
                        )
                    }

                    composable(AppRoutes.ERROR_PERMISSION_NOT_GRANTED) {
                        ErrorPermissionNotGrantedScreen(
                            onNavigateBack = { navController.goBackOrDashboard() },
                            onContinueManually = { navController.goToDashboard() }
                        )
                    }

                    composable(AppRoutes.ERROR_RATE_LIMITED) {
                        ErrorRateLimitedScreen(
                            onNavigateBack = { navController.goBackOrDashboard() },
                            onRetry = { navController.goToDashboard() }
                        )
                    }
                }
            }

            // Speed Dial Expanded Overlay & Actions
            if (isSpeedDialOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = FinanceAlphas.scrim))
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
                        .padding(end = FinanceTheme.spacing.l, bottom = FinanceTheme.sizes.fabSize + FinanceTheme.spacing.xxl),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    speedDialActions.forEach { item ->
                        Surface(
                            shape = FinanceShapes.pill,
                            color = FinanceTheme.colors.surfaceContainerLowest,
                            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                            modifier = Modifier
                                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                                .clickable {
                                    isSpeedDialOpen = false
                                    launchFlow(item.flow, item.preselectedFriend)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.s),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.compact)
                            ) {
                                Text(
                                    text = item.label,
                                    style = FinanceTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Box(
                                    modifier = Modifier
                                        .size(FinanceTheme.sizes.icon32)
                                        .clip(CircleShape)
                                        .background(FinanceTheme.colors.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(FinanceTheme.sizes.icon18)
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
