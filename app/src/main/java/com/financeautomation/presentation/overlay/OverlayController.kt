package com.financeautomation.presentation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import com.financeautomation.presentation.components.QuickLogSheet
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.viewmodel.QuickLogViewModel
import com.financeautomation.presentation.viewmodel.QuickLogViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.function.Consumer

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val resultPill = ResultPillOverlay(appContext)
    private val mainScope = CoroutineScope(mainDispatcher)

    private var currentView: ComposeView? = null
    private var currentTreeOwners: ComposeOverlayTreeOwners? = null
    private var currentLayoutParams: WindowManager.LayoutParams? = null
    private var blurListener: Any? = null

    /**
     * Shows an overlay window for the given request.
     */
    fun show(request: OverlayRequest) {
        if (!Settings.canDrawOverlays(appContext)) {
            Diagnostics.record("OverlayController", "show: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

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
                setupWindowBlur(layoutParams)

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

                when (request) {
                    is OverlayRequest.Capture -> viewModel.loadCapture(request.captureId)
                    is OverlayRequest.ManualExpense -> viewModel.setManualMode()
                    is OverlayRequest.ChooseAction -> viewModel.setManualMode()
                    is OverlayRequest.SelfTest -> viewModel.setManualMode()
                    is OverlayRequest.SpecificFlow -> viewModel.setManualMode()
                }

                val composeView = ComposeView(appContext).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        FinanceAutomationTheme {
                            val state by viewModel.uiState.collectAsState()
                            var isVisible by remember { mutableStateOf(false) }

                            LaunchedEffect(Unit) {
                                isVisible = true
                            }

                            // Full screen scrim backdrop for 1-tap outside dismissal
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f))
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
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                            .displayCutoutPadding()
                                            .padding(horizontal = 14.dp, vertical = 12.dp)
                                            .heightIn(max = 580.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { /* consume clicks to prevent backdrop dismissal */ },
                                        shape = RoundedCornerShape(32.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 6.dp,
                                        shadowElevation = 24.dp
                                    ) {
                                        QuickLogSheet(
                                            state = state,
                                            onAmountChanged = viewModel::onAmountChanged,
                                            onPayeeChanged = viewModel::onPayeeChanged,
                                            onAccountSelected = viewModel::onAccountSelected,
                                            onCategorySelected = viewModel::onCategorySelected,
                                            onSplitModeChanged = viewModel::onSplitModeChanged,
                                            onCompanionToggled = viewModel::onCompanionToggled,
                                            onAddCompanion = viewModel::onAddNewCompanion,
                                            onCustomShareChanged = viewModel::onCustomShareChanged,
                                            onEndTrip = viewModel::onEndTrip,
                                            onConfirmAndSave = {
                                                viewModel.onConfirmAndSave { message ->
                                                    dismiss()
                                                    resultPill.show(message)
                                                }
                                            },
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
                }

                treeOwners.attachTo(composeView)
                windowManager.addView(composeView, layoutParams)
                currentView = composeView
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: show failed")
            }
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
        teardownWindowBlur()
        currentView?.let { view ->
            try {
                currentTreeOwners?.destroy()
                windowManager.removeView(view)
            } catch (e: Exception) {
                Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: dismiss")
            } finally {
                currentView = null
                currentTreeOwners = null
                currentLayoutParams = null
            }
        }
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

    private fun setupWindowBlur(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (windowManager.isCrossWindowBlurEnabled) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = 24
            }
            val listener = Consumer<Boolean> { enabled ->
                val view = currentView ?: return@Consumer
                val lp = currentLayoutParams ?: return@Consumer
                if (enabled) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    lp.blurBehindRadius = 24
                } else {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    lp.blurBehindRadius = 0
                }
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (e: Exception) {
                    Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: updateViewLayout blur")
                }
            }
            windowManager.addCrossWindowBlurEnabledListener(listener)
            blurListener = listener
        }
    }

    private fun teardownWindowBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("UNCHECKED_CAST")
            val listener = blurListener as? Consumer<Boolean>
            if (listener != null) {
                try {
                    windowManager.removeCrossWindowBlurEnabledListener(listener)
                } catch (e: Exception) {
                    Diagnostics.record("OverlayController", "${e.javaClass.simpleName}: removeCrossWindowBlurEnabledListener")
                } finally {
                    blurListener = null
                }
            }
        }
    }
}

