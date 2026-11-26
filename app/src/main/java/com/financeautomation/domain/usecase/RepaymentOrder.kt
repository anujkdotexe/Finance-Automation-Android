package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable

/**
 * The one order every waterfall and netting run uses: oldest date first, then time. A row without a date goes after
 * every dated row, and a row without a time after the timed rows of its date.
 */
object RepaymentOrder {

    /**
     * Debts the user owes. The id settles a tie, so the app and the sheet pick the same debt even though they read
     * rows in different orders.
     */
    val payables: Comparator<LedgerPayable> = compareBy<LedgerPayable>(
        { it.dateIncurredSerial ?: Int.MAX_VALUE },
        { it.timeSerial ?: Double.MAX_VALUE },
        { it.uuid.orEmpty().lowercase() }
    )

    /** Debts owed to the user. Only the sheet settles these; a tie keeps the order of the sheet. */
    val debits: Comparator<LedgerDebit> = compareBy<LedgerDebit>(
        { it.dateSerial ?: Int.MAX_VALUE },
        { it.timeSerial ?: Double.MAX_VALUE }
    )
}
