package com.financeautomation.presentation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Secondary floating pill overlay displayed at the top centre of the screen (Phase 5.3.5 / R10).
 * Displays a confirmation message and an optional Undo action active for 8 seconds.
 */
class ResultPillOverlay(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main)

    private var pillView: ComposeView? = null
    private var treeOwners: ComposeOverlayTreeOwners? = null
    private var autoHideJob: Job? = null

    var activeSession: PillUndoSession? = null
        private set

    /**
     * Triggers the undo action if still within the 8-second window.
     */
    fun triggerUndo(): Boolean {
        val session = activeSession
        activeSession = null
        val triggered = session?.triggerUndo() ?: false
        dismiss()
        return triggered
    }

    /**
     * Displays a result pill capsule with the provided message and optional Undo callback.
     */
    fun show(
        message: String,
        transactionId: String? = null,
        onUndo: ((String) -> Unit)? = null
    ) {
        if (!Settings.canDrawOverlays(appContext)) return

        scope.launch {
            try {
                dismiss()

                val session = if (transactionId != null && onUndo != null) {
                    PillUndoSession(transactionId = transactionId, windowMillis = 8000L, onUndo = onUndo).also {
                        it.startTimer(scope) {
                            dismiss()
                        }
                    }
                } else null
                activeSession = session

                val durationMs = if (session != null) 8000L else 3000L

                val density = appContext.resources.displayMetrics.density
                val topMarginPx = (48 * density).toInt()

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = topMarginPx
                }

                val owners = ComposeOverlayTreeOwners()
                treeOwners = owners

                val composeView = ComposeView(appContext).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        FinanceAutomationTheme {
                            Surface(
                                shape = FinanceTheme.shapes.pill,
                                color = FinanceTheme.colors.primaryContainer,
                                shadowElevation = FinanceTheme.spacing.s,
                                tonalElevation = FinanceTheme.sizes.elevationSheet,
                                modifier = Modifier
                                    .padding(horizontal = FinanceTheme.spacing.l)
                                    .clickable { dismiss() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.s),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = FinanceTheme.colors.primary,
                                        modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                    )
                                    Text(
                                        text = message,
                                        style = FinanceTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(start = FinanceTheme.spacing.s)
                                    )
                                    if (session != null) {
                                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.m))
                                        Text(
                                            text = "Undo",
                                            style = FinanceTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable { triggerUndo() }
                                                .padding(horizontal = FinanceTheme.spacing.xs, vertical = FinanceTheme.spacing.xxs)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                owners.attachTo(composeView)
                windowManager.addView(composeView, layoutParams)
                pillView = composeView

                autoHideJob = scope.launch {
                    delay(durationMs)
                    dismiss()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("ResultPillOverlay", "${e.javaClass.simpleName}: show pill")
            }
        }
    }

    /**
     * Dismisses the active pill overlay and cleans up tree owners.
     */
    fun dismiss() {
        autoHideJob?.cancel()
        autoHideJob = null
        activeSession?.expire()
        activeSession = null

        pillView?.let { view ->
            try {
                treeOwners?.destroy()
                windowManager.removeView(view)
            } catch (e: Exception) {
                Diagnostics.record("ResultPillOverlay", "${e.javaClass.simpleName}: dismiss")
            } finally {
                pillView = null
                treeOwners = null
            }
        }
    }
}
