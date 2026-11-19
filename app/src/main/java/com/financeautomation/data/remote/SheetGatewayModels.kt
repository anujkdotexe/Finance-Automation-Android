package com.financeautomation.data.remote

/**
 * Snapshot representation of all historical and current month tabs across a workbook.
 * Consumed by the accounting engine for waterfalls, bilateral netting, settlements, and carry-forward.
 */
data class LedgerWorkbookSnapshot(
    val spreadsheetId: String,
    val monthTabs: List<MonthTabSnapshot>
)

/**
 * Snapshot of a single fiscal month tab (e.g. "Nov26") containing Table 1 Debits and Table 3 Payables.
 */
data class MonthTabSnapshot(
    val tabName: String,
    val sheetId: Int,
    val debits: List<LedgerDebitRow>,
    val payables: List<LedgerPayableRow>,
    val inflows: List<LedgerInflowRow> = emptyList()
)

/**
 * A row from Table 1 (Cols A to L plus AD hidden UUID) carrying its 1-based sheet row coordinate.
 */
data class LedgerDebitRow(
    val rowNumber: Int,
    val dateSerial: Int?,
    val timeSerial: Double?,
    val account: String,
    val description: String,
    val tripTag: String?,
    val category: String,
    val totalAmount: Double,
    val companions: String,
    val myShare: Double,
    val owedToMe: Double,
    val status: String,
    val uuid: String?,
    val note: String = ""
)

/**
 * A row from Table 3 (Cols W to AC plus AF hidden UUID) carrying its 1-based sheet row coordinate.
 */
data class LedgerPayableRow(
    val rowNumber: Int,
    val dateIncurredSerial: Int?,
    val timeSerial: Double?,
    val paidBy: String,
    val whatFor: String,
    val amountIOwe: Double,
    val dateRepaidSerial: Int?,
    val repaymentStatus: String,
    val uuid: String?
)

/**
 * A row from the Subscriptions tab (Cols A to L).
 */
data class SheetSubscriptionRow(
    val rowNumber: Int,
    val name: String,
    val amount: Double?,
    val frequency: String,
    val dueDateString: String,
    val account: String,
    val active: Boolean,
    val note: String,
    val keywords: String,
    val id: String?,
    val closedDateString: String = ""
)

/**
 * A row from the Trips tab (Cols A to K).
 */
data class SheetTripRow(
    val rowNumber: Int,
    val name: String,
    val startDateString: String = "",
    val endDateString: String = "",
    val status: String = "Active",
    val days: Int? = null,
    val totalPaid: Double? = null,
    val myShare: Double? = null,
    val owedToMe: Double? = null,
    val repaid: Double? = null,
    val topCategory: String = "",
    val id: String? = null
)

/**
 * A row from Table 2 (Cols N to U plus AE hidden UUID) carrying its 1-based sheet row coordinate.
 */
data class LedgerInflowRow(
    val rowNumber: Int,
    val dateSerial: Int?,
    val timeSerial: Double?,
    val account: String,
    val inflowType: String,
    val sender: String,
    val reason: String,
    val amountReceived: Double,
    val linkedDebits: String,
    val uuid: String?
)

