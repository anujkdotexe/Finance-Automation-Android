package com.financeautomation.presentation.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages the timed undo window for result pills (Phase 5.3.5 / R10).
 * Guarantees that undo can only be triggered within windowMillis of creation.
 */
class PillUndoSession(
    val transactionId: String,
    val windowMillis: Long = 8000L,
    private val onUndo: (String) -> Unit
) {
    @Volatile
    var isExpired: Boolean = false
        private set

    @Volatile
    var isTriggered: Boolean = false
        private set

    private var expiryJob: Job? = null

    fun startTimer(scope: CoroutineScope, onExpired: () -> Unit = {}) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(windowMillis)
            expire()
            onExpired()
        }
    }

    fun triggerUndo(): Boolean {
        if (!isExpired && !isTriggered) {
            isTriggered = true
            expiryJob?.cancel()
            expiryJob = null
            onUndo(transactionId)
            return true
        }
        return false
    }

    fun expire() {
        isExpired = true
        expiryJob?.cancel()
        expiryJob = null
    }
}

