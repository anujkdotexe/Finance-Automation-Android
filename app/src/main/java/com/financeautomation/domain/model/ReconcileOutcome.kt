package com.financeautomation.domain.model

/**
 * What one reconcile with the sheet did. [reconciled] counts local rows changed from the sheet, [pruned] counts local
 * entries removed because they are no longer in the sheet, and [manyMissing] is true when a pass found so many
 * entries missing that nothing was removed and the user should look at the sheet first.
 */
data class ReconcileOutcome(
    val reconciled: Int,
    val pruned: Int = 0,
    val manyMissing: Boolean = false
)

/** Pending message about entries the sheet no longer holds. [pruned] is read once, [manyMissing] stays until resolved. */
data class SheetPruneNotice(
    val pruned: Int = 0,
    val manyMissing: Boolean = false
)
