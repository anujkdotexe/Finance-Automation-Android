package com.financeautomation.data.remote

import com.financeautomation.core.constants.CategoryConstants
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

    /**
     * Builds the SUMIFS formula for current month living burn KPI (cell A5 / range A5:C5).
     */
    fun buildCurrentBurnFormula(excludedCategoryNames: List<String>): String {
        val activeExcluded = livingBurnExclusions(excludedCategoryNames)
        val exclusions = activeExcluded.joinToString("") { name ->
            """, INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>$name""""
        }
        return """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!I4:I")$exclusions), 0)"""
    }

    /**
     * Builds the SUMIFS formula for previous month living burn KPI (cell D5 / range D5:F5).
     */
    fun buildPrevMonthBurnFormula(excludedCategoryNames: List<String>): String {
        val activeExcluded = livingBurnExclusions(excludedCategoryNames)
        val exclusions = activeExcluded.joinToString("") { name ->
            """, INDIRECT("'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "'!F4:F"), "<>$name""""
        }
        return """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "'!I4:I")$exclusions), 0)"""
    }

    /**
     * Builds the SUMIFS formula for monthly summary row [rowNumber] (cells V6 to V17, referencing U{rowNumber}).
     */
    fun buildMonthlySummaryBurnFormula(rowNumber: Int, excludedCategoryNames: List<String>): String {
        val activeExcluded = livingBurnExclusions(excludedCategoryNames)
        val exclusions = activeExcluded.joinToString("") { name ->
            """, INDIRECT("'" & ${'$'}U$rowNumber & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>$name""""
        }
        return """=IFERROR(SUMIFS(INDIRECT("'" & ${'$'}U$rowNumber & RIGHT(YEAR(TODAY()), 2) & "'!I4:I")$exclusions), 0)"""
    }

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
        return """=IFERROR(SUMIF(INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!F4:F"), Y$rowNumber, INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!I4:I")), 0)"""
    }

    /**
     * Builds batchUpdate requests to update Dashboard formulas and Category table (Y5:Z15).
     */
    fun buildDashboardFormulaRequests(
        dashboardSheetId: Int,
        categories: List<Category>
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()
        val excludedNames = categories.filter { it.isExcludedFromLivingBurn && !it.isArchived }.map { it.name }

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
                formula = """=IFERROR(SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!AB4:AB"), "Pending", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!Z4:Z")) + SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!AB4:AB"), "Partial", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!Z4:Z")), 0)"""
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

        // 8. Category Table Y5:Z15 (Row 5 header, Rows 6 to 15 for 10 categories including Savings and Other)
        val activeCategories = categories.filter { !it.isArchived }
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

        // Rows 6 to 15: Exactly 10 categories
        for (i in 0 until 10) {
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
                        "startRowIndex" to 4, // Row 5
                        "endRowIndex" to 15, // Row 15 + 1
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
                formula = """=IFERROR(QUERY(QUERY(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!A4:K"), "SELECT H, G - I, G - I - J, J, A WHERE K = 'Pending' AND J > 0 AND H != 'Personal' LABEL G - I '', G - I - J ''", 0), "SELECT Col1, SUM(Col2), SUM(Col3), SUM(Col4), MIN(Col5), COUNT(Col1) GROUP BY Col1 LABEL Col1 '', SUM(Col2) '', SUM(Col3) '', SUM(Col4) '', MIN(Col5) '', COUNT(Col1) ''", 0), "")"""
            )
        )

        requests.add(
            createUpdateCellFormulaRequest(
                sheetId = dashboardSheetId,
                rowIndex = 26, // Row 27
                colIndex = 7,  // Col H
                formula = """=IFERROR(QUERY(QUERY(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!V4:AB"), "SELECT X, Z, V WHERE (AB = 'Pending' OR AB = 'Partial') AND Z > 0", 0), "SELECT Col1, COUNT(Col1), SUM(Col2), MIN(Col3) GROUP BY Col1 LABEL Col1 '', COUNT(Col1) '', SUM(Col2) '', MIN(Col3) ''", 0), "")"""
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
