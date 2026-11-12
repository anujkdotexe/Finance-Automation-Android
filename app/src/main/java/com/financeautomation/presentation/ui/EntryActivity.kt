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
        val flow = intent.getStringExtra(EXTRA_FLOW)
            ?: intent.getStringExtra(EXTRA_ACTION)
            ?: intent.getStringExtra("flow")

        if (Settings.canDrawOverlays(this)) {
            val request = when {
                !captureId.isNullOrBlank() -> OverlayRequest.Capture(captureId)
                !flow.isNullOrBlank() -> OverlayRequest.SpecificFlow(flow)
                else -> OverlayRequest.ChooseAction
            }
            app.appContainer.overlayController.show(request)
            finish()
        } else {
            // Spec 5.5 Fallback: Start ModalBottomSheetActivity when overlay permission is absent
            val authIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val fallbackIntent = Intent(this, ModalBottomSheetActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtras(intent)
                putExtra(ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH, authIntent)
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
        const val EXTRA_FLOW: String = "com.financeautomation.extra.FLOW"
    }
}

