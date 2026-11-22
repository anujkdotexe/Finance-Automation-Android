package com.financeautomation.presentation.ui

import kotlinx.coroutines.flow.first
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PrivacyScrubber
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Companion as FriendCompanion
import com.financeautomation.domain.model.TripTag
import com.financeautomation.presentation.flows.OpenDebtItem
import com.financeautomation.presentation.overlay.OverlayContentHost
import com.financeautomation.presentation.overlay.OverlayFlowScreen
import com.financeautomation.presentation.theme.SavedThemeHost
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.QuickLogViewModel
import com.financeautomation.presentation.viewmodel.QuickLogViewModelFactory
import kotlinx.coroutines.CancellationException
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
            val app = application as FinanceApplication
            SavedThemeHost(app.appContainer.preferencesRepository.getThemeMode(), transparentSystemBars = true) {
                val state by quickLogViewModel.uiState.collectAsState()

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
                            .forTrip(
                                tag.name,
                                app.appContainer.transactionRepository.getTransactions().first(),
                                excludedCategories = com.financeautomation.domain.usecase.LivingBurn.excludedNames(
                                    app.appContainer.categoryRepository.getAllCategories(includeArchived = true)
                                ),
                                inflows = app.appContainer.inflowRepository.getAllInflows().first()
                            )
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

                val isCreditCapture by produceState(initialValue = false) {
                    val cid = intent.getStringExtra(EXTRA_CAPTURE_ID)
                    if (!cid.isNullOrBlank()) {
                        val db = withContext(Dispatchers.IO) { app.appContainer.awaitDatabase() }
                        val cap = withContext(Dispatchers.IO) { db.pendingCaptureDao().getById(cid) }
                        value = cap?.kind.equals(com.financeautomation.data.local.entity.PendingCaptureEntity.KIND_CREDIT, ignoreCase = true)
                    }
                }

                // While the app lock is active only the plain expense form is offered: the friend flows list names and balances
                val initialScreen = if (lockedNow) {
                    OverlayFlowScreen.EXPENSE
                } else if (isCreditCapture) {
                    OverlayFlowScreen.INCOME_RETURN
                } else {
                    resolveInitialScreen(intent)
                }

                // Transparent clickable backdrop for 1-tap outside dismiss
                val isSubmitting = remember { AtomicBoolean(false) }

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
                            if (!isSubmitting.compareAndSet(false, true)) return@OverlayContentHost
                            quickLogViewModel.onConfirmAndSave(
                                onSaved = { msg, txnId ->
                                    Toast.makeText(this@ModalBottomSheetActivity, msg.asString(this@ModalBottomSheetActivity), Toast.LENGTH_SHORT).show()
                                    dismissSafely()
                                },
                                onError = { msg ->
                                    isSubmitting.set(false)
                                    Toast.makeText(this@ModalBottomSheetActivity, msg.asString(this@ModalBottomSheetActivity), Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        onConfirmIncome = { amount, account, inflowType, settler, reason, explicitRefs ->
                            if (!isSubmitting.compareAndSet(false, true)) return@OverlayContentHost
                            val captureId = intent.getStringExtra(EXTRA_CAPTURE_ID)
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val res = app.appContainer.transactionRepository.logCredit(
                                        settler = settler,
                                        amount = amount,
                                        explicitRefs = explicitRefs,
                                        account = account,
                                        inflowType = inflowType,
                                        reason = reason
                                    )
                                    withContext(Dispatchers.Main) {
                                        when (res) {
                                            is Result.Success -> {
                                                if (!captureId.isNullOrBlank()) {
                                                    launch(Dispatchers.IO) {
                                                        try {
                                                            val db = app.appContainer.awaitDatabase()
                                                            db.pendingCaptureDao().updateState(captureId, com.financeautomation.data.local.entity.PendingCaptureEntity.STATE_DONE)
                                                        } catch (e: Exception) {
                                                            if (e is CancellationException) throw e
                                                        }
                                                    }
                                                }
                                                val formatted = String.format(Locale.US, "%.2f", amount)
                                                val msg = if (settler.isNotBlank()) "Recorded $formatted from $settler" else "Recorded $formatted"
                                                Toast.makeText(this@ModalBottomSheetActivity, msg, Toast.LENGTH_SHORT).show()
                                                dismissSafely()
                                            }
                                            is Result.Error -> {
                                                isSubmitting.set(false)
                                                Diagnostics.record("ModalBottomSheetActivity", "logCredit: ${SyncErrorKind.safeCode("income", res.throwable)} ${PrivacyScrubber.scrub(res.message.orEmpty())}")
                                                val errMsg = SyncErrorKind.formatUserMessage("income", res.throwable)
                                                Toast.makeText(this@ModalBottomSheetActivity, errMsg, Toast.LENGTH_SHORT).show()
                                            }
                                            is Result.Loading -> Unit
                                        }
                                    }
                                } catch (e: Exception) {
                                    isSubmitting.set(false)
                                    if (e is CancellationException) throw e
                                    Diagnostics.record("ModalBottomSheetActivity", "logCredit_exception: ${SyncErrorKind.safeCode("income", e)} ${PrivacyScrubber.scrub(e.message.orEmpty())}")
                                    withContext(Dispatchers.Main) {
                                        val errMsg = SyncErrorKind.formatUserMessage("income", e)
                                        Toast.makeText(this@ModalBottomSheetActivity, errMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onConfirmFriendPaid = { amount, creditor, whatFor, category, timestamp ->
                            if (!isSubmitting.compareAndSet(false, true)) return@OverlayContentHost
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val res = app.appContainer.transactionRepository.logPayable(
                                        creditor = creditor,
                                        amount = amount,
                                        whatFor = whatFor,
                                        timestamp = timestamp,
                                        category = category
                                    )
                                    withContext(Dispatchers.Main) {
                                        when (res) {
                                            is Result.Success -> {
                                                val formatted = String.format(Locale.US, "%.2f", amount)
                                                val msg = if (creditor.isNotBlank()) "Recorded payable $formatted to $creditor" else "Recorded payable $formatted"
                                                Toast.makeText(this@ModalBottomSheetActivity, msg, Toast.LENGTH_SHORT).show()
                                                dismissSafely()
                                            }
                                            is Result.Error -> {
                                                isSubmitting.set(false)
                                                Diagnostics.record("ModalBottomSheetActivity", "logPayable: ${SyncErrorKind.safeCode("payable", res.throwable)} ${PrivacyScrubber.scrub(res.message.orEmpty())}")
                                                val errMsg = SyncErrorKind.formatUserMessage("payable", res.throwable)
                                                Toast.makeText(this@ModalBottomSheetActivity, errMsg, Toast.LENGTH_SHORT).show()
                                            }
                                            is Result.Loading -> Unit
                                        }
                                    }
                                } catch (e: Exception) {
                                    isSubmitting.set(false)
                                    if (e is CancellationException) throw e
                                    Diagnostics.record("ModalBottomSheetActivity", "logPayable_exception: ${SyncErrorKind.safeCode("payable", e)} ${PrivacyScrubber.scrub(e.message.orEmpty())}")
                                    withContext(Dispatchers.Main) {
                                        val errMsg = SyncErrorKind.formatUserMessage("payable", e)
                                        Toast.makeText(this@ModalBottomSheetActivity, errMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onConfirmRepaid = { amount, person, timestamp, account, note ->
                            if (!isSubmitting.compareAndSet(false, true)) return@OverlayContentHost
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val res = app.appContainer.transactionRepository.logRepayment(
                                        creditor = person,
                                        amount = amount,
                                        explicitRef = null,
                                        timestamp = timestamp,
                                        account = account,
                                        note = note
                                    )
                                    withContext(Dispatchers.Main) {
                                        when (res) {
                                            is Result.Success -> {
                                                val formatted = String.format(Locale.US, "%.2f", amount)
                                                val msg = if (person.isNotBlank()) "Recorded repayment $formatted to $person" else "Recorded repayment $formatted"
                                                Toast.makeText(this@ModalBottomSheetActivity, msg, Toast.LENGTH_SHORT).show()
                                                dismissSafely()
                                            }
                                            is Result.Error -> {
                                                isSubmitting.set(false)
                                                Diagnostics.record("ModalBottomSheetActivity", "logRepayment: ${SyncErrorKind.safeCode("repayment", res.throwable)} ${PrivacyScrubber.scrub(res.message.orEmpty())}")
                                                val errMsg = SyncErrorKind.formatUserMessage("repayment", res.throwable)
                                                Toast.makeText(this@ModalBottomSheetActivity, errMsg, Toast.LENGTH_SHORT).show()
                                            }
                                            is Result.Loading -> Unit
                                        }
                                    }
                                } catch (e: Exception) {
                                    isSubmitting.set(false)
                                    if (e is CancellationException) throw e
                                    Diagnostics.record("ModalBottomSheetActivity", "logRepayment_exception: ${SyncErrorKind.safeCode("repayment", e)} ${PrivacyScrubber.scrub(e.message.orEmpty())}")
                                    withContext(Dispatchers.Main) {
                                        val errMsg = SyncErrorKind.formatUserMessage("repayment", e)
                                        Toast.makeText(this@ModalBottomSheetActivity, errMsg, Toast.LENGTH_SHORT).show()
                                    }
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

        /**
         * The sheet is not exported, so only this app can start it and the flow name comes from our own code.
         * External callers reach flows only through [EntryActivity], which drops the flow for them.
         */
        fun resolveInitialScreen(intent: Intent): OverlayFlowScreen =
            com.financeautomation.presentation.overlay.FlowAuthGate.resolveScreen(intent.getStringExtra(EXTRA_FLOW))
    }
}
