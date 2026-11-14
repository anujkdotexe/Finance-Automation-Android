package com.financeautomation.presentation.ui

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.financeautomation.FinanceApplication
import com.financeautomation.R
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Main launcher Activity for Finance Automation.
 * Hosts modular navigation drawer, bottom bar, and deep links via AppNavHost.
 */
class MainActivity : FragmentActivity() {

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
            subscriptionActions = app.appContainer.subscriptionActionUseCase,
            preferencesRepository = app.appContainer.preferencesRepository
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
            inflowRepository = app.appContainer.inflowRepository,
            onlineState = app.appContainer.connectivityObserver.isOnline,
            preferencesRepository = app.appContainer.preferencesRepository,
            budgetRepository = app.appContainer.budgetRepository,
            ioDispatcher = app.appContainer.ioDispatcher
        )
    }

    private val transactionsViewModel: TransactionsViewModel by viewModels {
        val app = application as FinanceApplication
        TransactionsViewModel.provideFactory(
            transactionRepository = app.appContainer.transactionRepository,
            syncQueueDaoProvider = { app.appContainer.awaitDatabase().syncQueueDao() },
            inflowRepository = app.appContainer.inflowRepository,
            ioDispatcher = app.appContainer.ioDispatcher,
            budgetRepository = app.appContainer.budgetRepository,
            categoryRepository = app.appContainer.categoryRepository
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
            profileAvatarManager = app.appContainer.profileAvatarManager,
            payableDaoProvider = { app.appContainer.awaitDatabase().payableDao() },
            ioDispatcher = app.appContainer.ioDispatcher,
            appLockManager = app.appContainer.appLockManager,
            privacySettings = app.appContainer.privacySettings,
            accountSwitchGuard = app.appContainer.accountSwitchGuard
        )
    }

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        val app = application as FinanceApplication
        OnboardingViewModel.provideFactory(
            googleAuthTokenProvider = app.appContainer.googleAuthTokenProvider,
            workbookResolver = app.appContainer.workbookResolver,
            preferencesRepository = app.appContainer.preferencesRepository,
            profileAvatarManager = app.appContainer.profileAvatarManager,
            accountSwitchGuard = app.appContainer.accountSwitchGuard,
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
            subscriptionRepository = app.appContainer.subscriptionRepository,
            transactionRepository = app.appContainer.transactionRepository,
            preferencesRepository = app.appContainer.preferencesRepository
        )
    }

    override fun onResume() {
        super.onResume()
        setupHealthViewModel.refreshChecks()
    }

    override fun onStart() {
        super.onStart()
        (application as FinanceApplication).appContainer.appLockManager.onForegrounded()
    }

    override fun onStop() {
        (application as FinanceApplication).appContainer.appLockManager.onBackgrounded()
        // Redraw the home screen widget so a changed app lock setting shows there
        com.financeautomation.service.MonthBurnWidgetProvider.requestUpdate(applicationContext)
        super.onStop()
    }

    private var unlockPromptShowing = false

    /** Starts the system authentication. If the phone no longer has any screen lock, the app lock is switched off. */
    private fun promptUnlock() {
        if (unlockPromptShowing) return
        val app = application as FinanceApplication
        val authenticator = com.financeautomation.presentation.security.AppAuthenticator(this)
        if (authenticator.availability() != com.financeautomation.domain.security.AuthAvailability.AVAILABLE) {
            app.appContainer.appLockManager.disable()
            lifecycleScope.launch(Dispatchers.IO) { app.appContainer.preferencesRepository.setAppLockEnabled(false) }
            return
        }
        unlockPromptShowing = true
        authenticator.authenticate(getString(R.string.app_lock_prompt_title), null) { ok ->
            unlockPromptShowing = false
            app.appContainer.appLockManager.onUnlockResult(ok)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.financeautomation.presentation.security.SecureWindow.follow(
            window, (application as FinanceApplication).appContainer.privacySettings, lifecycleScope
        )
        extractRouteFromIntent(intent)

        try {
            val gAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this)
            if (gAccount != null) {
                val app = application as FinanceApplication
                lifecycleScope.launch(Dispatchers.IO) {
                    gAccount.email?.let { app.appContainer.preferencesRepository.setGoogleAccountEmail(it) }
                    gAccount.displayName?.let { app.appContainer.preferencesRepository.setGoogleAccountDisplayName(it) }
                    val photoUrl = gAccount.photoUrl?.toString()
                    if (!photoUrl.isNullOrBlank()) {
                        app.appContainer.preferencesRepository.setGoogleAccountPhotoUrl(photoUrl)
                        app.appContainer.profileAvatarManager.downloadAndSaveAvatar(photoUrl)
                    }
                }
            }
        } catch (_: Exception) {}

        setContent {
          com.financeautomation.presentation.screens.StorageGate(application as FinanceApplication) {
            val app = application as FinanceApplication
            val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
            val googleEmail by app.appContainer.preferencesRepository.getGoogleAccountEmail().collectAsState(initial = null)
            val spreadsheetId by app.appContainer.preferencesRepository.getSpreadsheetIdForYear(currentYear).collectAsState(initial = null)
            val themeMode by app.appContainer.preferencesRepository.getThemeMode().collectAsState(initial = ThemeMode.SYSTEM)
            var forceOnboarding by remember { mutableStateOf(false) }
            var lockReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                app.appContainer.ensureAppLockStarted()
                lockReady = true
            }
            val locked by app.appContainer.appLockManager.locked.collectAsState()

            val setupIncomplete = googleEmail.isNullOrBlank() || spreadsheetId.isNullOrBlank()
            // Once the Setup page is shown it stays until Continue is pressed, so a finished sign in shows Continue
            // instead of jumping away on its own.
            var setupLatched by remember { mutableStateOf(false) }
            LaunchedEffect(setupIncomplete) { if (setupIncomplete) setupLatched = true }
            val needsOnboarding = forceOnboarding || setupLatched || setupIncomplete

            FinanceAutomationTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!lockReady) {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize())
                    } else if (locked) {
                        com.financeautomation.presentation.security.AppLockScreen(onUnlock = { promptUnlock() })
                    } else if (needsOnboarding) {
                        OnboardingScreen(
                            viewModel = onboardingViewModel,
                            onOnboardingFinished = {
                                forceOnboarding = false
                                setupLatched = false
                            }
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractRouteFromIntent(intent)
    }

    private fun extractRouteFromIntent(intent: Intent?) {
        val extraRoute = intent?.getStringExtra("extra_route")
        val dataUriString = intent?.dataString
        val validatedRoute = MainRouteValidator.resolveRoute(
            extraRoute, dataUriString, internal = InternalIntentAuth.isInternal(intent, packageName)
        )
        if (validatedRoute != null) {
            pendingRoute = validatedRoute
        }
    }
}
