package com.financeautomation.presentation.viewmodel

import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingPlanJsonAdapter

/** Whether discarding a queued item has to be confirmed because part of it may already be on the sheet. */
object DiscardWarning {

    /**
     * New rows are written before changes to existing ones, so an entry that saved a plan with anything in it may be
     * partly written. A carry writes the same way and saves no plan, so it always counts.
     */
    fun isNeeded(item: SyncQueueEntity): Boolean {
        if (item.operationType == SyncQueueEntity.OP_CARRY_FORWARD) return true
        val planJson = item.planJson ?: return false
        // A plan that cannot be read may have been written: ask
        val plan = runCatching { AccountingPlanJsonAdapter.fromJson(planJson) }.getOrNull() ?: return true
        return plan.appends.isNotEmpty() || plan.edits.isNotEmpty()
    }
}
