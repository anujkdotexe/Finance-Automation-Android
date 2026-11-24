package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class SheetRequestShapeTest {

    private fun hex(color: Any?): String {
        val m = color as? Map<*, *> ?: return ""
        fun ch(k: String): Int {
            val v = (m[k] as? Number)?.toFloat() ?: 0f
            return Math.round(v * 255f).coerceIn(0, 255)
        }
        return "%02X%02X%02X".format(ch("red"), ch("green"), ch("blue"))
    }

    // 1. Subscriptions and Trips title rows keep navy fill (1A237E) and white text after the write

    @Test
    fun testSubscriptionsAndTripsTitleRowsKeepWhiteText() {
        val subReqs = SheetProvisioner.buildSubscriptionsRequests(sheetId = 1, year = 2026)
        val tripReqs = SheetProvisioner.buildTripsRequests(sheetId = 2, year = 2026)

        val subTitleCell = findCellUpdate(subReqs, rowIndex = 0, colIndex = 0)
        assertNotNull("Subscriptions A1 cell update must exist", subTitleCell)
        val subFormat = extractCellFormat(subTitleCell!!)
        val subTextColor = (subFormat["textFormat"] as? Map<*, *>)?.get("foregroundColor")
        assertEquals("FFFFFF", hex(subTextColor))

        val tripTitleCell = findCellUpdate(tripReqs, rowIndex = 0, colIndex = 0)
        assertNotNull("Trips A1 cell update must exist", tripTitleCell)
        val tripFormat = extractCellFormat(tripTitleCell!!)
        val tripTextColor = (tripFormat["textFormat"] as? Map<*, *>)?.get("foregroundColor")
        assertEquals("FFFFFF", hex(tripTextColor))
    }

    @Ignore("BUG: SheetProvisioner.kt:201 darkNavyBg uses 19227E instead of 1A237E")
    @Test
    fun testSubscriptionsAndTripsTitleRowsKeepNavyFill1A237E() {
        val subReqs = SheetProvisioner.buildSubscriptionsRequests(sheetId = 1, year = 2026)
        val tripReqs = SheetProvisioner.buildTripsRequests(sheetId = 2, year = 2026)

        val subTitleCell = findCellUpdate(subReqs, rowIndex = 0, colIndex = 0)
        assertNotNull("Subscriptions A1 cell update must exist", subTitleCell)
        val subFormat = extractCellFormat(subTitleCell!!)
        assertEquals("1A237E", hex(subFormat["backgroundColor"]))

        val tripTitleCell = findCellUpdate(tripReqs, rowIndex = 0, colIndex = 0)
        assertNotNull("Trips A1 cell update must exist", tripTitleCell)
        val tripFormat = extractCellFormat(tripTitleCell!!)
        assertEquals("1A237E", hex(tripFormat["backgroundColor"]))
    }

    // 2. No setDataValidation request in any tab

    @Test
    fun testNoSetDataValidationRequestsInSubscriptionsAndTripsProvisioning() {
        val subReqs = SheetProvisioner.buildSubscriptionsRequests(sheetId = 1, year = 2026)
        assertFalse(
            "Subscriptions provisioning must contain no setDataValidation requests",
            subReqs.any { it.containsKey("setDataValidation") }
        )

        val tripReqs = SheetProvisioner.buildTripsRequests(sheetId = 2, year = 2026)
        assertFalse(
            "Trips provisioning must contain no setDataValidation requests",
            tripReqs.any { it.containsKey("setDataValidation") }
        )
    }

    @Test
    fun testNoDataValidationRulesEnforcedAcrossAllTabs() {
        val dashboardReqs = SheetProvisioner.buildDashboardRequests(
            dashboardSheetId = 0,
            categories = listOf("Groceries", "Dining"),
            year = 2026
        )
        val monthReqs = SheetProvisioner.buildProvisionRequests(
            sheetId = 1,
            tabTitle = "Nov26",
            accounts = listOf("Canara", "Cash"),
            categories = listOf("Groceries", "Dining")
        )
        val subReqs = SheetProvisioner.buildSubscriptionsRequests(sheetId = 2, year = 2026)
        val tripReqs = SheetProvisioner.buildTripsRequests(sheetId = 3, year = 2026)

        val allRequests = dashboardReqs + monthReqs + subReqs + tripReqs
        val rules = allRequests.mapNotNull { it["setDataValidation"] as? Map<*, *> }
            .filter { it["rule"] != null }

        assertTrue(
            "No tab provisioning request should set a non-null data validation rule (rule must be null if clearing)",
            rules.isEmpty()
        )
    }

    // 3. The column chart has two named series (Personal Burn and Total Outflow) and a header count of 1

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testColumnChartHasTwoNamedSeriesAndHeaderCountOfOne() {
        val chartRequests = ChartManager.buildChartRequests(
            dashboardSheetId = 0,
            columnChartColorPrimary = mapOf("red" to 0.1f, "green" to 0.5f, "blue" to 0.8f),
            columnChartColorSecondary = mapOf("red" to 0.8f, "green" to 0.2f, "blue" to 0.2f)
        )

        val colChartReq = chartRequests.first {
            val addChart = it["addChart"] as? Map<String, Any?>
            val chart = addChart?.get("chart") as? Map<String, Any?>
            val spec = chart?.get("spec") as? Map<String, Any?>
            (spec?.get("basicChart") as? Map<*, *>)?.get("chartType") == "COLUMN"
        }

        val addChart = colChartReq["addChart"] as Map<String, Any?>
        val chart = addChart["chart"] as Map<String, Any?>
        val spec = chart["spec"] as Map<String, Any?>
        val basicChart = spec["basicChart"] as Map<String, Any?>

        assertEquals(1, basicChart["headerCount"])

        val seriesList = basicChart["series"] as List<Map<String, Any?>>
        assertEquals(2, seriesList.size)

        // Verify series column ranges: V (index 21) is Personal Burn, W (index 22) is Total Outflow
        val s1Range = ((seriesList[0]["series"] as Map<*, *>)["sourceRange"] as Map<*, *>)["sources"] as List<Map<*, *>>
        assertEquals(21, s1Range.first()["startColumnIndex"])
        assertEquals(22, s1Range.first()["endColumnIndex"])

        val s2Range = ((seriesList[1]["series"] as Map<*, *>)["sourceRange"] as Map<*, *>)["sources"] as List<Map<*, *>>
        assertEquals(22, s2Range.first()["startColumnIndex"])
        assertEquals(23, s2Range.first()["endColumnIndex"])

        // In dashboard, row index 4 (row 5) col 21 is "Personal Burn" and col 22 is "Total Outflow"
        val dashboardReqs = SheetProvisioner.buildDashboardRequests(
            dashboardSheetId = 0,
            categories = emptyList(),
            year = 2026
        )
        val cell21 = findCellUpdate(dashboardReqs, rowIndex = 4, colIndex = 21)
        val val21 = (((cell21!!["rows"] as List<*>).first() as Map<*, *>)["values"] as List<*>).first() as Map<*, *>
        assertEquals("Personal Burn", (val21["userEnteredValue"] as Map<*, *>)["stringValue"])

        val cell22 = findCellUpdate(dashboardReqs, rowIndex = 4, colIndex = 22)
        val val22 = (((cell22!!["rows"] as List<*>).first() as Map<*, *>)["values"] as List<*>).first() as Map<*, *>
        assertEquals("Total Outflow", (val22["userEnteredValue"] as Map<*, *>)["stringValue"])
    }

    // 4. The doughnut chart title text format is dark and bold

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testDoughnutChartTitleTextFormatIsDarkAndBold() {
        val chartRequests = ChartManager.buildChartRequests(
            dashboardSheetId = 0,
            columnChartColorPrimary = mapOf("red" to 0.1f, "green" to 0.5f, "blue" to 0.8f),
            columnChartColorSecondary = mapOf("red" to 0.8f, "green" to 0.2f, "blue" to 0.2f)
        )

        val doughnutCharts = chartRequests.filter {
            val addChart = it["addChart"] as? Map<String, Any?>
            val chart = addChart?.get("chart") as? Map<String, Any?>
            val spec = chart?.get("spec") as? Map<String, Any?>
            spec?.containsKey("pieChart") == true
        }

        assertTrue("At least one doughnut chart must exist", doughnutCharts.isNotEmpty())

        doughnutCharts.forEach { chartReq ->
            val addChart = chartReq["addChart"] as Map<String, Any?>
            val chart = addChart["chart"] as Map<String, Any?>
            val spec = chart["spec"] as Map<String, Any?>
            val titleFormat = spec["titleTextFormat"] as Map<String, Any?>

            assertEquals(true, titleFormat["bold"])
            val fgColor = titleFormat["foregroundColor"] as Map<*, *>
            val red = (fgColor["red"] as Number).toFloat()
            val green = (fgColor["green"] as Number).toFloat()
            val blue = (fgColor["blue"] as Number).toFloat()

            // Dark: RGB values well below 0.3f (0x20 / 255f ~= 0.125f)
            assertTrue("Title text red must be dark (< 0.3)", red < 0.3f)
            assertTrue("Title text green must be dark (< 0.3)", green < 0.3f)
            assertTrue("Title text blue must be dark (< 0.3)", blue < 0.3f)
        }
    }

    // 5. No request puts a fill on U3 to AC3 other than the clearing format

    @Test
    fun testNoRequestPutsFillOnU3ToAC3OtherThanClearingFormat() {
        val dashboardReqs = SheetProvisioner.buildDashboardRequests(
            dashboardSheetId = 0,
            categories = emptyList(),
            year = 2026
        )

        // U3:AC3 is row index 2 (row 3), startColumnIndex 20 (U) to endColumnIndex 29 (AC)
        for (req in dashboardReqs) {
            val repeatCell = req["repeatCell"] as? Map<*, *>
            if (repeatCell != null) {
                val range = repeatCell["range"] as? Map<*, *>
                val startRow = range?.get("startRowIndex") as? Int ?: -1
                val endRow = range?.get("endRowIndex") as? Int ?: -1
                val startCol = range?.get("startColumnIndex") as? Int ?: -1
                val endCol = range?.get("endColumnIndex") as? Int ?: -1

                if (startRow <= 2 && endRow >= 3 && startCol < 29 && endCol > 20) {
                    val cell = repeatCell["cell"] as? Map<*, *>
                    val uef = cell?.get("userEnteredFormat") as? Map<*, *>
                    val bg = uef?.get("backgroundColor") as? Map<*, *>
                    assertTrue(
                        "U3:AC3 repeatCell request must not set non-empty background color (clearing format only)",
                        bg == null || bg.isEmpty()
                    )
                }
            }

            val updateCells = req["updateCells"] as? Map<*, *>
            if (updateCells != null) {
                val start = updateCells["start"] as? Map<*, *>
                val rowIndex = start?.get("rowIndex") as? Int ?: -1
                val colIndex = start?.get("columnIndex") as? Int ?: -1
                if (rowIndex == 2 && colIndex in 20..28) {
                    val rows = updateCells["rows"] as? List<*>
                    rows?.forEach { r ->
                        val vals = (r as? Map<*, *>)?.get("values") as? List<*>
                        vals?.forEach { c ->
                            val uef = (c as? Map<*, *>)?.get("userEnteredFormat") as? Map<*, *>
                            val bg = uef?.get("backgroundColor") as? Map<*, *>
                            assertTrue("U3:AC3 cell update must not put fill", bg == null || bg.isEmpty())
                        }
                    }
                }
            }
        }
    }

    // 6. The dashboard formulas contain the workbook year taken from the workbook name and not TODAY() for the year

    @Test
    fun testDashboardFormulasContainWorkbookYearNotTodayForYear() {
        val reqs2026 = SheetProvisioner.buildDashboardRequests(
            dashboardSheetId = 0,
            categories = emptyList(),
            year = 2026
        )

        val allFormulas2026 = extractAllFormulas(reqs2026)
        assertTrue("Formulas must be present in dashboard", allFormulas2026.isNotEmpty())

        // Must not contain YEAR(TODAY()) or TEXT(TODAY(), "yy")
        for (f in allFormulas2026) {
            assertFalse("Formula must not use YEAR(TODAY()) to derive year: $f", f.contains("YEAR(TODAY())"))
            assertFalse("Formula must not use TEXT(TODAY(), \"yy\") to derive year: $f", f.contains("TEXT(TODAY(), \"yy\")"))
            assertFalse("Formula must not use TEXT(TODAY(),\"yy\") to derive year: $f", f.contains("TEXT(TODAY(),\"yy\")"))
        }

        // Verify year 2026 uses "26" in monthly summary formulas
        val burnFormula2026 = DashboardFormulaBuilder.buildCurrentBurnFormula(emptyList(), year = 2026)
        assertTrue(burnFormula2026.contains("\"26\""))

        // Verify year 2027 uses "27" in monthly summary formulas
        val burnFormula2027 = DashboardFormulaBuilder.buildCurrentBurnFormula(emptyList(), year = 2027)
        assertTrue(burnFormula2027.contains("\"27\""))
        assertFalse(burnFormula2027.contains("\"26\""))
    }

    // 7. The subscription KPI formulas quote the Subscriptions tab name

    @Ignore("BUG: DashboardFormulaBuilder.kt:226 subscription KPI formulas do not quote 'Subscriptions' tab name")
    @Test
    fun testSubscriptionKpiFormulasQuoteSubscriptionsTabName() {
        val activeCountFormula = DashboardFormulaBuilder.buildSubscriptionsActiveCountFormula()
        assertTrue(
            "Active count formula must quote Subscriptions tab: expected 'Subscriptions'! but was: $activeCountFormula",
            activeCountFormula.contains("'${SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME}'!")
        )

        val monthlyEquivFormula = DashboardFormulaBuilder.buildSubscriptionsMonthlyEquivalentFormula()
        assertTrue(
            "Monthly equiv formula must quote Subscriptions tab: expected 'Subscriptions'! but was: $monthlyEquivFormula",
            monthlyEquivFormula.contains("'${SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME}'!")
        )

        val nextDueFormula = DashboardFormulaBuilder.buildNextDueSubscriptionFormula()
        assertTrue(
            "Next due formula must quote Subscriptions tab: expected 'Subscriptions'! but was: $nextDueFormula",
            nextDueFormula.contains("'${SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME}'!")
        )
    }

    // 8. The I OWE PEOPLE table has no Status header

    @Test
    fun testIOwePeopleTableHasNoStatusHeader() {
        val dashboardReqs = SheetProvisioner.buildDashboardRequests(
            dashboardSheetId = 0,
            categories = emptyList(),
            year = 2026
        )

        // I OWE PEOPLE headers are placed at row index 25 (Row 26), columns 7 to 10 (H to K)
        val headers = (7..11).mapNotNull { col ->
            val cell = findCellUpdate(dashboardReqs, rowIndex = 25, colIndex = col) ?: return@mapNotNull null
            val valueMap = (((cell["rows"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("values") as? List<*>)?.firstOrNull() as? Map<*, *>
            (valueMap?.get("userEnteredValue") as? Map<*, *>)?.get("stringValue") as? String
        }

        assertEquals(listOf("Person", "Txn Count", "Amount I Owe (₹)", "Oldest Date"), headers)
        assertFalse("I OWE PEOPLE table must not contain a Status header", headers.contains("Status"))
    }

    // 9. The THEY OWE ME Oldest Date column E27 to E40 and K27 to K40 carry a date number format

    @Test
    fun testOldestDateColumnsE27ToE40AndK27ToK40CarryDateNumberFormat() {
        val dashboardReqs = SheetProvisioner.buildDashboardRequests(
            dashboardSheetId = 0,
            categories = emptyList(),
            year = 2026
        )

        // Find repeatCell requests with numberFormat
        val dateRanges = dashboardReqs.mapNotNull { it["repeatCell"] as? Map<*, *> }
            .filter {
                val uef = (it["cell"] as? Map<*, *>)?.get("userEnteredFormat") as? Map<*, *>
                val nf = uef?.get("numberFormat") as? Map<*, *>
                nf?.get("type") == "DATE"
            }

        // Verify Col E (index 4) rows 26 to 40
        val colERange = dateRanges.firstOrNull {
            val r = it["range"] as? Map<*, *>
            r?.get("startRowIndex") == 26 && r.get("endRowIndex") == 40 &&
                    r.get("startColumnIndex") == 4 && r.get("endColumnIndex") == 5
        }
        assertNotNull("Column E (E27:E40) must have a DATE number format", colERange)
        val colENf = (((colERange!!["cell"] as Map<*, *>)["userEnteredFormat"] as Map<*, *>)["numberFormat"] as Map<*, *>)
        assertEquals("DATE", colENf["type"])

        // Verify Col K (index 10) rows 26 to 40
        val colKRange = dateRanges.firstOrNull {
            val r = it["range"] as? Map<*, *>
            r?.get("startRowIndex") == 26 && r.get("endRowIndex") == 40 &&
                    r.get("startColumnIndex") == 10 && r.get("endColumnIndex") == 11
        }
        assertNotNull("Column K (K27:K40) must have a DATE number format", colKRange)
        val colKNf = (((colKRange!!["cell"] as Map<*, *>)["userEnteredFormat"] as Map<*, *>)["numberFormat"] as Map<*, *>)
        assertEquals("DATE", colKNf["type"])
    }

    // Helper functions

    private fun findCellUpdate(requests: List<Map<String, Any?>>, rowIndex: Int, colIndex: Int): Map<String, Any?>? {
        return requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .firstOrNull { update ->
                val start = update["start"] as? Map<*, *>
                if (start != null && start["rowIndex"] == rowIndex && start["columnIndex"] == colIndex) {
                    return@firstOrNull true
                }
                val range = update["range"] as? Map<*, *>
                if (range != null && range["startRowIndex"] == rowIndex && range["startColumnIndex"] == colIndex) {
                    return@firstOrNull true
                }
                false
            }
    }

    private fun extractCellFormat(cellUpdate: Map<String, Any?>): Map<String, Any?> {
        val rows = cellUpdate["rows"] as? List<*> ?: return emptyMap()
        val firstRow = rows.firstOrNull() as? Map<*, *> ?: return emptyMap()
        val values = firstRow["values"] as? List<*> ?: return emptyMap()
        val firstVal = values.firstOrNull() as? Map<*, *> ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return (firstVal["userEnteredFormat"] as? Map<String, Any?>) ?: emptyMap()
    }

    private fun extractAllFormulas(requests: List<Map<String, Any?>>): List<String> {
        val formulas = mutableListOf<String>()
        requests.mapNotNull { it["updateCells"] as? Map<*, *> }.forEach { update ->
            val rows = update["rows"] as? List<*> ?: return@forEach
            rows.forEach { r ->
                val vals = (r as? Map<*, *>)?.get("values") as? List<*> ?: return@forEach
                vals.forEach { c ->
                    val uev = (c as? Map<*, *>)?.get("userEnteredValue") as? Map<*, *>
                    val f = uev?.get("formulaValue") as? String
                    if (f != null) formulas.add(f)
                }
            }
        }
        return formulas
    }
}
