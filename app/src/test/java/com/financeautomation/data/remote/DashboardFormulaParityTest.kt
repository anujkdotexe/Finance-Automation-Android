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

    /**
     * Owner decision: living burn subtracts the refunds of the month. The iOS SUMIFS is kept exactly as the first
     * term; the refund terms and the MAX(0, ...) clamp are the only difference from the iOS template.
     */
    private fun withRefunds(ios: String, tabInner: String): String {
        val core = ios.removePrefix("=IFERROR(").removeSuffix(", 0)")
        fun range(cols: String) = """INDIRECT("'" & $tabInner & "'!$cols")"""
        val refunds = listOf("Refund / Return", "Refund").joinToString("") {
            " - SUMIFS(${range("S4:S")}, ${range("P4:P")}, \"$it\")"
        }
        return "=IFERROR(MAX(0, $core$refunds), 0)"
    }

    private val currentTab = """TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2)"""
    private val prevTab = """TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2)"""
    private val row6Tab = """${'$'}U6 & RIGHT(YEAR(TODAY()), 2)"""

    @Test
    fun testCurrentBurnExcludesSavingsAndTransferLikeIos() {
        assertEquals(withRefunds(iosCurrentBurn, currentTab), DashboardFormulaBuilder.buildCurrentBurnFormula(listOf("Savings")))
        assertEquals(withRefunds(iosCurrentBurn, currentTab), DashboardFormulaBuilder.buildCurrentBurnFormula(emptyList()))
    }

    @Test
    fun testPrevMonthBurnExcludesSavingsAndTransferLikeIos() {
        assertEquals(withRefunds(iosPrevBurn, prevTab), DashboardFormulaBuilder.buildPrevMonthBurnFormula(listOf("Savings")))
    }

    @Test
    fun testMonthlySummaryBurnExcludesSavingsAndTransferLikeIos() {
        assertEquals(withRefunds(iosMonthlyRow6, row6Tab), DashboardFormulaBuilder.buildMonthlySummaryBurnFormula(6, listOf("Savings")))
    }

    @Test
    fun testUserExcludedCategoriesAreAddedAfterTheDefaults() {
        val formula = DashboardFormulaBuilder.buildCurrentBurnFormula(listOf("savings", "Dining Out", "transfer"))
        assertTrue(formula.contains(""""<>Transfer", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Dining Out")"""))
        assertEquals(1, Regex("<>Savings").findAll(formula, 0).count())
        assertEquals(1, Regex("<>Transfer").findAll(formula, 0).count())
    }

    @Test
    fun testProvisionerFormulasMatchIosForTheBurnCells() {
        val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4)
        val formulas = requests.flatMap { collectFormulas(it) }
        assertTrue(formulas.contains(withRefunds(iosCurrentBurn, currentTab)))
        assertTrue(formulas.contains(withRefunds(iosPrevBurn, prevTab)))
        assertTrue(formulas.contains(withRefunds(iosMonthlyRow6, row6Tab)))
    }

    private fun collectFormulas(node: Any?): List<String> = when (node) {
        is Map<*, *> -> node.entries.flatMap { (k, v) ->
            if (k == "formulaValue" && v is String) listOf(v) else collectFormulas(v)
        }
        is List<*> -> node.flatMap { collectFormulas(it) }
        else -> emptyList()
    }

    @Test
    fun testCategorySpendFormulaUsesTheLiveSpacing() {
        val expected = """=IFERROR(SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), Y6, INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!I4:I")), 0)"""
        assertEquals(expected, DashboardFormulaBuilder.buildCategorySpendFormula(6))
    }
}
