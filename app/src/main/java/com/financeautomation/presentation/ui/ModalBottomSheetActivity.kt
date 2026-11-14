package com.financeautomation.presentation.ui

import kotlinx.coroutines.flow.first
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.financeautomation.FinanceApplication
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Companion as FriendCompanion
import com.financeautomation.domain.model.TripTag
import com.financeautomation.presentation.flows.OpenDebtItem
import com.financeautomation.presentation.overlay.OverlayContentHost
import com.financeautomation.presentation.overlay.OverlayFlowScreen
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.QuickLogViewModel
import com.financeautomation.presentation.viewmodel.QuickLogViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent Activity hosting the interactive Floating Modal Bottom Sheet for expense splitting and categorization.
 * Floats directly over the home screen or third-party UPI apps
 * with a dimmed translucent backdrop and 1-tap outside dismissal.
 */
class ModalBottomSheetActivity : ComponentActivity() {

    private val quickLogViewModel: QuickLogViewModel by viewModels {
        val app = application as FinanceApplication
        QuickLogViewModelFactory(
            transactionRepository = app.appContainer.transactionRepository,
            peopleRepository = app.appContainer.peopleRepository,
            categoryRepository = app.appContainer.categoryRepository,
            accountRepository = app.appContainer.accountRepository,
            tripTagRepository = app.appContainer.tripTagRepository,
            pendingCaptureDaoProvider = { app.appContainer.awaitDatabase().pendingCaptureDao() },
            syncQueueDaoProvider = { app.appContainer.awaitDatabase().syncQueueDao() },
            categoryStatDaoProvider = { app.appContainer.awaitDatabase().categoryStatDao() },
            ioDispatcher = app.appContainer.ioDispatcher,
            subscriptionRepository = app.appContainer.subscriptionRepository
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure hardware window for true hardware-composited translucency
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.48f)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        com.financeautomation.presentation.security.SecureWindow.follow(
            window, (application as FinanceApplication).appContainer.privacySettings, lifecycleScope
        )

        // A recreated activity (rotation, theme, font scale) must not reload over what the user already typed
        handleIntent(intent, restoring = savedInstanceState != null)

        setContent {
            FinanceAutomationTheme(transparentSystemBars = true) {
                val state by quickLogViewModel.uiState.collectAsState()
                val app = application as FinanceApplication

                LaunchedEffect(state.isSaved, state.isVoided) {
                    if (state.isSaved || state.isVoided) {
                        dismissSafely()
                    }
                }

                val accounts by produceState<List<Account>>(initialValue = emptyList()) {
                    value = withContext(Dispatchers.IO) { app.appContainer.accountRepository.getAllAccounts() }
                }
                val lockedNow = lockedState.value
                val companions by produceState<List<FriendCompanion>>(initialValue = emptyList(), lockedNow) {
                    if (lockedNow) {
                        value = emptyList()
                        return@produceState
                    }
                    val rawCompanions = withContext(Dispatchers.IO) { app.appContainer.peopleRepository.getAllCompanions() }
                    val db = withContext(Dispatchers.IO) { app.appContainer.awaitDatabase() }
                    val payables = withContext(Dispatchers.IO) { db.payableDao().getPending() }
                    val payableMap = payables.groupBy { it.paidBy.trim().lowercase() }
                        .mapValues { entry -> entry.value.sumOf { it.amountIOwe } }

                    val updatedCompanions = rawCompanions.map { comp ->
                        val owed = payableMap[comp.name.trim().lowercase()] ?: comp.balance
                        comp.copy(balance = owed)
                    }.toMutableList()

                    for ((nameLower, totalOwed) in payableMap) {
                        if (totalOwed > 0.0 && updatedCompanions.none { it.name.trim().lowercase() == nameLower }) {
                            val originalName = payables.first { it.paidBy.trim().lowercase() == nameLower }.paidBy.trim()
                            updatedCompanions.add(FriendCompanion(id = "payable_$originalName", name = originalName, balance = totalOwed))
                        }
                    }
                    value = updatedCompanions
                }
                val activeTripTag by produceState<TripTag?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) { app.appContainer.tripTagRepository.getActiveTagSync() }
                }
                val tripSummary by produceState<com.financeautomation.domain.usecase.TripSummary?>(initialValue = null, activeTripTag, lockedNow) {
                    val tag = activeTripTag
                    value = if (lockedNow || tag == null) null else withContext(Dispatchers.IO) {
                        com.financeautomation.domain.usecase.TripSummaryUseCase()
                            .forTrip(tag.name, app.appContainer.transactionRepository.getTransactions().first())
                    }
                }
                var openDebts by remember { mutableStateOf<List<OpenDebtItem>>(emptyList()) }
                val preselectedFriend = if (lockedNow) null else remember { intent.getStringExtra(EXTRA_PRESELECT_FRIEND) }

                LaunchedEffect(preselectedFriend) {
                    if (!preselectedFriend.isNullOrBlank()) {
                        val useCase = app.appContainer.getOpenDebtsForPersonUseCase
                        openDebts = withContext(Dispatchers.IO) { useCase.invoke(preselectedFriend) }
                    }
                }

                // While the app lock is active only the plain expense form is offered: the friend flows list names and balances
                val initialScreen = if (lockedNow) {
                    OverlayFlowScreen.EXPENSE
                } else {
                    resolveInitialScreen(
                        intent = intent,
                        callingPackage = callingPackage,
                        ownPackageName = packageName
                    )
                }

                // Transparent clickable backdrop for 1-tap outside dismiss
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { dismissSafely() },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    OverlayContentHost(
                        initialScreen = initialScreen,
                        quickLogViewModel = quickLogViewModel,
                        accounts = accounts,
                        companions = companions,
                        activeTripTag = activeTripTag,
                        tripSummary = tripSummary,
                        openDebts = openDebts,
                        preselectedFriend = preselectedFriend,
                        onPersonChanged = { person ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val useCase = app.appContainer.getOpenDebtsForPersonUseCase
                                openDebts = useCase.invoke(person)
                            }
                        },
                        onConfirmExpense = {
                            quickLogViewModel.onConfirmAndSave { msg ->
                                Toast.makeText(this@ModalBottomSheetActivity, msg.asString(this@ModalBottomSheetActivity), Toast.LENGTH_SHORT).show()
                                dismissSafely()
                            }
                        },
                        onConfirmIncome = { amount, account, inflowType, settler, reason, explicitRefs ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                app.appContainer.transactionRepository.logCredit(
                                    settler = settler,
                                    amount = amount,
                                    explicitRefs = explicitRefs,
                                    account = account,
                                    inflowType = inflowType,
                                    reason = reason
                                )
                                withContext(Dispatchers.Main) {
                                    val formatted = String.format(Locale.US, "%.2f", amount)
                                    val msg = if (settler.isNotBlank()) "Recorded $formatted from $settler" else "Recorded $formatted"
                                    Toast.makeText(this@ModalBottomSheetActivity, msg, Toast.LENGTH_SHORT).show()
                                    dismissSafely()
                                }
                            }
                        },
                        onConfirmFriendPaid = { amount, creditor, whatFor ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                app.appContainer.transactionRepository.logPayable(
                                    creditor = creditor,
                                    amount = amount,
                                    whatFor = whatFor
                                )
                                withContext(Dispatchers.Main) {
                                    val formatted = String.format(Locale.US, "%.2f", amount)
                                    val msg = if (creditor.isNotBlank()) "Recorded payable $formatted to $creditor" else "Recorded payable $formatted"
                                    Toast.makeText(this@ModalBottomSheetActivity, msg, Toast.LENGTH_SHORT).show()
                                    dismissSafely()
                                }
                            }
                        },
                        onConfirmRepaid = { amount, person, timestamp ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                app.appContainer.transactionRepository.logRepayment(
                                    creditor = person,
                                    amount = amount,
                                    explicitRef = null,
                                    timestamp = timestamp
                                )
                                withContext(Dispatchers.Main) {
                                    val formatted = String.format(Locale.US, "%.2f", amount)
                                    val msg = if (person.isNotBlank()) "Recorded repayment $formatted to $person" else "Recorded repayment $formatted"
                                    Toast.makeText(this@ModalBottomSheetActivity, msg, Toast.LENGTH_SHORT).show()
                                    dismissSafely()
                                }
                            }
                        },
                        onStartTrip = { tagName ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val tag = app.appContainer.tripTagRepository.getTagByName(tagName)
                                    ?: app.appContainer.tripTagRepository.insertTripTag(tagName)
                                app.appContainer.tripTagRepository.setActiveTag(tag.id)
                                withContext(Dispatchers.Main) { dismissSafely() }
                            }
                        },
                        onEndTrip = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                app.appContainer.tripTagRepository.setActiveTag(null)
                                withContext(Dispatchers.Main) { dismissSafely() }
                            }
                        },
                        onDismiss = { dismissSafely() },
                        onKeyboardFocusChanged = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m)
                    )
                }
            }
        }
    }

    private fun dismissSafely() {
        quickLogViewModel.onDismiss()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** True until the saved lock state is known, and while the app lock is active. Hides friend names and balances. */
    private val lockedState = androidx.compose.runtime.mutableStateOf(true)

    private fun handleIntent(intent: Intent, restoring: Boolean = false) {
        val captureId = intent.getStringExtra(EXTRA_CAPTURE_ID)
        val txnId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
            ?: intent.getStringExtra("txn_id")
            ?: intent.getStringExtra("txn_uuid")

        lifecycleScope.launch {
            val container = (application as FinanceApplication).appContainer
            withContext(Dispatchers.IO) { container.ensureAppLockStarted() }
            val locked = container.appLockManager.isLockedNow()
            lockedState.value = locked
            if (restoring && !locked) return@launch
            when (ModalSheetLoadPolicy.decide(captureId, txnId, locked)) {
                ModalSheetLoadPolicy.Action.LOAD_CAPTURE -> quickLogViewModel.loadCapture(captureId.orEmpty())
                ModalSheetLoadPolicy.Action.LOAD_TRANSACTION -> quickLogViewModel.loadTransaction(txnId.orEmpty())
                ModalSheetLoadPolicy.Action.SHOW_LOCK_PROMPT -> {
                    // Locked: load nothing. The main screen shows the unlock prompt; the capture stays waiting.
                    startActivity(
                        InternalIntentAuth.attach(
                            this@ModalBottomSheetActivity,
                            Intent(this@ModalBottomSheetActivity, MainActivity::class.java).apply {
                                putExtra("extra_route", AppRoutes.NEEDS_REVIEW)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                        )
                    )
                    finish()
                }
                ModalSheetLoadPolicy.Action.MANUAL -> quickLogViewModel.setManualMode()
            }
        }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID: String = "com.financeautomation.extra.TRANSACTION_ID"
        const val EXTRA_CAPTURE_ID: String = "com.financeautomation.extra.CAPTURE_ID"
        const val EXTRA_FLOW: String = "com.financeautomation.extra.FLOW"
        const val EXTRA_PRESELECT_FRIEND: String = "com.financeautomation.extra.PRESELECT_FRIEND"
        const val EXTRA_INTERNAL_AUTH: String = "com.financeautomation.extra.INTERNAL_AUTH"

        fun resolveInitialScreen(
            intent: Intent,
            callingPackage: String?,
            ownPackageName: String,
            isDebug: Boolean = com.financeautomation.BuildConfig.DEBUG
        ): OverlayFlowScreen {
            val authPendingIntent = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_INTERNAL_AUTH, android.app.PendingIntent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_INTERNAL_AUTH)
                }
            } catch (e: Exception) {
                null
            }
            val hasAuthToken = authPendingIntent?.creatorPackage == ownPackageName
            val isAuth = com.financeautomation.presentation.overlay.FlowAuthGate.isAuthorized(
                hasAuthToken = hasAuthToken,
                callingPackage = callingPackage,
                ownPackageName = ownPackageName,
                isDebug = isDebug
            )
            val rawFlow = if (isAuth) intent.getStringExtra(EXTRA_FLOW) else null
            return com.financeautomation.presentation.overlay.FlowAuthGate.resolveScreen(rawFlow, isAuth)
        }
    }
}
