package com.financeautomation.presentation.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.financeautomation.FinanceApplication
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.presentation.overlay.OverlayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Validates and sanitizes intents incoming to [EntryActivity] to prevent intent injection
 * or unauthorized execution of internal actions by third-party applications.
 */
object EntryIntentValidator {

    val ALLOWED_FLOWS: Set<String> = setOf(
        "EXPENSE",
        "INCOME",
        "INCOME_RETURN",
        "FRIEND_PAID",
        "REPAID",
        "REPAID_FRIEND",
        "TRIP",
        "TRIP_MODE"
    )

    private val CLOSED_CAPTURE_STATES: Set<String> = setOf(
        PendingCaptureEntity.STATE_DONE,
        PendingCaptureEntity.STATE_DISMISSED
    )

    /**
     * Validates a raw flow string against the known whitelist.
     * Returns the normalized uppercase flow name if permitted, or null if unknown.
     */
    fun validateFlow(rawFlow: String?): String? {
        if (rawFlow.isNullOrBlank()) return null
        val normalized = rawFlow.trim().uppercase()
        return if (normalized in ALLOWED_FLOWS) normalized else null
    }

    /**
     * Checks if a pending capture exists and has an open state (not DONE or DISMISSED).
     */
    fun isCaptureOpen(state: String?): Boolean {
        if (state.isNullOrBlank()) return false
        return state !in CLOSED_CAPTURE_STATES
    }

    /**
     * Filters raw incoming extras keys to guarantee that no unvalidated extras or injection
     * payloads are ever forwarded to internal activities.
     */
    fun sanitizeExtraKeys(incomingKeys: Set<String>): Set<String> {
        val allowed = setOf(
            EntryActivity.EXTRA_CAPTURE_ID,
            EntryActivity.EXTRA_FLOW,
            ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH
        )
        return incomingKeys.filter { it in allowed }.toSet()
    }

    /**
     * Builds a sanitized fallback intent for [ModalBottomSheetActivity].
     * Copies strictly only the validated capture ID and flow name; never forwards raw extras.
     */
    fun buildFallbackIntent(
        context: Context,
        validatedCaptureId: String?,
        validatedFlow: String?,
        authIntent: PendingIntent?
    ): Intent {
        return Intent(context, ModalBottomSheetActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!validatedCaptureId.isNullOrBlank()) {
                putExtra(EntryActivity.EXTRA_CAPTURE_ID, validatedCaptureId)
            }
            if (!validatedFlow.isNullOrBlank()) {
                putExtra(EntryActivity.EXTRA_FLOW, validatedFlow)
            }
            if (authIntent != null) {
                putExtra(ModalBottomSheetActivity.EXTRA_INTERNAL_AUTH, authIntent)
            }
        }
    }
}

/**
 * Transparent trampoline entry point for Widget, Quick Settings Tile, Pinned Shortcut, and Notifications.
 * Exported for Android system shortcuts, but hardened against intent injection from arbitrary apps.
 */
class EntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawCaptureId = intent.getStringExtra(EXTRA_CAPTURE_ID)
        val rawFlow = intent.getStringExtra(EXTRA_FLOW)
            ?: intent.getStringExtra(EXTRA_ACTION)
            ?: intent.getStringExtra("flow")

        val validatedFlow = EntryIntentValidator.validateFlow(rawFlow)

        if (rawCaptureId.isNullOrBlank()) {
            route(validatedCaptureId = null, validatedFlow = validatedFlow)
        } else {
            val app = application as FinanceApplication
            CoroutineScope(Dispatchers.IO).launch {
                val db = app.appContainer.awaitDatabase()
                val capture = db.pendingCaptureDao().getById(rawCaptureId)
                val isOpen = EntryIntentValidator.isCaptureOpen(capture?.state)
                val validCaptureId = if (isOpen) rawCaptureId else null

                withContext(Dispatchers.Main) {
                    route(validatedCaptureId = validCaptureId, validatedFlow = validatedFlow)
                }
            }
        }
    }

    private fun route(validatedCaptureId: String?, validatedFlow: String?) {
        val app = application as FinanceApplication

        if (Settings.canDrawOverlays(this)) {
            val request = when {
                !validatedCaptureId.isNullOrBlank() -> OverlayRequest.Capture(validatedCaptureId)
                !validatedFlow.isNullOrBlank() -> OverlayRequest.SpecificFlow(validatedFlow)
                else -> OverlayRequest.ChooseAction
            }
            app.appContainer.overlayController.show(request)
            finish()
        } else {
            val authIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val fallbackIntent = EntryIntentValidator.buildFallbackIntent(
                this,
                validatedCaptureId,
                validatedFlow,
                authIntent
            )
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
