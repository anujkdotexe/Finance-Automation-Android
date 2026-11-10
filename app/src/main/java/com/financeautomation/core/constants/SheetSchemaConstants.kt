package com.financeautomation.core.constants

/**
 * 31-Column master spreadsheet constants strictly adhering to master ledger rules.
 * All transactions logged by the app conform to this 28-business-column + 3-hidden-UUID schema.
 */
object SheetSchemaConstants {

    // Total columns across all 3 tables + spacers + system UUIDs
    const val TOTAL_COLUMN_COUNT: Int = 31

    // 31 column letters from A to AE
    val SHEET_COLUMNS: List<String> = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K",
        "L",
        "M", "N", "O", "P", "Q", "R", "S", "T",
        "U",
        "V", "W", "X", "Y", "Z", "AA", "AB",
        "AC", "AD", "AE"
    )

    // Column headers mapping A to AE matching iOS master template
    val SHEET_COLUMN_HEADERS: Map<String, String> = mapOf(
        "A" to "Date",
        "B" to "Time",
        "C" to "Account",
        "D" to "Item / Description",
        "E" to "Trip / Event Tag",
        "F" to "Category",
        "G" to "Total Amount (₹)",
        "H" to "Paid For / Companions",
        "I" to "My Share (₹)",
        "J" to "Owed To Me (₹)",
        "K" to "Status",
        "L" to "Spacer 1",
        "M" to "Date",
        "N" to "Time",
        "O" to "Account",
        "P" to "Inflow Type",
        "Q" to "Received From",
        "R" to "Reason / Note",
        "S" to "Amount Received (₹)",
        "T" to "Linked Debit Row",
        "U" to "Spacer 2",
        "V" to "Date Incurred",
        "W" to "Time",
        "X" to "Paid By",
        "Y" to "What For",
        "Z" to "Amount I Owe (₹)",
        "AA" to "Date Repaid",
        "AB" to "Repayment Status",
        "AC" to "Debit Txn UUID",
        "AD" to "Inflow Txn UUID",
        "AE" to "Payable Txn UUID"
    )

    // Table 1: Debits / Expenses (Cols A-K, indices 0-10)
    const val TABLE_1_DEBITS_RANGE: String = "A3:K"
    const val TABLE_1_START_COL_INDEX: Int = 0
    const val TABLE_1_END_COL_INDEX: Int = 10

    // Spacer 1 (Col L, index 11)
    const val SPACER_1_COL_INDEX: Int = 11
    const val SPACER_1_COL_LETTER: String = "L"

    // Table 2: Credits / Inflows (Cols M-T, indices 12-19)
    const val TABLE_2_INFLOWS_RANGE: String = "M3:T"
    const val TABLE_2_START_COL_INDEX: Int = 12
    const val TABLE_2_END_COL_INDEX: Int = 19

    // Spacer 2 (Col U, index 20)
    const val SPACER_2_COL_INDEX: Int = 20
    const val SPACER_2_COL_LETTER: String = "U"

    // Table 3: Payables / Liabilities (Cols V-AB, indices 21-27)
    const val TABLE_3_PAYABLES_RANGE: String = "V3:AB"
    const val TABLE_3_START_COL_INDEX: Int = 21
    const val TABLE_3_END_COL_INDEX: Int = 27

    // Hidden System UUID Columns (Cols AC-AE, indices 28-30)
    const val COL_INDEX_DEBIT_UUID: Int = 28
    const val COL_LETTER_DEBIT_UUID: String = "AC"
    const val COL_NAME_DEBIT_UUID: String = "Debit Txn UUID"

    const val COL_INDEX_INFLOW_UUID: Int = 29
    const val COL_LETTER_INFLOW_UUID: String = "AD"
    const val COL_NAME_INFLOW_UUID: String = "Inflow Txn UUID"

    const val COL_INDEX_PAYABLE_UUID: Int = 30
    const val COL_LETTER_PAYABLE_UUID: String = "AE"
    const val COL_NAME_PAYABLE_UUID: String = "Payable Txn UUID"

    // Brand and Section Colors
    const val COLOR_BANNER_NAVY: String = "#1A237E"
    const val COLOR_HEADER_DEBITS: String = "#C62828"
    const val COLOR_HEADER_INFLOWS: String = "#2E7D32"
    const val COLOR_HEADER_PAYABLES: String = "#F57F17"
    const val COLOR_HEADER_SLATE: String = "#37474F"
    const val COLOR_SPACER_GRAY: String = "#ECEFF1"

    // Standardized Status values strictly adhering to master ledger rules
    const val STATUS_PERSONAL: String = "Personal"
    const val STATUS_PENDING: String = "Pending"
    const val STATUS_SETTLED: String = "Settled"
    const val STATUS_CARRIED_FWD: String = "Carried Fwd"
    const val STATUS_VOID: String = "Void"
    const val STATUS_PARTIAL: String = "Partial"
    const val STATUS_REPAID: String = "Repaid"

    // Backward-compatible alias
    const val STATUS_CARRIED: String = STATUS_CARRIED_FWD

    val VALID_STATUS_VALUES: Set<String> = setOf(
        STATUS_PERSONAL,
        STATUS_PENDING,
        STATUS_SETTLED,
        STATUS_CARRIED_FWD,
        STATUS_VOID
    )

    // Standard plural keyword rule
    const val SAVINGS_KEYWORD: String = "Savings"
    const val FORBIDDEN_SINGULAR_SAVING: String = "Saving"

    fun columnLetter(index: Int): String {
        require(index in 0 until TOTAL_COLUMN_COUNT) {
            "Column index $index out of bounds [0, ${TOTAL_COLUMN_COUNT - 1}]"
        }
        return SHEET_COLUMNS[index]
    }

    fun columnIndex(letter: String): Int {
        val idx = SHEET_COLUMNS.indexOf(letter.uppercase())
        require(idx >= 0) { "Column letter $letter not found in sheet schema" }
        return idx
    }
}
