package com.financeautomation.presentation.overlay

import kotlinx.coroutines.flow.first
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelProvider
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.model.Companion as FriendCompanion
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.GetOpenDebtsForPersonUseCase
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import com.financeautomation.presentation.flows.OpenDebtItem
import com.financeautomation.presentation.theme.DefaultOverlayScrim
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.viewmodel.QuickLogViewModel
import com.financeautomation.presentation.viewmodel.QuickLogViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-scoped controller managing the system alert overlay window for transaction review and flows.
 * Cleanly decouples presentation from Activity lifecycles (Spec 5.1).
 */
class OverlayController(
    private val context: Context,
    private val transactionRepository: TransactionRepository,
    private val peopleRepository: PeopleRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val tripTagRepository: TripTagRepository,
    private val pendingCaptureDaoProvider: suspend () -> PendingCaptureDao,
    private val syncQueueDaoProvider: suspend () -> SyncQueueDao,
    private val categoryStatDaoProvider: (suspend () -> CategoryStatDao)? = null,
    private val predictCategoryUseCase: PredictCategoryUseCase = PredictCategoryUseCase(),
    private val getOpenDebtsForPersonUseCase: GetOpenDebtsForPersonUseCase? = null,
    private val payableDaoProvider: (suspend () -> PayableDao)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val subscriptionRepository: com.financeautomation.domain.repository.SubscriptionRepository? = null,
    /** True while the app lock is active: the overlay then shows no friend names, balances or flows that list them. */
    private val isLocked: suspend () -> Boolean = { false },
    /** True when windows must hide their content from screenshots and the recents list. */
    private val hidePreview: () -> Boolean = { false }
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val resultPill = ResultPillOverlay(appContext, hidePreview)
    private val blurHelper = OverlayBlurHelper(windowManager)
    private val mainScope = CoroutineScope(mainDispatcher + SupervisorJob())

    private val actionDispatcher = OverlayActionDispatcher(
        transactionRepository = transactionRepository, tripTagRepository = tripTagRepository,
        resultPill = resultPill, onDismiss = ::dismiss, mainScope = mainScope, ioDispatcher = ioDispatcher
    )

    private val showRequested = AtomicBoolean(false)

    val isShowing: Boolean
        get() = showRequested.get() || currentView != null

    private val _isShowingFlow = MutableStateFlow(false)
    val isShowingFlow: StateFlow<Boolean> = _isShowingFlow.asStateFlow()

    private var currentCaptureId: String? = null
    private val openDebtsState = mutableStateOf<List<OpenDebtItem>>(emptyList())
    private var openDebtsJob: Job? = null

    private var currentView: ComposeView? = null
    private var currentTreeOwners: ComposeOverlayTreeOwners? = null
    private var currentLayoutParams: WindowManager.LayoutParams? = null

    /**
     * Shows an overlay window for the given request.
     * Returns true if displayed or queued, false if blocked by missing permissions or active presentation.
     */
    fun show(request: OverlayRequest): Boolean {
        if (!Settings.canDrawOverlays(appContext)) {
            PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.OVERLAY_PERMISSION_MISSING)
            Diagnostics.record("OverlayController", "show: SYSTEM_ALERT_WINDOW permission missing")
            return false
        }

        // Automatic capture while sheet is already open is deferred; atomic check-and-reserve
        if (request is OverlayRequest.Capture) {
            if (currentView != null || !showRequested.compareAndSet(false, true)) {
                PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.OVERLAY_DEFERRED_BUSY)
                return false
            }
        } else {
            showRequested.set(true)
        }
        _isShowingFlow.value = true

        // Explicit user action replacing an active capture reverts the old capture back to WAITING
        val replacedCaptureId = currentCaptureId
        if (replacedCaptureId != null && request !is OverlayRequest.Capture) {
            mainScope.launch(ioDispatcher) {
                try {
                    pendingCaptureDaoProvider().updateState(replacedCaptureId, PendingCaptureEntity.STATE_WAITING)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: revertReplacedCapture")
                }
            }
            currentCaptureId = null
        }

        currentCaptureId = (request as? OverlayRequest.Capture)?.captureId

        mainScope.launch {
            try {
                clearCurrentWindow()

                val treeOwners = ComposeOverlayTreeOwners()
                currentTreeOwners = treeOwners

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    @Suppress("DEPRECATION")
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                }
                com.financeautomation.presentation.security.SecureWindow.apply(layoutParams, hidePreview())
                currentLayoutParams = layoutParams

                // Android 12+ Hardware Cross-Window Blur handling
                blurHelper.setupWindowBlur(layoutParams, { currentView }, { currentLayoutParams })

                val viewModelFactory = QuickLogViewModelFactory(
                    transactionRepository = transactionRepository, peopleRepository = peopleRepository,
                    categoryRepository = categoryRepository, accountRepository = accountRepository,
                    tripTagRepository = tripTagRepository, pendingCaptureDaoProvider = pendingCaptureDaoProvider,
                    syncQueueDaoProvider = syncQueueDaoProvider, categoryStatDaoProvider = categoryStatDaoProvider,
                    predictCategoryUseCase = predictCategoryUseCase, ioDispatcher = ioDispatcher,
                    subscriptionRepository = subscriptionRepository
                )
                val viewModel = ViewModelProvider(treeOwners, viewModelFactory)[QuickLogViewModel::class.java]

                val accounts = accountRepository.getAllAccounts()
                val locked = isLocked()
                val rawCompanions = if (locked) emptyList() else peopleRepository.getAllCompanions()
                val payables = if (locked) emptyList() else payableDaoProvider?.invoke()?.getPending() ?: emptyList()
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
                val companions = updatedCompanions
                val activeTripTag = tripTagRepository.getActiveTagSync()
                // Figures of the active trip, left out while the app lock is active
                val tripSummary = if (locked || activeTripTag == null) null else
                    com.financeautomation.domain.usecase.TripSummaryUseCase()
                        .forTrip(activeTripTag.name, transactionRepository.getTransactions().first())

                val initialScreen = when (request) {
                    is OverlayRequest.ChooseAction -> if (locked) { viewModel.setManualMode(); OverlayFlowScreen.EXPENSE } else OverlayFlowScreen.MENU
                    is OverlayRequest.Capture -> {
                        val capture = pendingCaptureDaoProvider().getById(request.captureId)
                        if (capture?.kind.equals(com.financeautomation.data.local.entity.PendingCaptureEntity.KIND_CREDIT, ignoreCase = true)) {
                            OverlayFlowScreen.INCOME_RETURN
                        } else {
                            viewModel.loadCapture(request.captureId)
                            OverlayFlowScreen.EXPENSE
                        }
                    }
                    is OverlayRequest.ManualExpense -> { viewModel.setManualMode(); OverlayFlowScreen.EXPENSE }
                    is OverlayRequest.SpecificFlow -> if (locked) { viewModel.setManualMode(); OverlayFlowScreen.EXPENSE } else when (request.flowType.uppercase()) {
                        "INCOME_RETURN" -> OverlayFlowScreen.INCOME_RETURN
                        "FRIEND_PAID" -> OverlayFlowScreen.FRIEND_PAID
                        "REPAID_FRIEND" -> OverlayFlowScreen.REPAID_FRIEND
                        "TRIP_MODE" -> OverlayFlowScreen.TRIP_MODE
                        else -> { viewModel.setManualMode(); OverlayFlowScreen.EXPENSE }
                    }
                }

                val composeView = ComposeView(appContext).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        FinanceAutomationTheme {
                            var isVisible by remember { mutableStateOf(false) }

                            LaunchedEffect(Unit) {
                                isVisible = true
                            }

                            // Full screen scrim backdrop for 1-tap outside dismissal
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(SCRIM_COLOR)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.USER_DISMISSED)
                                        val capId = currentCaptureId
                                        if (capId != null) {
                                            mainScope.launch(ioDispatcher) {
                                                try {
                                                    pendingCaptureDaoProvider().updateState(capId, PendingCaptureEntity.STATE_DISMISSED)
                                                } catch (e: Exception) {
                                                    if (e is CancellationException) throw e
                                                }
                                            }
                                        }
                                        viewModel.onDismiss()
                                        dismiss()
                                    },
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                AnimatedVisibility(
                                    visible = isVisible,
                                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                                ) {
                                    OverlayContentHost(
                                        initialScreen = initialScreen,
                                        quickLogViewModel = viewModel,
                                        accounts = accounts,
                                        companions = companions,
                                        activeTripTag = activeTripTag,
                                        tripSummary = tripSummary,
                                        openDebts = openDebtsState.value,
                                        onPersonChanged = ::loadOpenDebtsForPerson,
                                        onConfirmExpense = {
                                            viewModel.onConfirmAndSave { message, txnId ->
                                                PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.USER_SAVED)
                                                dismiss()
                                                resultPill.show(
                                                    message = message.asString(appContext),
                                                    transactionId = txnId,
                                                    onUndo = if (txnId != null) {
                                                        { id ->
                                                            mainScope.launch(ioDispatcher) {
                                                                transactionRepository.undoRecentSave(id)
                                                            }
                                                        }
                                                    } else null
                                                )
                                            }
                                        },
                                        onConfirmIncome = { amount, account, inflowType, settler, reason, explicitRefs ->
                                            val capId = currentCaptureId
                                            if (capId != null) {
                                                mainScope.launch(ioDispatcher) {
                                                    try {
                                                        pendingCaptureDaoProvider().updateState(capId, com.financeautomation.data.local.entity.PendingCaptureEntity.STATE_DONE)
                                                    } catch (e: Exception) {
                                                        if (e is CancellationException) throw e
                                                    }
                                                }
                                            }
                                            actionDispatcher.confirmIncome(amount, account, inflowType, settler, reason, explicitRefs)
                                        },
                                        onConfirmFriendPaid = actionDispatcher::confirmPayable,
                                        onConfirmRepaid = actionDispatcher::confirmRepaid,
                                        onStartTrip = actionDispatcher::startTrip,
                                        onEndTrip = actionDispatcher::endTrip,
                                        onDismiss = {
                                            PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.USER_DISMISSED)
                                            val capId = currentCaptureId
                                            if (capId != null) {
                                                mainScope.launch(ioDispatcher) {
                                                    try {
                                                        pendingCaptureDaoProvider().updateState(capId, PendingCaptureEntity.STATE_DISMISSED)
                                                    } catch (e: Exception) {
                                                        if (e is CancellationException) throw e
                                                    }
                                                }
                                            }
                                            viewModel.onDismiss()
                                            dismiss()
                                        },
                                        onKeyboardFocusChanged = { hasFocus ->
                                            setKeyboardFocus(hasFocus)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                treeOwners.attachTo(composeView)
                windowManager.addView(composeView, layoutParams)
                currentView = composeView
                _isShowingFlow.value = true
                PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.OVERLAY_SHOWN)
            } catch (e: Exception) {
                PipelineTrail.record(PipelineStage.PROMPT, PipelineCode.OVERLAY_ADD_FAILED)
                showRequested.set(false)
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: show failed")
                val failedCaptureId = currentCaptureId
                if (failedCaptureId != null) {
                    launch(ioDispatcher) {
                        try {
                            pendingCaptureDaoProvider().updateState(failedCaptureId, PendingCaptureEntity.STATE_WAITING)
                        } catch (ex: Exception) {
                            if (ex is CancellationException) throw ex
                            Diagnostics.record("OverlayController", "${ex.javaClass.simpleName}: revertFailedCapture")
                        }
                    }
                }
                dismiss()
            }
        }
        return true
    }

    private fun loadOpenDebtsForPerson(person: String) {
        openDebtsJob?.cancel()
        val query = person.trim()
        if (query.isBlank()) {
            openDebtsState.value = emptyList()
            return
        }
        val useCase = getOpenDebtsForPersonUseCase ?: return
        openDebtsJob = mainScope.launch {
            if (isLocked()) {
                openDebtsState.value = emptyList()
                return@launch
            }
            val debts = useCase(query)
            openDebtsState.value = debts
        }
    }

    /**
     * Toggles FLAG_NOT_FOCUSABLE dynamically to allow the software keyboard to emerge.
     */
    private fun setKeyboardFocus(isFocused: Boolean) {
        val view = currentView ?: return
        val params = currentLayoutParams ?: return
        val currentlyNonFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0

        if (isFocused && currentlyNonFocusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(view, params)
        } else if (!isFocused && !currentlyNonFocusable) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun clearCurrentWindow() {
        blurHelper.teardownWindowBlur()
        val view = currentView
        val owners = currentTreeOwners
        try {
            if (view != null) {
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: removeView failed")
                }
            }
            if (owners != null) {
                try {
                    owners.destroy()
                } catch (e: Exception) {
                    Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: owners.destroy failed")
                }
            }
        } finally {
            currentView = null
            currentTreeOwners = null
            currentLayoutParams = null
        }
    }

    /**
     * Dismisses the currently displayed overlay window and recycles resources.
     */
    fun dismiss() {
        clearCurrentWindow()
        currentCaptureId = null
        showRequested.set(false)
        _isShowingFlow.value = false
    }

    /**
     * Shows a lightweight pill banner at the top of the screen.
     */
    fun showPill(message: String) {
        resultPill.show(message)
    }

    companion object {
        val SCRIM_COLOR = DefaultOverlayScrim
    }
}
