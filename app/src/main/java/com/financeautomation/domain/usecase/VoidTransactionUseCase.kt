package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.SheetSchemaConstants
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
            CellEdit(tab = row.tab, row = row.row, column = SheetSchemaConstants.COL_LETTER_DEBIT_DESCRIPTION, value = voidDesc),
            CellEdit(tab = row.tab, row = row.row, column = SheetSchemaConstants.COL_LETTER_DEBIT_TOTAL_AMOUNT, value = 0.0),
            CellEdit(tab = row.tab, row = row.row, column = SheetSchemaConstants.COL_LETTER_DEBIT_MY_SHARE, value = 0.0),
            CellEdit(tab = row.tab, row = row.row, column = SheetSchemaConstants.COL_LETTER_DEBIT_OWED_TO_ME, value = 0.0),
            CellEdit(tab = row.tab, row = row.row, column = SheetSchemaConstants.COL_LETTER_DEBIT_STATUS, value = "Void")
        )

        return AccountingPlan(
            edits = edits,
            message = "Voided debit in ${row.tab} row ${row.row}"
        )
    }

    /**
     * Voids a Table 2 inflow row in-place, with the same convention as a debit: the row and its UUID in column AE
     * stay, the amount is zeroed and the text is prefixed with "[Void] ". Table 2 has no status column.
     * Edits:
     * - Column S (Reason): prefixed with "[Void] " if not already present
     * - Column T (Amount Received): 0.0
     */
    fun voidInflow(tab: String, row: Int, currentReason: String): AccountingPlan {
        val voidReason = if (currentReason.startsWith("[Void] ")) currentReason else "[Void] $currentReason"
        return AccountingPlan(
            edits = listOf(
                CellEdit(tab = tab, row = row, column = SheetSchemaConstants.COL_LETTER_INFLOW_REASON, value = voidReason),
                CellEdit(tab = tab, row = row, column = SheetSchemaConstants.COL_LETTER_INFLOW_AMOUNT, value = 0.0)
            ),
            message = "Voided inflow in $tab row $row"
        )
    }

    /**
     * Voids a Table 3 payable row in-place without deleting or blanking the row.
     * Edits:
     * - Column Z (What For): prefixed with "[Void] " if not already present
     * - Column AA (Amount I Owe): 0.0
     * - Column AC (Status): "Void"
     * Preserves the row's UUID in column AF.
     */
    fun voidPayable(row: LedgerPayable): AccountingPlan {
        val voidDesc = if (row.whatFor.startsWith("[Void] ")) {
            row.whatFor
        } else {
            "[Void] ${row.whatFor}"
        }

        val edits = listOf(
            CellEdit(tab = row.tab, row = row.row, column = SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR, value = voidDesc),
            CellEdit(tab = row.tab, row = row.row, column = SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE, value = 0.0),
            CellEdit(tab = row.tab, row = row.row, column = SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, value = "Void")
        )

        return AccountingPlan(
            edits = edits,
            message = "Voided payable in ${row.tab} row ${row.row}"
        )
    }
}

