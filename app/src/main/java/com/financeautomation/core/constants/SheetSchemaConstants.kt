package com.financeautomation.core.constants

/**
 * 32-Column master spreadsheet constants adhering to master ledger rules with Expense Note at Col L.
 * All transactions logged by the app conform to this 29-business-column + 3-hidden-UUID schema.
 * Backward-compatible V1 constants (31 columns) are preserved for reading old workbooks.
 */
object SheetSchemaConstants {

    // Total columns across all 3 tables + spacers + system UUIDs
    const val TOTAL_COLUMN_COUNT_V1: Int = 31
    const val TOTAL_COLUMN_COUNT: Int = 32

    // 31 column letters from A to AE (V1)
    val SHEET_COLUMNS_V1: List<String> = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K",
        "L",
        "M", "N", "O", "P", "Q", "R", "S", "T",
        "U",
        "V", "W", "X", "Y", "Z", "AA", "AB",
        "AC", "AD", "AE"
    )

    // 32 column letters from A to AF (V2 with Note at Col L)
    val SHEET_COLUMNS: List<String> = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
        "M",
        "N", "O", "P", "Q", "R", "S", "T", "U",
        "V",
        "W", "X", "Y", "Z", "AA", "AB", "AC",
        "AD", "AE", "AF"
    )

    // Column headers mapping A to AF (Table 1: A-L, Spacer 1: M, Table 2: N-U, Spacer 2: V, Table 3: W-AC, UUIDs: AD-AF)
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
        "L" to "Note",
        "M" to "Spacer 1",
        "N" to "Date",
        "O" to "Time",
        "P" to "Account",
        "Q" to "Inflow Type",
        "R" to "Received From",
        "S" to "Reason / Note",
        "T" to "Amount Received (₹)",
        "U" to "Linked Debit Row",
        "V" to "Spacer 2",
        "W" to "Date Incurred",
        "X" to "Time",
        "Y" to "Paid By",
        "Z" to "What For",
        "AA" to "Amount I Owe (₹)",
        "AB" to "Date Repaid",
        "AC" to "Repayment Status",
        "AD" to "Debit Txn UUID",
        "AE" to "Inflow Txn UUID",
        "AF" to "Payable Txn UUID"
    )

    // Table 1: Debits / Expenses (Cols A-L, indices 0-11)
    const val TABLE_1_DEBITS_RANGE: String = "A3:L"
    const val TABLE_1_START_COL_INDEX: Int = 0
    const val TABLE_1_END_COL_INDEX: Int = 11

    const val COL_INDEX_EXPENSE_NOTE: Int = 11
    const val COL_LETTER_EXPENSE_NOTE: String = "L"

    // Spacer 1 (Col M, index 12)
    const val SPACER_1_COL_INDEX: Int = 12
    const val SPACER_1_COL_LETTER: String = "M"

    // Table 2: Credits / Inflows (Cols N-U, indices 13-20)
    const val TABLE_2_INFLOWS_RANGE: String = "N3:U"
    const val TABLE_2_START_COL_INDEX: Int = 13
    const val TABLE_2_END_COL_INDEX: Int = 20

    // Spacer 2 (Col V, index 21)
    const val SPACER_2_COL_INDEX: Int = 21
    const val SPACER_2_COL_LETTER: String = "V"

    // Table 3: Payables / Liabilities (Cols W-AC, indices 22-28)
    const val TABLE_3_PAYABLES_RANGE: String = "W3:AC"
    const val TABLE_3_START_COL_INDEX: Int = 22
    const val TABLE_3_END_COL_INDEX: Int = 28

    // Hidden System UUID Columns (Cols AD-AF, indices 29-31)
    const val COL_INDEX_DEBIT_UUID: Int = 29
    const val COL_LETTER_DEBIT_UUID: String = "AD"
    const val COL_NAME_DEBIT_UUID: String = "Debit Txn UUID"

    const val COL_INDEX_INFLOW_UUID: Int = 30
    const val COL_LETTER_INFLOW_UUID: String = "AE"
    const val COL_NAME_INFLOW_UUID: String = "Inflow Txn UUID"

    const val COL_INDEX_PAYABLE_UUID: Int = 31
    const val COL_LETTER_PAYABLE_UUID: String = "AF"
    const val COL_NAME_PAYABLE_UUID: String = "Payable Txn UUID"

    // V1 Backward Compatibility Constants (31 columns)
    const val TABLE_1_DEBITS_RANGE_V1: String = "A3:K"
    const val TABLE_1_END_COL_INDEX_V1: Int = 10
    const val SPACER_1_COL_INDEX_V1: Int = 11
    const val SPACER_1_COL_LETTER_V1: String = "L"
    const val TABLE_2_INFLOWS_RANGE_V1: String = "M3:T"
    const val TABLE_2_START_COL_INDEX_V1: Int = 12
    const val TABLE_2_END_COL_INDEX_V1: Int = 19
    const val SPACER_2_COL_INDEX_V1: Int = 20
    const val SPACER_2_COL_LETTER_V1: String = "U"
    const val TABLE_3_PAYABLES_RANGE_V1: String = "V3:AB"
    const val TABLE_3_START_COL_INDEX_V1: Int = 21
    const val TABLE_3_END_COL_INDEX_V1: Int = 27
    const val COL_INDEX_DEBIT_UUID_V1: Int = 28
    const val COL_LETTER_DEBIT_UUID_V1: String = "AC"
    const val COL_INDEX_INFLOW_UUID_V1: Int = 29
    const val COL_LETTER_INFLOW_UUID_V1: String = "AD"
    const val COL_INDEX_PAYABLE_UUID_V1: Int = 30
    const val COL_LETTER_PAYABLE_UUID_V1: String = "AE"

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

    // Subscriptions Tab Constants
    const val SUBSCRIPTIONS_TAB_NAME: String = "Subscriptions"
    const val SUBSCRIPTIONS_HEADER_ROW_INDEX: Int = 2 // Row 3
    const val SUBSCRIPTIONS_DATA_START_ROW_INDEX: Int = 3 // Row 4
    const val SUBSCRIPTIONS_COLUMN_COUNT: Int = 13
    const val SUBSCRIPTIONS_RANGE: String = "A4:M"

    const val COL_INDEX_SUB_NAME: Int = 0
    const val COL_INDEX_SUB_AMOUNT: Int = 1
    const val COL_INDEX_SUB_FREQUENCY: Int = 2
    const val COL_INDEX_SUB_DUE_DATE: Int = 3
    const val COL_INDEX_SUB_ACCOUNT: Int = 4
    const val COL_INDEX_SUB_ACTIVE: Int = 5
    const val COL_INDEX_SUB_NOTE: Int = 6
    const val COL_INDEX_SUB_KEYWORDS: Int = 7
    const val COL_INDEX_SUB_PER_MONTH: Int = 8
    const val COL_INDEX_SUB_PAID_MONTH: Int = 9
    const val COL_INDEX_SUB_PAID_YEAR: Int = 10
    const val COL_INDEX_SUB_LAST_PAID_DATE: Int = 11
    const val COL_INDEX_SUB_ID: Int = 12
    const val COL_LETTER_SUB_ID: String = "M"

    // Trips Tab Constants
    const val TRIPS_TAB_NAME: String = "Trips"
    const val TRIPS_HEADER_ROW_INDEX: Int = 2 // Row 3
    const val TRIPS_DATA_START_ROW_INDEX: Int = 3 // Row 4
    const val TRIPS_COLUMN_COUNT: Int = 11
    const val TRIPS_RANGE: String = "A4:K"

    const val COL_INDEX_TRIP_NAME: Int = 0
    const val COL_INDEX_TRIP_START_DATE: Int = 1
    const val COL_INDEX_TRIP_END_DATE: Int = 2
    const val COL_INDEX_TRIP_STATUS: Int = 3
    const val COL_INDEX_TRIP_DAYS: Int = 4
    const val COL_INDEX_TRIP_TOTAL_PAID: Int = 5
    const val COL_INDEX_TRIP_MY_SHARE: Int = 6
    const val COL_INDEX_TRIP_OWED_TO_ME: Int = 7
    const val COL_INDEX_TRIP_REPAID: Int = 8
    const val COL_INDEX_TRIP_TOP_CATEGORY: Int = 9
    const val COL_INDEX_TRIP_ID: Int = 10
    const val COL_LETTER_TRIP_ID: String = "K"

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
