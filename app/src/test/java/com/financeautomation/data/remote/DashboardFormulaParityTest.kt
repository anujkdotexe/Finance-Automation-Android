package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected strings below are copied from docs/ios_reference/Finance2026_Template.xlsx (Dashboard A5, D5, V6).
 */
class DashboardFormulaParityTest {

    private val iosCurrentBurn =
        """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!I4:I"), INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Savings", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Transfer"), 0)"""

    private val iosPrevBurn =
        """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "'!I4:I"), INDIRECT("'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "'!F4:F"), "<>Savings", INDIRECT("'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "'!F4:F"), "<>Transfer"), 0)"""

    private val iosMonthlyRow6 =
        """=IFERROR(SUMIFS(INDIRECT("'" & ${'$'}U6 & RIGHT(YEAR(TODAY()), 2) & "'!I4:I"), INDIRECT("'" & ${'$'}U6 & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Savings", INDIRECT("'" & ${'$'}U6 & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Transfer"), 0)"""

    @Test
    fun testCurrentBurnExcludesSavingsAndTransferLikeIos() {
        assertEquals(iosCurrentBurn, DashboardFormulaBuilder.buildCurrentBurnFormula(listOf("Savings")))
        assertEquals(iosCurrentBurn, DashboardFormulaBuilder.buildCurrentBurnFormula(emptyList()))
    }

    @Test
    fun testPrevMonthBurnExcludesSavingsAndTransferLikeIos() {
        assertEquals(iosPrevBurn, DashboardFormulaBuilder.buildPrevMonthBurnFormula(listOf("Savings")))
    }

    @Test
    fun testMonthlySummaryBurnExcludesSavingsAndTransferLikeIos() {
        assertEquals(iosMonthlyRow6, DashboardFormulaBuilder.buildMonthlySummaryBurnFormula(6, listOf("Savings")))
    }

    @Test
    fun testUserExcludedCategoriesAreAddedAfterTheDefaults() {
        val formula = DashboardFormulaBuilder.buildCurrentBurnFormula(listOf("savings", "Dining Out", "transfer"))
        assertTrue(formula.endsWith(""""<>Transfer", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Dining Out"), 0)"""))
        assertEquals(1, Regex("<>Savings").findAll(formula, 0).count())
        assertEquals(1, Regex("<>Transfer").findAll(formula, 0).count())
    }

    @Test
    fun testProvisionerFormulasMatchIosForTheBurnCells() {
        val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4)
        val formulas = requests.flatMap { collectFormulas(it) }
        assertTrue(formulas.contains(iosCurrentBurn))
        assertTrue(formulas.contains(iosPrevBurn))
        assertTrue(formulas.contains(iosMonthlyRow6))
    }

    private fun collectFormulas(node: Any?): List<String> = when (node) {
        is Map<*, *> -> node.entries.flatMap { (k, v) ->
            if (k == "formulaValue" && v is String) listOf(v) else collectFormulas(v)
        }
        is List<*> -> node.flatMap { collectFormulas(it) }
        else -> emptyList()
    }
}
