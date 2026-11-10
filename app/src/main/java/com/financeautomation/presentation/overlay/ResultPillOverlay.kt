package com.financeautomation.presentation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Secondary floating pill overlay displayed at the top centre of the screen (Phase 5.3.5).
 * Auto-hides after 3 seconds or dismisses upon tap.
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

    /**
     * Displays a result pill capsule with the provided message.
     */
    fun show(message: String) {
        if (!Settings.canDrawOverlays(appContext)) return

        scope.launch {
            try {
                dismiss()

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
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shadowElevation = 8.dp,
                                tonalElevation = 6.dp,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .clickable { dismiss() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                owners.attachTo(composeView)
                windowManager.addView(composeView, layoutParams)
                pillView = composeView

                autoHideJob = scope.launch {
                    delay(3000L)
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

