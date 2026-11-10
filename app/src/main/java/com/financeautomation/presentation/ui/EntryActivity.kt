package com.financeautomation.presentation.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.financeautomation.FinanceApplication
import com.financeautomation.presentation.overlay.OverlayRequest

/**
 * Transparent trampoline entry point for Widget, Quick Settings Tile, Pinned Shortcut, and Notifications (Spec 5.2.1).
 * Immediately delegates to OverlayController when overlay permission is granted, or falls back to ModalBottomSheetActivity.
 */
class EntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as FinanceApplication
        val captureId = intent.getStringExtra(EXTRA_CAPTURE_ID)

        if (Settings.canDrawOverlays(this)) {
            val request = if (!captureId.isNullOrBlank()) {
                OverlayRequest.Capture(captureId)
            } else {
                OverlayRequest.ChooseAction
            }
            app.appContainer.overlayController.show(request)
            finish()
        } else {
            // Spec 5.5 Fallback: Start ModalBottomSheetActivity when overlay permission is absent
            val fallbackIntent = Intent(this, ModalBottomSheetActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtras(intent)
            }
            startActivity(fallbackIntent)
            finish()
        }
        applyNoAnimation()
    }

    override fun finish() {
        super.finish()
        applyNoAnimation()
    }

    private fun applyNoAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_CAPTURE_ID: String = "com.financeautomation.extra.CAPTURE_ID"
        const val EXTRA_ACTION: String = "com.financeautomation.extra.ACTION"
    }
}

