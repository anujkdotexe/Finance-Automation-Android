package com.financeautomation.presentation

import com.financeautomation.presentation.ui.ModalSheetLoadPolicy
import com.financeautomation.presentation.ui.ModalSheetLoadPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** R2-30: while the app lock is active the sheet shows the lock prompt first and loads no capture or transaction. */
class ModalSheetLoadPolicyTest {

    @Test
    fun lockedCaptureOrTransactionGoesToTheLockPrompt() {
        assertEquals(Action.SHOW_LOCK_PROMPT, ModalSheetLoadPolicy.decide("cap_1", null, locked = true))
        assertEquals(Action.SHOW_LOCK_PROMPT, ModalSheetLoadPolicy.decide(null, "txn_1", locked = true))
    }

    @Test
    fun lockedPlainAddStaysUsable() {
        assertEquals(Action.MANUAL, ModalSheetLoadPolicy.decide(null, null, locked = true))
    }

    @Test
    fun unlockedLoadsWhatWasAsked() {
        assertEquals(Action.LOAD_CAPTURE, ModalSheetLoadPolicy.decide("cap_1", "txn_1", locked = false))
        assertEquals(Action.LOAD_TRANSACTION, ModalSheetLoadPolicy.decide(null, "txn_1", locked = false))
        assertEquals(Action.MANUAL, ModalSheetLoadPolicy.decide("", null, locked = false))
    }
}
