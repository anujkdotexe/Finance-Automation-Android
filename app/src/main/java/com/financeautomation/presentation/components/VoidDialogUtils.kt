package com.financeautomation.presentation.components

import androidx.annotation.StringRes
import com.financeautomation.R

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
    @StringRes
    fun voidConfirmMessageResId(deleteSheetRow: Boolean): Int {
        return if (deleteSheetRow) {
            R.string.confirm_void_delete_message
        } else {
            R.string.confirm_void_message
        }
    }
}
