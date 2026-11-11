package com.financeautomation.presentation.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.FinanceApplication
import com.financeautomation.R
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.presentation.screens.DashboardScreen
import com.financeautomation.presentation.screens.OnboardingScreen
import com.financeautomation.presentation.screens.ReviewInboxScreen
import com.financeautomation.presentation.screens.SettingsScreen
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import com.financeautomation.presentation.viewmodel.OnboardingViewModel
import com.financeautomation.presentation.viewmodel.ReviewInboxViewModel
import com.financeautomation.presentation.viewmodel.SettingsViewModel
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel
import com.financeautomation.presentation.viewmodel.SetupHealthViewModelFactory
import java.time.ZoneId
import java.time.ZonedDateTime

enum class MainNavDestination(val label: String) {
    INBOX("Needs Review"),
    DASHBOARD("Dashboard"),
    SETTINGS("Settings & Sync")
}

/**
 * Main launcher Activity for Finance Automation.
 * Hosts navigation between ReviewInboxScreen, DashboardScreen, and SettingsScreen.
 */
class MainActivity : ComponentActivity() {

    private val reviewInboxViewModel: ReviewInboxViewModel by viewModels {
        val app = application as FinanceApplication
        ReviewInboxViewModel.provideFactory(
            transactionRepository = app.appContainer.transactionRepository,
            ioDispatcher = app.appContainer.ioDispatcher
        )
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        val app = application as FinanceApplication
        DashboardViewModel.provideFactory(
            transactionRepository = app.appContainer.transactionRepository,
            syncQueueDaoProvider = { app.appContainer.awaitDatabase().syncQueueDao() },
            categoryRepository = app.appContainer.categoryRepository,
            ioDispatcher = app.appContainer.ioDispatcher
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        val app = application as FinanceApplication
        SettingsViewModel.provideFactory(
            preferencesRepository = app.appContainer.preferencesRepository,
            peopleRepository = app.appContainer.peopleRepository,
            transactionRepository = app.appContainer.transactionRepository,
            accountRepository = app.appContainer.accountRepository,
            categoryRepository = app.appContainer.categoryRepository,
            tripTagRepository = app.appContainer.tripTagRepository,
            syncConfigUseCase = app.appContainer.syncConfigUseCase,
            regenerateDashboardUseCase = app.appContainer.regenerateDashboardUseCase,
            googleDriveClient = app.appContainer.googleDriveClient,
            googleSheetsClient = app.appContainer.googleSheetsClient,
            googleAuthTokenProvider = app.appContainer.googleAuthTokenProvider,
            workbookResolver = app.appContainer.workbookResolver,
            ioDispatcher = app.appContainer.ioDispatcher
        )
    }

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        val app = application as FinanceApplication
        OnboardingViewModel.provideFactory(
            googleAuthTokenProvider = app.appContainer.googleAuthTokenProvider,
            workbookResolver = app.appContainer.workbookResolver,
            preferencesRepository = app.appContainer.preferencesRepository,
            ioDispatcher = app.appContainer.ioDispatcher
        )
    }

    private val setupHealthViewModel: SetupHealthViewModel by viewModels {
        val app = application as FinanceApplication
        SetupHealthViewModelFactory(
            context = applicationContext,
            preferencesRepository = app.appContainer.preferencesRepository,
            ioDispatcher = app.appContainer.ioDispatcher
        )
    }

    override fun onResume() {
        super.onResume()
        setupHealthViewModel.refreshChecks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as FinanceApplication
            val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
            val googleEmail by app.appContainer.preferencesRepository.getGoogleAccountEmail().collectAsState(initial = null)
            val spreadsheetId by app.appContainer.preferencesRepository.getSpreadsheetIdForYear(currentYear).collectAsState(initial = null)
            var forceOnboarding by remember { mutableStateOf(false) }

            val needsOnboarding = forceOnboarding || googleEmail.isNullOrBlank() || spreadsheetId.isNullOrBlank()

            FinanceAutomationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (needsOnboarding) {
                        OnboardingScreen(
                            viewModel = onboardingViewModel,
                            onOnboardingFinished = { forceOnboarding = false }
                        )
                    } else {
                        MainScreen(
                            reviewInboxViewModel = reviewInboxViewModel,
                            dashboardViewModel = dashboardViewModel,
                            settingsViewModel = settingsViewModel,
                            setupHealthViewModel = setupHealthViewModel,
                            overlayControllerProvider = { app.appContainer.overlayController },
                            onReRunSetup = {
                                onboardingViewModel.startWorkspaceProvisioning()
                                forceOnboarding = true
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    reviewInboxViewModel: ReviewInboxViewModel,
    dashboardViewModel: DashboardViewModel,
    settingsViewModel: SettingsViewModel,
    setupHealthViewModel: SetupHealthViewModel,
    overlayControllerProvider: () -> OverlayController,
    onReRunSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentDestination by remember { mutableStateOf(MainNavDestination.INBOX) }
    val healthState by setupHealthViewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when (currentDestination) {
                                MainNavDestination.INBOX -> stringResource(R.string.nav_inbox_title)
                                MainNavDestination.DASHBOARD -> stringResource(R.string.nav_dashboard_title)
                                MainNavDestination.SETTINGS -> stringResource(R.string.nav_settings_title)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = when (currentDestination) {
                                MainNavDestination.INBOX -> stringResource(R.string.nav_inbox_subtitle)
                                MainNavDestination.DASHBOARD -> stringResource(R.string.nav_dashboard_subtitle)
                                MainNavDestination.SETTINGS -> stringResource(R.string.nav_settings_subtitle)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = FinanceTheme.spacing.xs
            ) {
                MainNavDestination.values().forEach { destination ->
                    val isSelected = currentDestination == destination
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentDestination = destination },
                        label = {
                            Text(
                                text = destination.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        icon = {
                            if (destination == MainNavDestination.SETTINGS && healthState.hasMissing) {
                                BadgedBox(badge = { Badge() }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Tune,
                                        contentDescription = destination.label,
                                        modifier = Modifier.size(FinanceTheme.sizes.iconLarge)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = when (destination) {
                                        MainNavDestination.INBOX -> Icons.AutoMirrored.Rounded.ReceiptLong
                                        MainNavDestination.DASHBOARD -> Icons.Rounded.Dashboard
                                        MainNavDestination.SETTINGS -> Icons.Rounded.Tune
                                    },
                                    contentDescription = destination.label,
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
            if (currentDestination == MainNavDestination.INBOX) {
                ExtendedFloatingActionButton(
                    onClick = {
                        context.startActivity(Intent(context, ModalBottomSheetActivity::class.java))
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
        when (currentDestination) {
            MainNavDestination.INBOX -> {
                ReviewInboxScreen(
                    reviewInboxViewModel = reviewInboxViewModel,
                    onEditTransaction = { txn ->
                        context.startActivity(Intent(context, ModalBottomSheetActivity::class.java).apply {
                            putExtra(ModalBottomSheetActivity.EXTRA_TRANSACTION_ID, txn.id)
                        })
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            MainNavDestination.DASHBOARD -> {
                DashboardScreen(
                    viewModel = dashboardViewModel,
                    onNavigateToReview = { currentDestination = MainNavDestination.INBOX },
                    onAddExpense = {
                        context.startActivity(Intent(context, ModalBottomSheetActivity::class.java))
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            MainNavDestination.SETTINGS -> {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    setupHealthViewModel = setupHealthViewModel,
                    overlayControllerProvider = overlayControllerProvider,
                    onReRunSetup = onReRunSetup,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
