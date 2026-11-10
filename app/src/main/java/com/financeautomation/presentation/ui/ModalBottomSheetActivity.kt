package com.financeautomation.presentation.ui

import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import com.financeautomation.FinanceApplication
import com.financeautomation.R
import com.financeautomation.presentation.components.QuickLogSheet
import com.financeautomation.presentation.components.SheetScaffold
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.QuickLogViewModel
import com.financeautomation.presentation.viewmodel.QuickLogViewModelFactory

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
            ioDispatcher = app.appContainer.ioDispatcher
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

        handleIntent(intent)

        setContent {
            FinanceAutomationTheme(transparentSystemBars = true) {
                val state by quickLogViewModel.uiState.collectAsState()

                LaunchedEffect(state.isSaved, state.isVoided) {
                    if (state.isSaved || state.isVoided) {
                        dismissSafely()
                    }
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
                    // Floating Bottom Sheet using unified SheetScaffold
                    SheetScaffold(
                        title = when {
                            state.transactionId != null -> stringResource(R.string.prompt_enter_amount)
                            state.captureId != null -> stringResource(R.string.badge_payment_intercepted)
                            else -> stringResource(R.string.prompt_enter_amount)
                        },
                        onDismiss = { dismissSafely() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m)
                    ) {
                        QuickLogSheet(
                            state = state,
                            onAmountChanged = quickLogViewModel::onAmountChanged,
                            onPayeeChanged = quickLogViewModel::onPayeeChanged,
                            onAccountSelected = quickLogViewModel::onAccountSelected,
                            onCategorySelected = quickLogViewModel::onCategorySelected,
                            onSplitModeChanged = quickLogViewModel::onSplitModeChanged,
                            onCompanionToggled = quickLogViewModel::onCompanionToggled,
                            onAddCompanion = quickLogViewModel::onAddNewCompanion,
                            onCustomShareChanged = quickLogViewModel::onCustomShareChanged,
                            onEndTrip = quickLogViewModel::onEndTrip,
                            onConfirmAndSave = { quickLogViewModel.onConfirmAndSave { dismissSafely() } },
                            onDismiss = { dismissSafely() },
                            onVoid = { quickLogViewModel.onVoid { dismissSafely() } }
                        )
                    }
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

    private fun handleIntent(intent: Intent) {
        val captureId = intent.getStringExtra(EXTRA_CAPTURE_ID)
        val txnId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
            ?: intent.getStringExtra("txn_id")
            ?: intent.getStringExtra("txn_uuid")

        when {
            !captureId.isNullOrBlank() -> {
                quickLogViewModel.loadCapture(captureId)
            }
            !txnId.isNullOrBlank() -> {
                quickLogViewModel.loadTransaction(txnId)
            }
            else -> {
                quickLogViewModel.setManualMode()
            }
        }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID: String = "com.financeautomation.extra.TRANSACTION_ID"
        const val EXTRA_CAPTURE_ID: String = "com.financeautomation.extra.CAPTURE_ID"
    }
}
