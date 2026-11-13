package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable

/**
 * Pure domain UseCase executing non-destructive in-place void logic for Debits and Payables.
 * Port of void logic from Code.gs v2.3.0 and spec section 3.5 / 9.8.
 * Preserves table contiguousness by zeroing amounts, prefixing description with "[Void]",
 * and updating status to "Void", while keeping the row coordinate and UUID intact.
 * Zero Android framework dependencies.
 */
class VoidTransactionUseCase {

    /**
     * Voids a Table 1 debit row in-place without deleting or blanking the row.
     * Edits:
     * - Column D (Item): prefixed with "[Void] " if not already present
     * - Column G (Total): 0.0
     * - Column I (My Share): 0.0
     * - Column J (Owed To Me): 0.0
     * - Column K (Status): "Void"
     * Preserves the row's UUID in column AC.
     */
    fun voidDebit(row: LedgerDebit): AccountingPlan {
        val voidDesc = if (row.item.startsWith("[Void] ")) {
            row.item
        } else {
            "[Void] ${row.item}"
        }

        val edits = listOf(
            CellEdit(tab = row.tab, row = row.row, column = "D", value = voidDesc),
            CellEdit(tab = row.tab, row = row.row, column = "G", value = 0.0),
            CellEdit(tab = row.tab, row = row.row, column = "I", value = 0.0),
            CellEdit(tab = row.tab, row = row.row, column = "J", value = 0.0),
            CellEdit(tab = row.tab, row = row.row, column = "K", value = "Void")
        )

        return AccountingPlan(
            edits = edits,
            message = "Voided debit in ${row.tab} row ${row.row}"
        )
    }

    /**
     * Voids a Table 2 inflow row in-place, with the same convention as a debit: the row and its UUID in column AD
     * stay, the amount is zeroed and the text is prefixed with "[Void] ". Table 2 has no status column.
     * Edits:
     * - Column R (Reason): prefixed with "[Void] " if not already present
     * - Column S (Amount Received): 0.0
     */
    fun voidInflow(tab: String, row: Int, currentReason: String): AccountingPlan {
        val voidReason = if (currentReason.startsWith("[Void] ")) currentReason else "[Void] $currentReason"
        return AccountingPlan(
            edits = listOf(
                CellEdit(tab = tab, row = row, column = "R", value = voidReason),
                CellEdit(tab = tab, row = row, column = "S", value = 0.0)
            ),
            message = "Voided inflow in $tab row $row"
        )
    }

    /**
     * Voids a Table 3 payable row in-place without deleting or blanking the row.
     * Edits:
     * - Column Y (What For): prefixed with "[Void] " if not already present
     * - Column Z (Amount Owed): 0.0
     * - Column AB (Status): "Void"
     * Preserves the row's UUID in column AE.
     */
    fun voidPayable(row: LedgerPayable): AccountingPlan {
        val voidDesc = if (row.whatFor.startsWith("[Void] ")) {
            row.whatFor
        } else {
            "[Void] ${row.whatFor}"
        }

        val edits = listOf(
            CellEdit(tab = row.tab, row = row.row, column = "Y", value = voidDesc),
            CellEdit(tab = row.tab, row = row.row, column = "Z", value = 0.0),
            CellEdit(tab = row.tab, row = row.row, column = "AB", value = "Void")
        )

        return AccountingPlan(
            edits = edits,
            message = "Voided payable in ${row.tab} row ${row.row}"
        )
    }
}

