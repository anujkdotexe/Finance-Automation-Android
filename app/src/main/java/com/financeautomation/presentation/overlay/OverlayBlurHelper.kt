package com.financeautomation.presentation.overlay

import android.os.Build
import android.view.View
import android.view.WindowManager
import com.financeautomation.core.utils.Diagnostics
import java.util.function.Consumer

/**
 * Hardware cross-window blur coordinator for API 31+ (Android 12+).
 */
class OverlayBlurHelper(
    private val windowManager: WindowManager
) {
    companion object {
        const val DEFAULT_BLUR_RADIUS: Int = 24
    }

    private var blurListener: Any? = null

    fun setupWindowBlur(
        params: WindowManager.LayoutParams,
        viewProvider: () -> View?,
        layoutParamsProvider: () -> WindowManager.LayoutParams?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (windowManager.isCrossWindowBlurEnabled) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = DEFAULT_BLUR_RADIUS
            }
            val listener = Consumer<Boolean> { enabled ->
                val view = viewProvider() ?: return@Consumer
                val lp = layoutParamsProvider() ?: return@Consumer
                if (enabled) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    lp.blurBehindRadius = DEFAULT_BLUR_RADIUS
                } else {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    lp.blurBehindRadius = 0
                }
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (e: Exception) {
                    Diagnostics.record("OverlayBlurHelper", "${e.javaClass.simpleName}: updateViewLayout blur")
                }
            }
            windowManager.addCrossWindowBlurEnabledListener(listener)
            blurListener = listener
        }
    }

    fun teardownWindowBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("UNCHECKED_CAST")
            val listener = blurListener as? Consumer<Boolean>
            if (listener != null) {
                try {
                    windowManager.removeCrossWindowBlurEnabledListener(listener)
                } catch (e: Exception) {
                    Diagnostics.record("OverlayBlurHelper", "${e.javaClass.simpleName}: removeCrossWindowBlurEnabledListener")
                } finally {
                    blurListener = null
                }
            }
        }
    }
}

