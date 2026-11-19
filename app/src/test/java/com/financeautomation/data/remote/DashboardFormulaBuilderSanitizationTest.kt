package com.financeautomation.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardFormulaBuilderSanitizationTest {

    @Test
    fun burnFormulaEscapesQuotesAndWildcardsInExcludedCategoryNames() {
        val excluded = listOf(
            "He said \"hi\"",
            "Gifts*",
            "=SUM(A1)",
            "~tilde"
        )

        val formula = DashboardFormulaBuilder.buildCurrentBurnFormula(excluded)

        // Verify He said "hi" has doubled quotes: "He said ""hi"""
        assertTrue(
            "Formula must double internal quotes for formula safety: $formula",
            formula.contains("\"<>He said \"\"hi\"\"\"")
        )

        // Verify Gifts* has escaped wildcard: Gifts~*
        assertTrue(
            "Formula must escape wildcards in criteria: $formula",
            formula.contains("\"<>Gifts~*\"")
        )

        // Verify ~tilde has escaped tilde: ~~tilde
        assertTrue(
            "Formula must escape tildes in criteria: $formula",
            formula.contains("\"<>~~tilde\"")
        )

        // Verify =SUM(A1) is safely enclosed
        assertTrue(
            "Formula must safely enclose = in criteria string: $formula",
            formula.contains("\"<>=SUM(A1)\"")
        )
    }

    @Test
    fun buildCategorySpendFormulaReferencesCategoryCellExact() {
        val formula = DashboardFormulaBuilder.buildCategorySpendFormula(6)
        assertTrue(
            "Category spend formula must reference cell Y6: $formula",
            formula.contains("Y6")
        )
    }
}
