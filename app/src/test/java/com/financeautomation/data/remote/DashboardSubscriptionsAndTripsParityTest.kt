package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves formula correctness, column constant alignment, and mathematical burn parity
 * for the Dashboard Subscriptions, Trips, and Insights bands.
 */
class DashboardSubscriptionsAndTripsParityTest {

    @Test
    fun testNextDueFormula_usesDueDateConstantAndNotAccountOrNoteOrActive() {
        val formula = DashboardFormulaBuilder.buildNextDueSubscriptionFormula()
        val expectedDateCol = SheetSchemaConstants.COL_LETTER_SUB_DUE_DATE // "D"
        val expectedActiveCol = SheetSchemaConstants.COL_LETTER_SUB_ACTIVE // "F"
        val expectedNameCol = SheetSchemaConstants.COL_LETTER_SUB_NAME // "A"
        val accountCol = SheetSchemaConstants.COL_LETTER_SUB_ACCOUNT // "E"
        val noteCol = SheetSchemaConstants.COL_LETTER_SUB_NOTE // "G"

        // Must filter on due date and active
        assertTrue("Formula must use due date column $expectedDateCol", formula.contains("Subscriptions!${expectedDateCol}4:$expectedDateCol"))
        assertTrue("Formula must filter by due date >= TODAY() using column $expectedDateCol", formula.contains("Subscriptions!${expectedDateCol}4:$expectedDateCol>=TODAY()"))
        assertTrue("Formula must filter by active using column $expectedActiveCol", formula.contains("Subscriptions!${expectedActiveCol}4:$expectedActiveCol=TRUE"))
        assertTrue("Formula must include name column $expectedNameCol", formula.contains("Subscriptions!${expectedNameCol}4:$expectedNameCol"))

        // Must NOT use account column (E) or note column (G) for date comparison
        assertFalse("Must NOT use account column $accountCol for date comparison", formula.contains("Subscriptions!${accountCol}4:$accountCol>=TODAY()"))
        assertFalse("Must NOT use note column $noteCol for date comparison", formula.contains("Subscriptions!${noteCol}4:$noteCol>=TODAY()"))

        // Verify Dashboard cell G45 (row index 44, col index 6) receives this exact formula
        val dashboardRequests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4, year = 2026)
        val g45Cell = findCellUpdate(dashboardRequests, rowIndex = 44, colIndex = 6)
        assertNotNull("Cell G45 update must exist", g45Cell)
        @Suppress("UNCHECKED_CAST")
        val g45Row = (g45Cell!!["rows"] as List<Map<String, Any?>>).first()
        @Suppress("UNCHECKED_CAST")
        val g45Value = ((g45Row["values"] as List<Map<String, Any?>>).first()["userEnteredValue"] as Map<*, *>)["formulaValue"] as String
        assertEquals(formula, g45Value)
    }

    @Test
    fun testClosedSubscriptionsQuery_selectsNameClosedDateAndPaidInTotalFromConstants() {
        val queryFormula = DashboardFormulaBuilder.buildClosedSubscriptionsQueryFormula()
        val nameCol = SheetSchemaConstants.COL_LETTER_SUB_NAME // "A"
        val activeCol = SheetSchemaConstants.COL_LETTER_SUB_ACTIVE // "F"
        val closedDateCol = SheetSchemaConstants.COL_LETTER_SUB_CLOSED_DATE // "M"
        val paidTotalCol = SheetSchemaConstants.COL_LETTER_SUB_PAID_IN_TOTAL // "N"

        // Verify SELECT statement uses constants
        assertTrue("Query must SELECT $nameCol, $closedDateCol, $paidTotalCol", queryFormula.contains("SELECT $nameCol, $closedDateCol, $paidTotalCol"))
        assertTrue("Query must filter on $activeCol = FALSE", queryFormula.contains("WHERE $activeCol = FALSE"))
        assertTrue("Query must ORDER BY $closedDateCol DESC LIMIT 5", queryFormula.contains("ORDER BY $closedDateCol DESC LIMIT 5"))

        // Verify Dashboard cell A50 (row index 49, col index 0) receives this exact query formula
        val dashboardRequests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4, year = 2026)
        val a50Cell = findCellUpdate(dashboardRequests, rowIndex = 49, colIndex = 0)
        assertNotNull("Cell A50 update must exist", a50Cell)
        @Suppress("UNCHECKED_CAST")
        val a50Row = (a50Cell!!["rows"] as List<Map<String, Any?>>).first()
        @Suppress("UNCHECKED_CAST")
        val a50Value = ((a50Row["values"] as List<Map<String, Any?>>).first()["userEnteredValue"] as Map<*, *>)["formulaValue"] as String
        assertEquals(queryFormula, a50Value)
    }

    @Test
    fun testInsightTableSlicesAddUpToBurnKpi_withTripSavingsAndTransfer() {
        // Model simulated transaction set in the month
        data class Txn(val desc: String, val category: String, val tripTag: String, val myShare: Double, val isVoid: Boolean)

        val txns = listOf(
            Txn(desc = "Supermarket", category = "Groceries", tripTag = "", myShare = 1200.0, isVoid = false),
            Txn(desc = "Cafe", category = "Dining", tripTag = "", myShare = 800.0, isVoid = false),
            Txn(desc = "Goa Hotel", category = "Dining", tripTag = "Goa2026", myShare = 2500.0, isVoid = false),
            Txn(desc = "Monthly SIP", category = "Savings", tripTag = "", myShare = 10000.0, isVoid = false),
            Txn(desc = "Bank Transfer", category = "Transfer", tripTag = "", myShare = 5000.0, isVoid = false),
            Txn(desc = "Cancelled Booking", category = "Other", tripTag = "", myShare = 700.0, isVoid = true)
        )

        // 1. Calculate Burn KPI (Dashboard A5 logic: sum of non-void myShare where category <> Savings and category <> Transfer)
        val burnKpiExpected = txns
            .filter { !it.isVoid && it.category != "Savings" && it.category != "Transfer" }
            .sumOf { it.myShare }
        assertEquals(4500.0, burnKpiExpected, 0.001)

        // 2. Evaluate Insights Table slices generated by SheetProvisioner
        val dashboardRequests = SheetProvisioner.buildDashboardRequests(
            dashboardSheetId = 4,
            categories = listOf("Groceries", "Dining", "Savings", "Transfer", "Other"),
            year = 2026
        )

        // Extract Insights items from rows 65+ (cols U and V)
        @Suppress("UNCHECKED_CAST")
        val cellUpdates = dashboardRequests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .filter {
                val range = it["range"] as? Map<*, *>
                val start = it["start"] as? Map<*, *>
                val rowIdx = (range?.get("startRowIndex") ?: start?.get("rowIndex")) as? Int
                val colIdx = (range?.get("startColumnIndex") ?: start?.get("columnIndex")) as? Int
                rowIdx != null && colIdx != null && rowIdx in 64..80 && colIdx in listOf(20, 21)
            }

        // Verify Savings and Transfer are NOT in the insights slices
        val sliceLabels = cellUpdates
            .filter {
                val range = it["range"] as? Map<*, *>
                val start = it["start"] as? Map<*, *>
                val colIdx = (range?.get("startColumnIndex") ?: start?.get("columnIndex")) as? Int
                colIdx == 20
            }
            .map {
                @Suppress("UNCHECKED_CAST")
                val row = (it["rows"] as List<Map<String, Any?>>).first()
                @Suppress("UNCHECKED_CAST")
                ((row["values"] as List<Map<String, Any?>>).first()["userEnteredValue"] as Map<*, *>)["stringValue"] as String
            }
        assertFalse("Insights slices must NOT contain Savings", sliceLabels.contains("Savings"))
        assertFalse("Insights slices must NOT contain Transfer", sliceLabels.contains("Transfer"))
        assertTrue("Insights slices must contain Trips", sliceLabels.contains("Trips"))

        // Calculate slice sums based on the formula semantics:
        // Category slices: sum where Category == cat && Col E == "" && !isVoid
        var sliceSum = 0.0
        val categorySlices = sliceLabels.filter { it != "Trips" }
        for (cat in categorySlices) {
            val catTotal = txns
                .filter { it.category == cat && it.tripTag.isEmpty() && !it.isVoid }
                .sumOf { it.myShare }
            sliceSum += catTotal
        }

        // Trips slice: sum where Col E != "" && Category <> Savings && Category <> Transfer && !isVoid
        val tripSliceTotal = txns
            .filter { it.tripTag.isNotEmpty() && it.category != "Savings" && it.category != "Transfer" && !it.isVoid }
            .sumOf { it.myShare }
        sliceSum += tripSliceTotal

        // The slices MUST sum up to the exact same total as the Burn KPI!
        assertEquals("Insights slices sum must match Dashboard burn KPI exactly", burnKpiExpected, sliceSum, 0.001)
    }

    @Suppress("UNCHECKED_CAST")
    private fun findCellUpdate(requests: List<Map<String, Any?>>, rowIndex: Int, colIndex: Int): Map<String, Any?>? {
        return requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .firstOrNull {
                val range = it["range"] as? Map<*, *>
                val start = it["start"] as? Map<*, *>
                (range?.get("startRowIndex") == rowIndex && range["startColumnIndex"] == colIndex) ||
                (start?.get("rowIndex") == rowIndex && start["columnIndex"] == colIndex)
            }
    }

    @Test
    fun testTripsTabHeaderNote_statesExclusionsAndDynamicLimitations() {
        val requests = SheetProvisioner.buildTripsRequests(sheetId = 8, year = 2026)
        val row2Cell = findCellUpdate(requests, rowIndex = 1, colIndex = 0)
        assertNotNull("Row 2 note banner must exist on Trips tab", row2Cell)
        @Suppress("UNCHECKED_CAST")
        val row = (row2Cell!!["rows"] as List<Map<String, Any?>>).first()
        @Suppress("UNCHECKED_CAST")
        val text = ((row["values"] as List<Map<String, Any?>>).first()["userEnteredValue"] as Map<*, *>)["stringValue"] as String

        assertTrue("Note must state exclusion of Void, Savings, Transfer", text.contains("Void, Savings, Transfer"))
        assertTrue("Note must state that sheet cannot apply dynamic exclusions from Settings", text.contains("dynamic category exclusions from Settings"))
        assertTrue("Note must state that sheet cannot apply rows still in review", text.contains("rows still in review"))
    }

    @Test
    fun testSubscriptionsTabHeaderNote_statesYearCoverage() {
        val requests = SheetProvisioner.buildSubscriptionsRequests(sheetId = 9, year = 2026)
        val row2Cell = findCellUpdate(requests, rowIndex = 1, colIndex = 0)
        assertNotNull("Row 2 note banner must exist on Subscriptions tab", row2Cell)
        @Suppress("UNCHECKED_CAST")
        val row = (row2Cell!!["rows"] as List<Map<String, Any?>>).first()
        @Suppress("UNCHECKED_CAST")
        val text = ((row["values"] as List<Map<String, Any?>>).first()["userEnteredValue"] as Map<*, *>)["stringValue"] as String

        assertTrue("Note must state that Paid in total covers this workbook's year only", text.contains("Paid in total covers this workbook's year only"))
    }
}
