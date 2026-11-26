package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.domain.model.EditBlockReason
import com.financeautomation.domain.model.EditBlockedException

/** The words for a refused edit, wherever it is shown. */
object EditBlockText {

    fun resId(reason: EditBlockReason): Int = when (reason) {
        EditBlockReason.DEBT_CARRIED -> R.string.edit_blocked_debt_carried
        EditBlockReason.SHEET_CHANGED -> R.string.edit_blocked_sheet_changed
    }

    /** Null when [error] is not a refused edit. */
    fun of(error: Throwable): UiText? = (error as? EditBlockedException)?.let { UiText.res(resId(it.reason)) }

    /** For the kind recorded on a queued item; null when it is not a refused edit. */
    fun resIdFor(errorKind: String?): Int? = when (errorKind) {
        SyncErrorKind.EDIT_CARRIED.name -> resId(EditBlockReason.DEBT_CARRIED)
        SyncErrorKind.EDIT_SHEET_CHANGED.name -> resId(EditBlockReason.SHEET_CHANGED)
        else -> null
    }
}
