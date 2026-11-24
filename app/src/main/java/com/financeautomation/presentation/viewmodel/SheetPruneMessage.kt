package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
import com.financeautomation.domain.model.SheetPruneNotice

/** The message shown after a sync when entries were removed because the sheet no longer holds them. */
object SheetPruneMessage {
    /** Null when there is nothing to tell the user. A held back bulk removal is told only when nothing was removed. */
    fun text(notice: SheetPruneNotice): UiText? = when {
        notice.pruned == 1 -> UiText.res(R.string.sync_msg_entry_pruned_one)
        notice.pruned > 1 -> UiText.res(R.string.sync_msg_entries_pruned, notice.pruned)
        notice.manyMissing -> UiText.res(R.string.sync_msg_many_missing)
        else -> null
    }
}
