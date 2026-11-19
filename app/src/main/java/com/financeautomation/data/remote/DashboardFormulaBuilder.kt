package com.financeautomation.data.remote

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category

/**
 * Builds Google Sheets batchUpdate request payloads for dynamic formula generation
 * and cell validation dropdowns matching the iOS master ledger layout:
 * - KPI cards (A5, D5, G5, J5, M5, P5)
 * - Monthly summary table (V6:V17 Personal Burn, W6:W17 Total Outflow)
 * - Category spend breakdown table (Y5:Z15 for 10 categories including Savings and Other)
 * - Active settlement queries (A27 They Owe Me, H27 I Owe People)
 */
object DashboardFormulaBuilder {

    private const val CATEGORY_TRANSFER = "Transfer"
    private val YEAR_4_DIGIT_REGEX = Regex("""(20\d{2})""")
    private val MONTH_TAB_REGEX = Regex("""^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(\d{2})$""", RegexOption.IGNORE_CASE)

    /**
     * Categories always left out of living burn, as in the iOS reference: Savings and Transfer (in that order),
     * followed by any other categories the user excluded in the app. Duplicates are ignored case-insensitively.
     */
    fun livingBurnExclusions(excludedCategoryNames: List<String>): List<String> {
        val result = mutableListOf(CategoryConstants.CATEGORY_SAVINGS, CATEGORY_TRANSFER)
        excludedCategoryNames.map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            if (result.none { it.equals(name, ignoreCase = true) }) result.add(name)
        }
        return result
    }

    /** INDIRECT reference to [columns] (for example "I4:I") of the month tab named by [tabName]. */
    private fun tabRange(tabName: String, columns: String): String =
        """INDIRECT("'" & $tabName & "'!$columns")"""

    /**
     * The living burn formula for one month tab: My share (column I) leaving out the excluded categories (column F),
     * minus the refunds of the month (Table 2: amount in column S where the type in column P is a refund), never
     * below zero. Voided income rows have amount 0, so they drop out on their own, and rows still in review are not
     * on the sheet at all. Matches [com.financeautomation.domain.usecase.LivingBurn].
     */
    fun burnFormula(tabName: String, excludedCategoryNames: List<String>): String {
        val exclusions = livingBurnExclusions(excludedCategoryNames).joinToString("") { name ->
            val escapedCriterion = com.financeautomation.core.utils.SinkEscaper.sheetFormulaLiteral(
                com.financeautomation.core.utils.SinkEscaper.sheetCriterion(name)
            )
            ", ${tabRange(tabName, "F4:F")}, \"<>$escapedCriterion\""
        }
        val spent = "SUMIFS(${tabRange(tabName, "I4:I")}, ${tabRange(tabName, "D4:D")}, \"<>[Void]*\"$exclusions)"
        val refunds = REFUND_TYPES.joinToString("") { type ->
            val escapedType = com.financeautomation.core.utils.SinkEscaper.sheetFormulaLiteral(type)
            " - SUMIFS(${tabRange(tabName, "T4:T")}, ${tabRange(tabName, "Q4:Q")}, \"$escapedType\")"
        }
        return "=IFERROR(MAX(0, $spent$refunds), 0)"
    }

    /** Inflow types counted as refunds, written exactly as the app stores them. */
    val REFUND_TYPES: List<String> = listOf("Refund / Return", "Refund")

    /**
     * Builds the formula for current month living burn KPI (cell A5 / range A5:C5).
     */
    fun buildCurrentBurnFormula(excludedCategoryNames: List<String>): String =
        burnFormula("""TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2)""", excludedCategoryNames)

    /**
     * Builds the formula for previous month living burn KPI (cell D5 / range D5:F5).
     */
    fun buildPrevMonthBurnFormula(excludedCategoryNames: List<String>): String =
        burnFormula("""TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2)""", excludedCategoryNames)

    /**
     * Builds the formula for monthly summary row [rowNumber] (cells V6 to V17, referencing U{rowNumber}).
     */
    fun buildMonthlySummaryBurnFormula(rowNumber: Int, excludedCategoryNames: List<String>): String =
        burnFormula("""${'$'}U$rowNumber & RIGHT(YEAR(TODAY()), 2)""", excludedCategoryNames)

    /**
     * Builds the SUMIFS formula for monthly summary gross outflow row [rowNumber] (cells W6 to W17, referencing U{rowNumber}).
     * Excludes rows whose description starts with '[Carried' to prevent double counting.
     */
    fun buildMonthlySummaryOutflowFormula(rowNumber: Int): String {
        return """=IFERROR(SUMIFS(INDIRECT("'" & ${'$'}U$rowNumber & RIGHT(YEAR(TODAY()), 2) & "'!G4:G"), INDIRECT("'" & ${'$'}U$rowNumber & RIGHT(YEAR(TODAY()), 2) & "'!D4:D"), "<>[Carried*"), 0)"""
    }

    /**
     * Builds category spend SUMIF formula for category table row [rowNumber] (Z6 to Z15, referencing Y{rowNumber}).
     */
    fun buildCategorySpendFormula(rowNumber: Int): String {
        return """=IFERROR(SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), Y$rowNumber, INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!I4:I")), 0)"""
    }

    /**
     * Category spend formula for the Insights table (Column V row [rowNum], referencing category in Column U).
     * Excludes [Void] rows and trip-tagged rows (Column E blank).
     */
    fun buildInsightCategorySpendFormula(rowNum: Int): String =
        """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!I4:I"), INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), U$rowNum, INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!E4:E"), "", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!D4:D"), "<>[Void]*"), 0)"""

    /**
     * Trips slice spend formula for the Insights table.
     * Sums across tagged trip rows (Column E non-empty) excluding Void, Savings, Transfer.
     */
    fun buildInsightTripSpendFormula(): String =
        """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!I4:I"), INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!E4:E"), "?*", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!D4:D"), "<>[Void]*", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Savings", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Transfer"), 0)"""

    /**
     * Refunds slice formula for the Insights table.
     * Subtracts refund inflows from Table 2 so total of all Insights slices matches the Living Burn KPI.
     */
    fun buildInsightRefundsFormula(): String =
        """=-IFERROR(SUMIFS(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!T4:T"), INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!Q4:Q"), "Refund*"), 0)"""

    val MONTH_PREFIXES: List<String> = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /** Returns the 12 month tab names for a given [year] (e.g. 2026 -> ["Jan26", "Feb26", ...]). */
    fun monthTabsForYear(year: Int): List<String> {
        val yy = (year % 100).toString().padStart(2, '0')
        return MONTH_PREFIXES.map { "$it$yy" }
    }

    /**
     * Extracts the 4-digit fiscal year from workbook tab names or workbook title,
     * falling back to the provided default year.
     */
    fun extractWorkbookYear(
        tabNames: Collection<String>,
        workbookTitle: String? = null,
        fallbackYear: Int = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).year
    ): Int {
        if (!workbookTitle.isNullOrBlank()) {
            val match = YEAR_4_DIGIT_REGEX.find(workbookTitle)
            if (match != null) return match.value.toInt()
        }
        for (tab in tabNames) {
            val m = MONTH_TAB_REGEX.find(tab)
            if (m != null) {
                val yy = m.groupValues[2].toIntOrNull()
                if (yy != null) return 2000 + yy
            }
        }
        return fallbackYear
    }

    /**
     * Builds the Paid This Year formula for row [row1BasedIndex] summing across the 12 month tabs of [year].
     * Each month is evaluated as an independent IFERROR term returning 0 if the tab is missing or has no matches.
     */
    fun buildPaidThisYearFormula(year: Int, row1BasedIndex: Int): String {
        val tabs = monthTabsForYear(year)
        val terms = tabs.map { tab ->
            """IFERROR(SUMIFS(INDIRECT("'$tab'!G4:G"), INDIRECT("'$tab'!F4:F"), "Subscriptions", INDIRECT("'$tab'!D4:D"), "*" & A$row1BasedIndex & "*"), 0)"""
        }
        return "=" + terms.joinToString(" + ")
    }

    /**
     * Builds the Last Paid Date formula for row [row1BasedIndex] finding the latest date across the 12 month tabs of [year].
     * Each month is evaluated as an independent IFERROR term inside MAX returning 0 if the tab is missing or has no matches.
     * When all terms return 0, the outer IFERROR returns empty string or 0 formatted as date with blank on zero.
     */
    fun buildLastPaidDateFormula(year: Int, row1BasedIndex: Int): String {
        val tabs = monthTabsForYear(year)
        val innerTerms = tabs.joinToString(", ") { tab ->
            """IFERROR(MAXIFS(INDIRECT("'$tab'!A4:A"), INDIRECT("'$tab'!F4:F"), "Subscriptions", INDIRECT("'$tab'!D4:D"), "*" & A$row1BasedIndex & "*"), 0)"""
        }
        return """=IFERROR(MAX($innerTerms), "")"""
    }

    /**
     * Builds the helper formula for Subscriptions tab "Per Month (₹)" column.
     * Evaluates weekly (* 52 / 12), yearly (/ 12), or monthly directly.
     */
    fun buildSubscriptionPerMonthFormula(row1BasedIndex: Int): String {
        return """=IF(ISBLANK(B$row1BasedIndex), 0, IF(C$row1BasedIndex="Weekly", B$row1BasedIndex*52/12, IF(C$row1BasedIndex="Yearly", B$row1BasedIndex/12, B$row1BasedIndex)))"""
    }

    /**
     * Builds the formula for Active Subscriptions count on the Dashboard.
     */
    fun buildSubscriptionsActiveCountFormula(): String {
        val tab = SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME
        val activeCol = SheetSchemaConstants.COL_LETTER_SUB_ACTIVE
        return """=COUNTIF($tab!${activeCol}4:$activeCol, TRUE)"""
    }

    /**
     * Builds the formula for Monthly Equivalent Subscriptions outflow on the Dashboard.
     */
    fun buildSubscriptionsMonthlyEquivalentFormula(): String {
        val tab = SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME
        val activeCol = SheetSchemaConstants.COL_LETTER_SUB_ACTIVE
        val perMonthCol = SheetSchemaConstants.COL_LETTER_SUB_PER_MONTH
        return """=SUMIF($tab!${activeCol}4:$activeCol, TRUE, $tab!${perMonthCol}4:$perMonthCol)"""
    }

    /**
     * Builds the formula for Next Due Subscription on the Dashboard.
     * Uses the due date column and filters for active renewals where due date >= TODAY(),
     * returning "Name (dd mmm)" or "None upcoming".
     */
    fun buildNextDueSubscriptionFormula(): String {
        val tab = SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME
        val nameCol = SheetSchemaConstants.COL_LETTER_SUB_NAME
        val dateCol = SheetSchemaConstants.COL_LETTER_SUB_DUE_DATE
        val activeCol = SheetSchemaConstants.COL_LETTER_SUB_ACTIVE
        val filterExpr = "FILTER({$tab!${nameCol}4:$nameCol, $tab!${dateCol}4:$dateCol}, $tab!${activeCol}4:$activeCol=TRUE, $tab!${dateCol}4:$dateCol>=TODAY())"
        val sortExpr = "SORT($filterExpr, 2, TRUE)"
        return """=IFERROR(INDEX($sortExpr, 1, 1) & " (" & TEXT(INDEX($sortExpr, 1, 2), "dd mmm") & ")", "None upcoming")"""
    }

    /**
     * Builds the formula to query the 5 newest closed subscriptions for the Dashboard,
     * selecting Name, Closed Date, and Paid in Total.
     */
    fun buildClosedSubscriptionsQueryFormula(): String {
        val tab = SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME
        val range = SheetSchemaConstants.SUBSCRIPTIONS_RANGE
        val nameCol = SheetSchemaConstants.COL_LETTER_SUB_NAME
        val activeCol = SheetSchemaConstants.COL_LETTER_SUB_ACTIVE
        val closedDateCol = SheetSchemaConstants.COL_LETTER_SUB_CLOSED_DATE
        val paidTotalCol = SheetSchemaConstants.COL_LETTER_SUB_PAID_IN_TOTAL
        return """=IFERROR(QUERY($tab!$range, "SELECT $nameCol, $closedDateCol, $paidTotalCol WHERE $activeCol = FALSE AND $nameCol IS NOT NULL ORDER BY $closedDateCol DESC LIMIT 5", 0), "")"""
    }

    /**
     * Builds the Days formula for row [row1BasedIndex] of the Trips tab.
     */
    fun buildTripDaysFormula(row1BasedIndex: Int): String {
        return """=IF(ISBLANK(B$row1BasedIndex), "", IF(ISBLANK(C$row1BasedIndex), MAX(1, TODAY()-B$row1BasedIndex+1), MAX(1, C$row1BasedIndex-B$row1BasedIndex+1)))"""
    }

    /**
     * Builds the Total Paid formula for row [row1BasedIndex] of the Trips tab across 12 month tabs of [year].
     * Excludes [Void] rows, Savings, and Transfer by category text.
     */
    fun buildTripTotalPaidFormula(year: Int, row1BasedIndex: Int): String {
        val tabs = monthTabsForYear(year)
        val terms = tabs.map { tab ->
            """IFERROR(SUMIFS(INDIRECT("'$tab'!G4:G"), INDIRECT("'$tab'!E4:E"), A$row1BasedIndex, INDIRECT("'$tab'!D4:D"), "<>[Void]*", INDIRECT("'$tab'!F4:F"), "<>Savings", INDIRECT("'$tab'!F4:F"), "<>Transfer", INDIRECT("'$tab'!F4:F"), "<>Refund"), 0)"""
        }
        return "=" + terms.joinToString(" + ")
    }

    /**
     * Builds the My Share formula for row [row1BasedIndex] of the Trips tab across 12 month tabs of [year].
     * Excludes [Void] rows, Savings, and Transfer by category text.
     */
    fun buildTripMyShareFormula(year: Int, row1BasedIndex: Int): String {
        val tabs = monthTabsForYear(year)
        val terms = tabs.map { tab ->
            """IFERROR(SUMIFS(INDIRECT("'$tab'!I4:I"), INDIRECT("'$tab'!E4:E"), A$row1BasedIndex, INDIRECT("'$tab'!D4:D"), "<>[Void]*", INDIRECT("'$tab'!F4:F"), "<>Savings", INDIRECT("'$tab'!F4:F"), "<>Transfer", INDIRECT("'$tab'!F4:F"), "<>Refund"), 0)"""
        }
        return "=" + terms.joinToString(" + ")
    }

    /**
     * Builds the Owed To Me formula for row [row1BasedIndex] of the Trips tab across 12 month tabs of [year].
     * Excludes [Void] rows, Savings, and Transfer by category text.
     */
    fun buildTripOwedToMeFormula(year: Int, row1BasedIndex: Int): String {
        val tabs = monthTabsForYear(year)
        val terms = tabs.map { tab ->
            """IFERROR(SUMIFS(INDIRECT("'$tab'!J4:J"), INDIRECT("'$tab'!E4:E"), A$row1BasedIndex, INDIRECT("'$tab'!D4:D"), "<>[Void]*", INDIRECT("'$tab'!F4:F"), "<>Savings", INDIRECT("'$tab'!F4:F"), "<>Transfer"), 0)"""
        }
        return "=" + terms.joinToString(" + ")
    }

    /**
     * Builds batchUpdate requests to update Dashboard formulas and Category table (Y5:Z15).
     */
    fun buildDashboardFormulaRequests(
        dashboardSheetId: Int,
        categories: List<Category>
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()
        val excludedNames = categories.filter { it.isExcludedFromLivingBurn }.map { it.name }

        // 1. Current Month Burn (A5)
        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 4, // 0-based row 5
                colIndex = 0, // Col A
                formula = buildCurrentBurnFormula(excludedNames)
            )
        )

        // 2. Previous Month Burn (D5)
        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 4, // 0-based row 5
                colIndex = 3, // Col D
                formula = buildPrevMonthBurnFormula(excludedNames)
            )
        )

        // 3. Owed To Me (G5) - counts status exactly Pending
        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 4,
                colIndex = 6, // Col G
                formula = """=IFERROR(SUMIF(INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!K4:K"), "Pending", INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!J4:J")), 0)"""
            )
        )

        // 4. I Owe People (J5) - counts status Pending plus Partial
        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 4,
                colIndex = 9, // Col J
                formula = """=IFERROR(SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!AC4:AC"), "Pending", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!AA4:AA")) + SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!AC4:AC"), "Partial", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!AA4:AA")), 0)"""
            )
        )

        // 5. Subscriptions (M5)
        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 4,
                colIndex = 12, // Col M
                formula = """=IFERROR(SUMIF(INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!F4:F"), "Subscriptions", INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!I4:I")), 0)"""
            )
        )

        // 6. Daily Average (P5)
        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 4,
                colIndex = 15, // Col P
                formula = """=IFERROR(A5 / DAY(TODAY()), 0)"""
            )
        )

        // 7. Monthly Summary Trend Rows V6:V17 & W6:W17
        for (row in 6..17) {
            val rowIndex = row - 1
            // Col V: Personal Burn
            requests.add(
                createUpdateCellFormulaRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowIndex,
                    colIndex = 21, // Col V
                    formula = buildMonthlySummaryBurnFormula(row, excludedNames)
                )
            )
            // Col W: Total Outflow (excluding [Carried*)
            requests.add(
                createUpdateCellFormulaRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowIndex,
                    colIndex = 22, // Col W
                    formula = buildMonthlySummaryOutflowFormula(row)
                )
            )
        }

        // 8. Category Table Y5:Z... (Row 5 header, Rows 6 to (5 + categoryCount))
        // Preserves user displayOrder, guarantees Savings and Other are never dropped, and scales dynamically (min 10 rows)
        val activeCategories = categories.filter { !it.isArchived }.sortedBy { it.displayOrder }.toMutableList()
        if (!activeCategories.any { it.name.equals(CategoryConstants.CATEGORY_SAVINGS, ignoreCase = true) }) {
            activeCategories.add(
                Category(
                    id = "savings",
                    name = CategoryConstants.CATEGORY_SAVINGS,
                    isDefault = false,
                    isExcludedFromLivingBurn = true,
                    isArchived = false,
                    displayOrder = 998
                )
            )
        }
        if (!activeCategories.any { it.name.equals(CategoryConstants.CATEGORY_OTHER, ignoreCase = true) }) {
            activeCategories.add(
                Category(
                    id = "other",
                    name = CategoryConstants.CATEGORY_OTHER,
                    isDefault = false,
                    isExcludedFromLivingBurn = false,
                    isArchived = false,
                    displayOrder = 999
                )
            )
        }
        val categoryCount = maxOf(10, activeCategories.size)
        val categoryRows = mutableListOf<Map<String, Any?>>()

        // Row 5: Header
        categoryRows.add(
            mapOf(
                "values" to listOf(
                    mapOf("userEnteredValue" to mapOf("stringValue" to "Category")),
                    mapOf("userEnteredValue" to mapOf("stringValue" to "Spend"))
                )
            )
        )

        for (i in 0 until categoryCount) {
            val rowNum = 6 + i
            if (i < activeCategories.size) {
                val cat = activeCategories[i]
                categoryRows.add(
                    mapOf(
                        "values" to listOf(
                            mapOf("userEnteredValue" to mapOf("stringValue" to cat.name)),
                            mapOf("userEnteredValue" to mapOf("formulaValue" to buildCategorySpendFormula(rowNum)))
                        )
                    )
                )
            } else {
                categoryRows.add(
                    mapOf(
                        "values" to listOf(
                            mapOf("userEnteredValue" to mapOf("stringValue" to "")),
                            mapOf("userEnteredValue" to mapOf("stringValue" to ""))
                        )
                    )
                )
            }
        }

        requests.add(
            mapOf(
                "updateCells" to mapOf(
                    "range" to mapOf(
                        "sheetId" to dashboardSheetId,
                        "startRowIndex" to 4, // Row 5 (header)
                        "endRowIndex" to 5 + categoryCount, // Row 5 + categoryCount
                        "startColumnIndex" to 24, // Col Y
                        "endColumnIndex" to 26 // Col Z + 1
                    ),
                    "rows" to categoryRows,
                    "fields" to "userEnteredValue"
                )
            )
        )

        // 9. Active Settlement Queries (A27 They Owe Me, H27 I Owe People)
        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 26, // Row 27
                colIndex = 0,  // Col A
                formula = """=IFERROR(QUERY(QUERY(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!A4:L"), "SELECT H, G - I, G - I - J, J, A WHERE K = 'Pending' AND J > 0 AND H != 'Personal' LABEL G - I '', G - I - J ''", 0), "SELECT Col1, SUM(Col2), SUM(Col3), SUM(Col4), MIN(Col5), COUNT(Col1) GROUP BY Col1 LABEL Col1 '', SUM(Col2) '', SUM(Col3) '', SUM(Col4) '', MIN(Col5) '', COUNT(Col1) ''", 0), "")"""
            )
        )

        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 26, // Row 27
                colIndex = 7,  // Col H
                formula = """=IFERROR(QUERY(QUERY(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!W4:AC"), "SELECT Y, AA, W WHERE (AC = 'Pending' OR AC = 'Partial') AND AA > 0", 0), "SELECT Col1, COUNT(Col1), SUM(Col2), MIN(Col3) GROUP BY Col1 LABEL Col1 '', COUNT(Col1) '', SUM(Col2) '', MIN(Col3) ''", 0), "")"""
            )
        )

        return requests
    }

    /**
     * Builds data validation requests across sheet IDs (no-op to match iOS master template which has no dropdowns).
     */
    fun buildDataValidationRequests(
        sheetIds: List<Int>,
        accounts: List<Account>,
        categories: List<Category>
    ): List<Map<String, Any?>> {
        return emptyList()
    }

    private fun createUpdateCellFormulaRequest(
        sheetId: Int,
        rowIndex: Int,
        colIndex: Int,
        formula: String
    ): Map<String, Any?> {
        return mapOf(
            "updateCells" to mapOf(
                "range" to mapOf(
                    "sheetId" to sheetId,
                    "startRowIndex" to rowIndex,
                    "endRowIndex" to rowIndex + 1,
                    "startColumnIndex" to colIndex,
                    "endColumnIndex" to colIndex + 1
                ),
                "rows" to listOf(
                    mapOf(
                        "values" to listOf(
                            mapOf("userEnteredValue" to mapOf("formulaValue" to formula))
                        )
                    )
                ),
                "fields" to "userEnteredValue"
            )
        )
    }
}
