package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.usecase.LivingBurn
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

    data class FakeExpenseRow(
        val desc: String,    // Col D
        val tripTag: String, // Col E
        val category: String,// Col F
        val totalAmount: Double, // Col G
        val myShare: Double  // Col I
    )

    data class FakeInflowRow(
        val inflowType: String, // Col Q
        val reason: String,     // Col S
        val amount: Double      // Col T
    )

    private fun extractFunctionCalls(text: String, funcName: String): List<String> {
        val results = mutableListOf<String>()
        var searchIdx = 0
        while (true) {
            val start = text.indexOf("$funcName(", searchIdx)
            if (start == -1) break
            var depth = 0
            var i = start + funcName.length
            var end = -1
            while (i < text.length) {
                if (text[i] == '(') depth++
                else if (text[i] == ')') {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
                i++
            }
            if (end != -1) {
                results.add(text.substring(start, end + 1))
                searchIdx = end + 1
            } else {
                break
            }
        }
        return results
    }

    private fun splitTopLevelArgs(inner: String): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var inQuotes = false
        var cur = StringBuilder()
        for (ch in inner) {
            if (ch == '"') {
                inQuotes = !inQuotes
                cur.append(ch)
            } else if (!inQuotes && ch == '(') {
                depth++
                cur.append(ch)
            } else if (!inQuotes && ch == ')') {
                depth--
                cur.append(ch)
            } else if (!inQuotes && depth == 0 && ch == ',') {
                args.add(cur.toString().trim())
                cur = StringBuilder()
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) {
            args.add(cur.toString().trim())
        }
        return args
    }

    private fun evaluateSumifs(
        sumifsCall: String,
        expenses: List<FakeExpenseRow>,
        inflows: List<FakeInflowRow>
    ): Double {
        val inner = sumifsCall.trim().removePrefix("SUMIFS(").removeSuffix(")")
        val args = splitTopLevelArgs(inner)
        if (args.isEmpty()) return 0.0

        val colRegex = Regex("""!([A-Z])4:[A-Z]""")
        val sumCol = colRegex.find(args[0])?.groupValues?.get(1) ?: return 0.0

        val criteria = mutableListOf<Pair<String, String>>()
        var i = 1
        while (i + 1 < args.size) {
            val col = colRegex.find(args[i])?.groupValues?.get(1) ?: ""
            val rawCrit = args[i + 1].trim().trim('"')
            if (col.isNotEmpty()) {
                criteria.add(col to rawCrit)
            }
            i += 2
        }

        fun matches(value: String, criterion: String): Boolean = when {
            criterion.startsWith("<>") -> {
                val target = criterion.removePrefix("<>")
                if (target.endsWith("*")) {
                    !value.startsWith(target.removeSuffix("*"), ignoreCase = true)
                } else {
                    !value.equals(target, ignoreCase = true)
                }
            }
            criterion == "?*" -> value.isNotEmpty()
            criterion == "" -> value.isEmpty()
            criterion.endsWith("*") -> value.startsWith(criterion.removeSuffix("*"), ignoreCase = true)
            else -> value.equals(criterion, ignoreCase = true)
        }

        return if (sumCol == "I") {
            expenses.filter { row ->
                criteria.all { (col, crit) ->
                    val cellVal = when (col) {
                        "D" -> row.desc
                        "E" -> row.tripTag
                        "F" -> row.category
                        "G" -> row.totalAmount.toString()
                        "I" -> row.myShare.toString()
                        else -> ""
                    }
                    matches(cellVal, crit)
                }
            }.sumOf { it.myShare }
        } else if (sumCol == "T") {
            inflows.filter { row ->
                criteria.all { (col, crit) ->
                    val cellVal = when (col) {
                        "Q" -> row.inflowType
                        "S" -> row.reason
                        "T" -> row.amount.toString()
                        else -> ""
                    }
                    matches(cellVal, crit)
                }
            }.sumOf { it.amount }
        } else {
            0.0
        }
    }

    private fun evaluateBurnFormula(
        formula: String,
        expenses: List<FakeExpenseRow>,
        inflows: List<FakeInflowRow>
    ): Double {
        val sumifsMatches = extractFunctionCalls(formula, "SUMIFS")
        if (sumifsMatches.isEmpty()) return 0.0
        val spent = evaluateSumifs(sumifsMatches[0], expenses, inflows)
        var refunds = 0.0
        for (i in 1 until sumifsMatches.size) {
            refunds += evaluateSumifs(sumifsMatches[i], expenses, inflows)
        }
        return maxOf(0.0, spent - refunds)
    }

    @Test
    fun testInsightTableSlicesAddUpToBurnKpi_withTripSavingsAndTransfer() {
        val window = LivingBurn.monthWindow(2026, 11)
        val testTs = window.first + 1000L

        // Real domain transactions for authoritative LivingBurn calculation
        val txns = listOf(
            Transaction(id = "1", timestamp = testTs, dateSerial = 46000, timeSerial = 0.5, account = "HDFC", merchant = "Supermarket", category = "Groceries", totalAmount = 1200.0, myShare = 1200.0, owedToMe = 0.0, status = TransactionStatus.PERSONAL, tripTag = "", reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED),
            Transaction(id = "2", timestamp = testTs, dateSerial = 46000, timeSerial = 0.5, account = "HDFC", merchant = "Cafe", category = "Dining", totalAmount = 800.0, myShare = 800.0, owedToMe = 0.0, status = TransactionStatus.PERSONAL, tripTag = "", reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED),
            Transaction(id = "3", timestamp = testTs, dateSerial = 46000, timeSerial = 0.5, account = "HDFC", merchant = "Goa Hotel", category = "Dining", totalAmount = 2500.0, myShare = 2500.0, owedToMe = 0.0, status = TransactionStatus.PERSONAL, tripTag = "Goa2026", reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED),
            Transaction(id = "4", timestamp = testTs, dateSerial = 46000, timeSerial = 0.5, account = "HDFC", merchant = "Monthly SIP", category = "Savings", totalAmount = 10000.0, myShare = 10000.0, owedToMe = 0.0, status = TransactionStatus.PERSONAL, tripTag = "", reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED),
            Transaction(id = "5", timestamp = testTs, dateSerial = 46000, timeSerial = 0.5, account = "HDFC", merchant = "Bank Transfer", category = "Transfer", totalAmount = 5000.0, myShare = 5000.0, owedToMe = 0.0, status = TransactionStatus.PERSONAL, tripTag = "", reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED),
            Transaction(id = "6", timestamp = testTs, dateSerial = 46000, timeSerial = 0.5, account = "HDFC", merchant = "[Void] Cancelled Booking", category = "Other", totalAmount = 0.0, myShare = 0.0, owedToMe = 0.0, status = TransactionStatus.VOID, tripTag = "", reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED),
            Transaction(id = "7", timestamp = testTs, dateSerial = 46000, timeSerial = 0.5, account = "HDFC", merchant = "[Void] Hand Edited NonZero Void", category = "Dining", totalAmount = 750.0, myShare = 750.0, owedToMe = 0.0, status = TransactionStatus.VOID, tripTag = "", reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED)
        )
        val domainInflows = listOf(
            Inflow(id = "inf_1", timestamp = testTs, amount = 600.0, account = "HDFC", inflowType = "Refund / Return", source = "Store", note = "Merchant return")
        )

        // Simulated fake sheet ledger mirroring the transactions and inflows
        val sheetExpenses = listOf(
            FakeExpenseRow(desc = "Supermarket", tripTag = "", category = "Groceries", totalAmount = 1200.0, myShare = 1200.0),
            FakeExpenseRow(desc = "Cafe", tripTag = "", category = "Dining", totalAmount = 800.0, myShare = 800.0),
            FakeExpenseRow(desc = "Goa Hotel", tripTag = "Goa2026", category = "Dining", totalAmount = 2500.0, myShare = 2500.0),
            FakeExpenseRow(desc = "Monthly SIP", tripTag = "", category = "Savings", totalAmount = 10000.0, myShare = 10000.0),
            FakeExpenseRow(desc = "Bank Transfer", tripTag = "", category = "Transfer", totalAmount = 5000.0, myShare = 5000.0),
            FakeExpenseRow(desc = "[Void] Cancelled Booking", tripTag = "", category = "Other", totalAmount = 0.0, myShare = 0.0),
            FakeExpenseRow(desc = "[Void] Hand Edited NonZero Void", tripTag = "", category = "Dining", totalAmount = 750.0, myShare = 750.0)
        )
        val sheetInflows = listOf(
            FakeInflowRow(inflowType = "Refund / Return", reason = "Merchant return", amount = 600.0)
        )

        // 1. Authoritative living burn from domain calculation (LivingBurn rule: non-void minus refunds, excluding Savings and Transfer)
        val burnKpiExpected = LivingBurn.total(txns, window, emptySet(), domainInflows)
        assertEquals(3900.0, burnKpiExpected, 0.001)

        // 2. Evaluate Burn KPI formula generated by DashboardFormulaBuilder
        val burnFormula = DashboardFormulaBuilder.burnFormula("Nov26", emptyList())
        val evaluatedBurnKpi = evaluateBurnFormula(burnFormula, sheetExpenses, sheetInflows)
        assertEquals("Burn KPI formula must evaluate to LivingBurn domain total", burnKpiExpected, evaluatedBurnKpi, 0.001)

        // 3. Evaluate Insights Table slices generated by SheetProvisioner
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
                rowIdx != null && colIdx != null && rowIdx in 64..85 && colIdx in listOf(20, 21)
            }

        // Verify Savings and Transfer are NOT in the insights slices, but Trips and Refunds are
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
        assertTrue("Insights slices must contain Refunds", sliceLabels.contains("Refunds"))

        // Extract formula cells (col index 21) and evaluate each slice using the evaluator
        val formulaCells = cellUpdates
            .filter {
                val range = it["range"] as? Map<*, *>
                val start = it["start"] as? Map<*, *>
                val colIdx = (range?.get("startColumnIndex") ?: start?.get("columnIndex")) as? Int
                colIdx == 21
            }

        var evaluatedSliceSum = 0.0
        for (cell in formulaCells) {
            @Suppress("UNCHECKED_CAST")
            val row = (cell["rows"] as List<Map<String, Any?>>).first()
            @Suppress("UNCHECKED_CAST")
            val formula = ((row["values"] as List<Map<String, Any?>>).first()["userEnteredValue"] as Map<*, *>)["formulaValue"] as String
            val sliceValue = if (formula.startsWith("=-")) {
                val call = extractFunctionCalls(formula, "SUMIFS").firstOrNull()
                if (call != null) -evaluateSumifs(call, sheetExpenses, sheetInflows) else 0.0
            } else {
                // If formula references Column U (like U65), resolve with category name
                val resolvedFormula = if (formula.contains("U65")) {
                    formula.replace("U65", "\"Groceries\"")
                } else if (formula.contains("U66")) {
                    formula.replace("U66", "\"Dining\"")
                } else if (formula.contains("U67")) {
                    formula.replace("U67", "\"Other\"")
                } else {
                    formula
                }
                val call = extractFunctionCalls(resolvedFormula, "SUMIFS").firstOrNull()
                if (call != null) evaluateSumifs(call, sheetExpenses, sheetInflows) else 0.0
            }
            evaluatedSliceSum += sliceValue
        }

        // The evaluated slice sum MUST equal the authoritative LivingBurn and match Burn KPI formula!
        assertEquals("Insights slices sum must match LivingBurn domain calculation", burnKpiExpected, evaluatedSliceSum, 0.001)
        assertEquals("Insights slices sum must match Dashboard burn KPI exactly", evaluatedBurnKpi, evaluatedSliceSum, 0.001)
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

    @Test
    fun testTitleRowsEndWithDarkNavyBackgroundAndWhiteText() {
        val darkNavy = mapOf("red" to 0.09803922f, "green" to 0.13333334f, "blue" to 0.49411765f, "alpha" to 1.0f)
        val white = mapOf("red" to 1.0f, "green" to 1.0f, "blue" to 1.0f, "alpha" to 1.0f)

        // 1. Subscriptions tab initial requests
        val subReqs = SheetProvisioner.buildSubscriptionsRequests(sheetId = 9, year = 2026)
        val subA1 = findCellUpdate(subReqs, rowIndex = 0, colIndex = 0)
        assertNotNull("Subscriptions A1 update must exist", subA1)
        val subFormat = (((subA1!!["rows"] as List<*>).first() as Map<*, *>)["values"] as List<*>).first() as Map<*, *>
        val subUef = subFormat["userEnteredFormat"] as Map<*, *>
        assertEquals(darkNavy, subUef["backgroundColor"])
        val subTf = subUef["textFormat"] as Map<*, *>
        assertEquals(white, subTf["foregroundColor"])

        // 2. Trips tab initial requests
        val tripReqs = SheetProvisioner.buildTripsRequests(sheetId = 8, year = 2026)
        val tripA1 = findCellUpdate(tripReqs, rowIndex = 0, colIndex = 0)
        assertNotNull("Trips A1 update must exist", tripA1)
        val tripFormat = (((tripA1!!["rows"] as List<*>).first() as Map<*, *>)["values"] as List<*>).first() as Map<*, *>
        val tripUef = tripFormat["userEnteredFormat"] as Map<*, *>
        assertEquals(darkNavy, tripUef["backgroundColor"])
        val tripTf = tripUef["textFormat"] as Map<*, *>
        assertEquals(white, tripTf["foregroundColor"])

        // 3. Subscriptions title repair requests
        val subRepairReqs = SheetProvisioner.buildSubscriptionsTitleRepairRequests(sheetId = 9)
        val subRepairA1 = findCellUpdate(subRepairReqs, rowIndex = 0, colIndex = 0)
        assertNotNull("Subscriptions repair A1 update must exist", subRepairA1)
        val subRepairFormat = (((subRepairA1!!["rows"] as List<*>).first() as Map<*, *>)["values"] as List<*>).first() as Map<*, *>
        val subRepairUef = subRepairFormat["userEnteredFormat"] as Map<*, *>
        assertEquals(darkNavy, subRepairUef["backgroundColor"])
        val subRepairTf = subRepairUef["textFormat"] as Map<*, *>
        assertEquals(white, subRepairTf["foregroundColor"])

        // 4. Trips title repair requests
        val tripRepairReqs = SheetProvisioner.buildTripsTitleRepairRequests(sheetId = 8)
        val tripRepairA1 = findCellUpdate(tripRepairReqs, rowIndex = 0, colIndex = 0)
        assertNotNull("Trips repair A1 update must exist", tripRepairA1)
        val tripRepairFormat = (((tripRepairA1!!["rows"] as List<*>).first() as Map<*, *>)["values"] as List<*>).first() as Map<*, *>
        val tripRepairUef = tripRepairFormat["userEnteredFormat"] as Map<*, *>
        assertEquals(darkNavy, tripRepairUef["backgroundColor"])
        val tripRepairTf = tripRepairUef["textFormat"] as Map<*, *>
        assertEquals(white, tripRepairTf["foregroundColor"])
    }
}
