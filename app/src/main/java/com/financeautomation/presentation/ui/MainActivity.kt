package com.financeautomation.presentation.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.financeautomation.FinanceApplication
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.presentation.screens.OnboardingScreen
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.viewmodel.DashboardViewModel
import com.financeautomation.presentation.viewmodel.OnboardingViewModel
import com.financeautomation.presentation.viewmodel.ReviewInboxViewModel
import com.financeautomation.presentation.viewmodel.SettingsViewModel
import com.financeautomation.presentation.viewmodel.SetupHealthViewModel
import com.financeautomation.presentation.viewmodel.SetupHealthViewModelFactory
import com.financeautomation.presentation.viewmodel.SubscriptionsViewModel
import com.financeautomation.presentation.viewmodel.TransactionsViewModel
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Main launcher Activity for Finance Automation.
 * Hosts modular navigation drawer, bottom bar, and deep links via AppNavHost.
 */
class MainActivity : ComponentActivity() {

    private var pendingRoute by mutableStateOf<String?>(null)

    private val reviewInboxViewModel: ReviewInboxViewModel by viewModels {
        val app = application as FinanceApplication
        ReviewInboxViewModel.provideFactory(
            transactionRepository = app.appContainer.transactionRepository,
            syncQueueDaoProvider = { app.appContainer.awaitDatabase().syncQueueDao() },
            pendingCaptureDaoProvider = { app.appContainer.awaitDatabase().pendingCaptureDao() },
            accountRepository = app.appContainer.accountRepository,
            ioDispatcher = app.appContainer.ioDispatcher,
            subscriptionRepository = app.appContainer.subscriptionRepository,
            subscriptionActions = app.appContainer.subscriptionActionUseCase
        )
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        val app = application as FinanceApplication
        DashboardViewModel.provideFactory(
            transactionRepository = app.appContainer.transactionRepository,
            syncQueueDaoProvider = { app.appContainer.awaitDatabase().syncQueueDao() },
            payableDaoProvider = { app.appContainer.awaitDatabase().payableDao() },
            pendingCaptureDaoProvider = { app.appContainer.awaitDatabase().pendingCaptureDao() },
            categoryRepository = app.appContainer.categoryRepository,
            onlineState = app.appContainer.connectivityObserver.isOnline,
            ioDispatcher = app.appContainer.ioDispatcher
        )
    }

    private val transactionsViewModel: TransactionsViewModel by viewModels {
        val app = application as FinanceApplication
        TransactionsViewModel.provideFactory(
            transactionRepository = app.appContainer.transactionRepository,
            syncQueueDaoProvider = { app.appContainer.awaitDatabase().syncQueueDao() },
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

    private val subscriptionsViewModel: SubscriptionsViewModel by viewModels {
        val app = application as FinanceApplication
        SubscriptionsViewModel.provideFactory(
            subscriptionRepository = app.appContainer.subscriptionRepository
        )
    }

    override fun onResume() {
        super.onResume()
        setupHealthViewModel.refreshChecks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractRouteFromIntent(intent)

        setContent {
            val app = application as FinanceApplication
            val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
            val googleEmail by app.appContainer.preferencesRepository.getGoogleAccountEmail().collectAsState(initial = null)
            val spreadsheetId by app.appContainer.preferencesRepository.getSpreadsheetIdForYear(currentYear).collectAsState(initial = null)
            val themeMode by app.appContainer.preferencesRepository.getThemeMode().collectAsState(initial = ThemeMode.SYSTEM)
            var forceOnboarding by remember { mutableStateOf(false) }

            val needsOnboarding = forceOnboarding || googleEmail.isNullOrBlank() || spreadsheetId.isNullOrBlank()

            FinanceAutomationTheme(themeMode = themeMode) {
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
                        val navController = rememberNavController()

                        LaunchedEffect(pendingRoute) {
                            pendingRoute?.let { route ->
                                navController.navigate(route) {
                                    launchSingleTop = true
                                }
                                pendingRoute = null
                            }
                        }

                        AppNavHost(
                            navController = navController,
                            dashboardViewModel = dashboardViewModel,
                            reviewInboxViewModel = reviewInboxViewModel,
                            transactionsViewModel = transactionsViewModel,
                            settingsViewModel = settingsViewModel,
                            setupHealthViewModel = setupHealthViewModel,
                            onboardingViewModel = onboardingViewModel,
                            overlayControllerProvider = { app.appContainer.overlayController },
                            subscriptionsViewModel = subscriptionsViewModel,
                            onReRunSetup = { forceOnboarding = true }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractRouteFromIntent(intent)
    }

    private fun extractRouteFromIntent(intent: Intent?) {
        val extraRoute = intent?.getStringExtra("extra_route")
        if (!extraRoute.isNullOrBlank()) {
            pendingRoute = extraRoute
            return
        }
        val dataUri = intent?.data
        if (dataUri != null && dataUri.scheme == "financeautomation") {
            val host = dataUri.host
            val path = dataUri.path?.removePrefix("/")
            val route = if (!path.isNullOrBlank()) path else host
            if (!route.isNullOrBlank()) {
                pendingRoute = route
            }
        }
    }
}
