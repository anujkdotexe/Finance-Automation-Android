package com.financeautomation.domain.model

/** Why an edit of an expense was refused. */
enum class EditBlockReason {
    /** The debt of this expense was carried into a later month. */
    DEBT_CARRIED,

    /** Amounts or status on the sheet are not what this device last saw. */
    SHEET_CHANGED
}

/** Thrown, or recorded on a queued item, when an edit is refused. Nothing of it is written. */
class EditBlockedException(val reason: EditBlockReason) : IllegalStateException("Edit blocked: ${reason.name}")
