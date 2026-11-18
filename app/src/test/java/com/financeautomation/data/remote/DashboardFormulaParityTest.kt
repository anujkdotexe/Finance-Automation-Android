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
            " - SUMIFS(${range("T4:T")}, ${range("Q4:Q")}, \"$it\")"
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

    private fun collectStrings(node: Any?): List<String> = when (node) {
        is Map<*, *> -> node.entries.flatMap { (k, v) ->
            if (k == "stringValue" && v is String) listOf(v) else collectStrings(v)
        }
        is List<*> -> node.flatMap { collectStrings(it) }
        else -> emptyList()
    }

    @Test
    fun testCategorySpendFormulaUsesTheLiveSpacing() {
        val expected = """=IFERROR(SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), Y6, INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!I4:I")), 0)"""
        assertEquals(expected, DashboardFormulaBuilder.buildCategorySpendFormula(6))
    }

    @Test
    fun testDashboardFormulaRequestsExcludesArchivedExcludedCategoriesWhileArchivedIncludedStillCounts() {
        val categories = listOf(
            com.financeautomation.domain.model.Category(id = "c1", name = "Rent", isDefault = false, isExcludedFromLivingBurn = true, isArchived = true, displayOrder = 0),
            com.financeautomation.domain.model.Category(id = "c2", name = "Groceries", isDefault = false, isExcludedFromLivingBurn = false, isArchived = true, displayOrder = 1),
            com.financeautomation.domain.model.Category(id = "c3", name = "Investments", isDefault = false, isExcludedFromLivingBurn = true, isArchived = false, displayOrder = 2),
            com.financeautomation.domain.model.Category(id = "c4", name = "Dining", isDefault = false, isExcludedFromLivingBurn = false, isArchived = false, displayOrder = 3)
        )

        val requests = DashboardFormulaBuilder.buildDashboardFormulaRequests(dashboardSheetId = 4, categories = categories)

        val currentBurnFormula = requests.flatMap { collectFormulas(it) }
            .first { it.contains("TEXT(TODAY(), \"mmm\")") && it.contains("!I4:I") }

        assertTrue("Current burn formula must exclude Rent", currentBurnFormula.contains("\"<>Rent\""))
        assertTrue("Current burn formula must exclude Investments", currentBurnFormula.contains("\"<>Investments\""))
        org.junit.Assert.assertFalse("Current burn formula must not exclude Groceries", currentBurnFormula.contains("<>Groceries"))
        org.junit.Assert.assertFalse("Current burn formula must not exclude Dining", currentBurnFormula.contains("<>Dining"))

        val cellStrings = requests.flatMap { collectStrings(it) }
        assertTrue("Active non-excluded category Dining must appear in category table", cellStrings.contains("Dining"))
        assertTrue("Active excluded category Investments must appear in category table", cellStrings.contains("Investments"))
        org.junit.Assert.assertFalse("Archived excluded category Rent must be hidden from category table", cellStrings.contains("Rent"))
        org.junit.Assert.assertFalse("Archived category Groceries must be hidden from category table", cellStrings.contains("Groceries"))
    }
}
