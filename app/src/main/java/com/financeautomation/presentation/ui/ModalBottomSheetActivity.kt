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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.financeautomation.FinanceApplication
import com.financeautomation.presentation.components.AppleExpensePromptSheet
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.viewmodel.ExpenseViewModel

/**
 * Transparent Activity hosting the interactive Floating Modal Bottom Sheet for expense splitting and categorization.
 * Floats directly over the home screen or third-party UPI apps (Dynamic Island / Apple Shortcuts style)
 * with a dimmed translucent backdrop and 1-tap outside dismissal.
 */
class ModalBottomSheetActivity : ComponentActivity() {

    private val expenseViewModel: ExpenseViewModel by viewModels {
        val app = application as FinanceApplication
        ExpenseViewModel.provideFactory(
            transactionRepository = app.appContainer.transactionRepository,
            peopleRepository = app.appContainer.peopleRepository,
            categoryRepository = app.appContainer.categoryRepository,
            accountRepository = app.appContainer.accountRepository,
            pendingCaptureDaoProvider = { app.appContainer.awaitDatabase().pendingCaptureDao() },
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
                val state by expenseViewModel.uiState.collectAsState()

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
                    // Floating Bottom Sheet Island (Apple Shortcuts & Dynamic Island Style)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                            .heightIn(max = 540.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* consume click to prevent backdrop dismissal */ },
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        shadowElevation = 24.dp
                    ) {
                        AppleExpensePromptSheet(
                            state = state,
                            onAmountChanged = expenseViewModel::onAmountChanged,
                            onMerchantChanged = expenseViewModel::onMerchantChanged,
                            onCategorySelected = expenseViewModel::onCategorySelected,
                            onSplitModeChanged = expenseViewModel::onSplitModeChanged,
                            onCompanionToggled = expenseViewModel::onCompanionToggled,
                            onAddCompanion = expenseViewModel::onAddNewCompanion,
                            onCustomShareChanged = expenseViewModel::onCustomShareChanged,
                            onConfirmAndSave = expenseViewModel::onConfirmAndSave,
                            onVoid = expenseViewModel::onVoid,
                            onCancel = { dismissSafely() }
                        )
                    }
                }
            }
        }
    }

    private fun dismissSafely() {
        expenseViewModel.onDismissCapture()
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
                expenseViewModel.loadCapture(captureId)
            }
            !txnId.isNullOrBlank() -> {
                expenseViewModel.loadTransaction(txnId)
            }
            else -> {
                expenseViewModel.clearState()
            }
        }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID: String = "com.financeautomation.extra.TRANSACTION_ID"
        const val EXTRA_CAPTURE_ID: String = "com.financeautomation.extra.CAPTURE_ID"
    }
}
