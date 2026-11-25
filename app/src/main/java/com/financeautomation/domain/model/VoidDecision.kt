package com.financeautomation.domain.model

/**
 * All-or-nothing decision for voiding an expense.
 */
sealed interface VoidDecision {
    /**
     * An ordinary expense with no matching debt candidate; voids the expense alone.
     */
    object OrdinaryVoid : VoidDecision

    /**
     * Untouched debt found; voids the expense and the debt together.
     */
    data class VoidBoth(
        val payableId: String,
        val sheetRowIndex: Int? = null,
        val tabName: String? = null
    ) : VoidDecision

    /**
     * Void is blocked: change nothing in the sheet, Room, or the queue.
     */
    data class Block(val reason: BlockReason) : VoidDecision

    enum class BlockReason {
        DEBT_PARTIALLY_OR_FULLY_REPAID,
        DEBT_OFFSET,
        AMBIGUOUS_DEBT_MATCH,
        SHEET_READ_FAILED
    }
}

/**
 * Exception thrown when a void operation is blocked under the all-or-nothing rule.
 */
class VoidBlockedException(val reason: VoidDecision.BlockReason) :
    Exception("Void blocked: ${reason.name}")
