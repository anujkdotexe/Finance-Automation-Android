package com.financeautomation.presentation.components

import androidx.annotation.StringRes
import com.financeautomation.R
import com.financeautomation.domain.model.VoidDecision

/**
 * Pure utility functions for selecting dialog wording on transaction void actions.
 */
object VoidDialogUtils {

    /**
     * Chooses the confirmation dialog message string resource based on whether
     * the "Also delete this entry from the sheet" checkbox is checked.
     * When ticked: says the entry will be removed from the sheet and the app.
     * Unticked or no box: says the entry will be struck through in the ledger.
     */
    /** Why a void was refused. Every screen that can void shows this same text. */
    @StringRes
    fun blockMessageResId(reason: VoidDecision.BlockReason): Int = when (reason) {
        VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID -> R.string.void_blocked_debt_repaid
        VoidDecision.BlockReason.DEBT_OFFSET -> R.string.void_blocked_debt_offset
        VoidDecision.BlockReason.AMBIGUOUS_DEBT_MATCH -> R.string.void_blocked_ambiguous
        VoidDecision.BlockReason.DEBT_CARRIED_FORWARD -> R.string.void_blocked_debt_carried
        VoidDecision.BlockReason.SHEET_READ_FAILED -> R.string.void_blocked_sheet_error
    }

    @StringRes
    fun voidConfirmMessageResId(deleteSheetRow: Boolean): Int {
        return if (deleteSheetRow) {
            R.string.confirm_void_delete_message
        } else {
            R.string.confirm_void_message
        }
    }
}
