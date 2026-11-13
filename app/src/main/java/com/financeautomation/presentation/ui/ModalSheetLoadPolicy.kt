package com.financeautomation.presentation.ui

/**
 * What the floating sheet may load for an intent. While the app lock is active a capture or an existing transaction
 * is never loaded; the lock prompt comes first. A plain add stays usable, as decided for writes.
 */
object ModalSheetLoadPolicy {

    enum class Action { LOAD_CAPTURE, LOAD_TRANSACTION, SHOW_LOCK_PROMPT, MANUAL }

    fun decide(captureId: String?, txnId: String?, locked: Boolean): Action {
        val hasCapture = !captureId.isNullOrBlank()
        val hasTxn = !txnId.isNullOrBlank()
        return when {
            locked && (hasCapture || hasTxn) -> Action.SHOW_LOCK_PROMPT
            hasCapture -> Action.LOAD_CAPTURE
            hasTxn -> Action.LOAD_TRANSACTION
            else -> Action.MANUAL
        }
    }
}
