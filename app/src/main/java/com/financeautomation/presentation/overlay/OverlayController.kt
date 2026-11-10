package com.financeautomation.presentation.overlay

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
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.GetOpenDebtsForPersonUseCase
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import com.financeautomation.presentation.flows.OpenDebtItem
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val resultPill = ResultPillOverlay(appContext)
    private val blurHelper = OverlayBlurHelper(windowManager)
    private val mainScope = CoroutineScope(mainDispatcher + SupervisorJob())

    private val actionDispatcher = OverlayActionDispatcher(
        transactionRepository = transactionRepository,
        tripTagRepository = tripTagRepository,
        resultPill = resultPill,
        onDismiss = ::dismiss,
        mainScope = mainScope,
        ioDispatcher = ioDispatcher
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
            Diagnostics.record("OverlayController", "show: SYSTEM_ALERT_WINDOW permission missing")
            return false
        }

        // Automatic capture while sheet is already open is deferred; remains in WAITING
        if (request is OverlayRequest.Capture && isShowing) {
            return false
        }

        // Reserve the overlay synchronously to prevent simultaneous capture prompt collisions
        showRequested.set(true)
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
                dismiss()

                val treeOwners = ComposeOverlayTreeOwners()
                currentTreeOwners = treeOwners

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    @Suppress("DEPRECATION")
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                }
                currentLayoutParams = layoutParams

                // Android 12+ Hardware Cross-Window Blur handling
                blurHelper.setupWindowBlur(layoutParams, { currentView }, { currentLayoutParams })

                val viewModelFactory = QuickLogViewModelFactory(
                    transactionRepository = transactionRepository,
                    peopleRepository = peopleRepository,
                    categoryRepository = categoryRepository,
                    accountRepository = accountRepository,
                    tripTagRepository = tripTagRepository,
                    pendingCaptureDaoProvider = pendingCaptureDaoProvider,
                    syncQueueDaoProvider = syncQueueDaoProvider,
                    categoryStatDaoProvider = categoryStatDaoProvider,
                    predictCategoryUseCase = predictCategoryUseCase,
                    ioDispatcher = ioDispatcher
                )
                val viewModel = ViewModelProvider(treeOwners, viewModelFactory)[QuickLogViewModel::class.java]

                val accounts = accountRepository.getAllAccounts()
                val companions = peopleRepository.getAllCompanions()
                val activeTripTag = tripTagRepository.getActiveTagSync()

                val initialScreen = when (request) {
                    is OverlayRequest.ChooseAction -> OverlayFlowScreen.MENU
                    is OverlayRequest.Capture -> {
                        viewModel.loadCapture(request.captureId)
                        OverlayFlowScreen.EXPENSE
                    }
                    is OverlayRequest.ManualExpense, is OverlayRequest.SelfTest -> {
                        viewModel.setManualMode()
                        OverlayFlowScreen.EXPENSE
                    }
                    is OverlayRequest.SpecificFlow -> {
                        when (request.flowType.uppercase()) {
                            "INCOME_RETURN" -> OverlayFlowScreen.INCOME_RETURN
                            "FRIEND_PAID" -> OverlayFlowScreen.FRIEND_PAID
                            "REPAID_FRIEND" -> OverlayFlowScreen.REPAID_FRIEND
                            "TRIP_MODE" -> OverlayFlowScreen.TRIP_MODE
                            else -> {
                                viewModel.setManualMode()
                                OverlayFlowScreen.EXPENSE
                            }
                        }
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
                                        openDebts = openDebtsState.value,
                                        onPersonChanged = ::loadOpenDebtsForPerson,
                                        onConfirmExpense = {
                                            viewModel.onConfirmAndSave { message ->
                                                dismiss()
                                                resultPill.show(message)
                                            }
                                        },
                                        onConfirmIncome = actionDispatcher::confirmIncome,
                                        onConfirmFriendPaid = actionDispatcher::confirmPayable,
                                        onConfirmRepaid = actionDispatcher::confirmRepaid,
                                        onStartTrip = actionDispatcher::startTrip,
                                        onEndTrip = actionDispatcher::endTrip,
                                        onDismiss = {
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
            } catch (e: Exception) {
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

    /**
     * Dismisses the currently displayed overlay window and recycles resources.
     */
    fun dismiss() {
        showRequested.set(false)
        blurHelper.teardownWindowBlur()
        currentView?.let { view ->
            try {
                currentTreeOwners?.destroy()
                windowManager.removeView(view)
            } catch (e: Exception) {
                Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: dismiss")
            }
        }
        currentView = null
        currentTreeOwners = null
        currentLayoutParams = null
        currentCaptureId = null
        _isShowingFlow.value = false
    }

    /**
     * Shows a lightweight pill banner at the top of the screen.
     */
    fun showPill(message: String) {
        resultPill.show(message)
    }

    /**
     * Self-test simulation of the Quick Log sheet (Spec 7.4).
     * Returns true if addView succeeded.
     */
    fun showSelfTest(): Boolean {
        if (!Settings.canDrawOverlays(appContext)) return false
        show(OverlayRequest.SelfTest)
        return true
    }

    companion object {
        val SCRIM_COLOR = Color.Black.copy(alpha = 0.45f)
    }
}
