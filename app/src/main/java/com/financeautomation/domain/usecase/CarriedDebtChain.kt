package com.financeautomation.domain.usecase

import com.financeautomation.domain.utils.AccountingUtils
import kotlin.math.abs

/**
 * One debt row of a workbook, from either table. [party] is who paid (a debt of the user) or who owes (a debt owed to
 * the user); [amount] is the open amount column of that table.
 */
data class ChainRow(
    val uuid: String,
    val tab: String,
    val row: Int,
    val text: String,
    val party: String,
    val dateSerial: Int?,
    val timeSerial: Double?,
    val amount: Double,
    val status: String,
    val repaidDateSerial: Int? = null
)

/** Where a carried debt is now, as far as it can be told without doubt. */
sealed interface CarriedResolution {
    /** Nothing was repaid or offset. [copies] are the carried rows, oldest hop first; the last one is open. */
    data class Untouched(val copies: List<ChainRow>) : CarriedResolution

    /** The debt was repaid in part or in full, or offset, somewhere along the way. */
    data object Touched : CarriedResolution

    /** The carried rows cannot be matched without doubt, for example because they are in another year's workbook. */
    data object Unknown : CarriedResolution
}

/**
 * Follows a "Carried Fwd" row to the row that holds its debt now, so both can be voided together. A hop is accepted
 * only when exactly one row names the previous row's tab in its prefix and agrees on description, party, date, time
 * and amount, and no other carried row could claim it.
 */
object CarriedDebtChain {

    private const val MAX_HOPS = 24

    fun resolve(original: ChainRow, rows: List<ChainRow>, expectedAmount: Double): CarriedResolution {
        if (!sameAmount(original.amount, expectedAmount)) return CarriedResolution.Touched
        if (original.dateSerial == null || original.timeSerial == null || original.party.isBlank()) {
            return CarriedResolution.Unknown
        }
        val description = CarriedCopies.stripPrefixes(original.text)
        if (description.isBlank()) return CarriedResolution.Unknown

        fun sameEntry(row: ChainRow): Boolean =
            CarriedCopies.stripPrefixes(row.text).equals(description, ignoreCase = true) &&
                row.dateSerial == original.dateSerial && row.timeSerial != null &&
                abs(row.timeSerial - original.timeSerial) < AccountingUtils.LEGACY_TIME_SERIAL_TOLERANCE

        val copies = mutableListOf<ChainRow>()
        var node = original
        repeat(MAX_HOPS) {
            // A twin of this row in the same tab could own the next copy just as well
            val twin = rows.any {
                it.tab.equals(node.tab, ignoreCase = true) && it.row != node.row && sameEntry(it) &&
                    it.status.equals("Carried Fwd", ignoreCase = true) &&
                    AccountingUtils.eq(it.party, node.party) && sameAmount(it.amount, node.amount)
            }
            if (twin) return CarriedResolution.Unknown
            val prefix = "[Carried from ${node.tab}] "
            val next = rows.filter {
                !it.tab.equals(node.tab, ignoreCase = true) && it.text.trim().startsWith(prefix, ignoreCase = true) &&
                    sameEntry(it)
            }.singleOrNull() ?: return CarriedResolution.Unknown

            val untouchedAmount = sameAmount(next.amount, expectedAmount)
            if (!AccountingUtils.eq(next.party, original.party)) {
                // A shared debt drops the people who have paid; any other difference is not the same debt for sure
                return if (untouchedAmount) CarriedResolution.Unknown else CarriedResolution.Touched
            }
            if (!untouchedAmount) return CarriedResolution.Touched
            copies.add(next)
            when {
                next.status.equals("Carried Fwd", ignoreCase = true) -> node = next
                next.status.equals("Pending", ignoreCase = true) && next.repaidDateSerial == null ->
                    return CarriedResolution.Untouched(copies)
                next.status.equals("Void", ignoreCase = true) -> return CarriedResolution.Unknown
                else -> return CarriedResolution.Touched
            }
        }
        return CarriedResolution.Unknown
    }

    private fun sameAmount(a: Double, b: Double): Boolean = abs(a - b) < 0.01
}
