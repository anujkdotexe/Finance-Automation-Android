package com.financeautomation.data.repository

/**
 * Remembers which delivered income entries settled debts on the sheet. Voiding such an income cannot restore the
 * debts (the earlier companion, owed and status cells are not kept), so the void is refused with [BLOCKED_MESSAGE].
 */
object SettledInflowMarker {
    const val BLOCKED_MESSAGE: String =
        "This income settled earlier debts, so it cannot be voided here. Reopen those debts in the sheet first."

    fun key(inflowId: String): String = "inflow_settled_debts_$inflowId"
}
