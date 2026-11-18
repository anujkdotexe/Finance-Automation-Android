package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SinkEscaperTest {

    @Test
    fun sheetFormulaLiteralDoublesDoubleQuotes() {
        assertEquals("He said \"\"hi\"\"", SinkEscaper.sheetFormulaLiteral("He said \"hi\""))
        assertEquals("\"\"\"\"", SinkEscaper.sheetFormulaLiteral("\"\""))
        assertEquals("No quotes", SinkEscaper.sheetFormulaLiteral("No quotes"))
        assertEquals("", SinkEscaper.sheetFormulaLiteral(""))
    }

    @Test
    fun sheetCriterionPrefixesWildcardCharactersWithTilde() {
        assertEquals("Gifts~*", SinkEscaper.sheetCriterion("Gifts*"))
        assertEquals("~~tilde", SinkEscaper.sheetCriterion("~tilde"))
        assertEquals("What~?", SinkEscaper.sheetCriterion("What?"))
        assertEquals("~*All~?~~", SinkEscaper.sheetCriterion("*All?~"))
        assertEquals("Plain Name", SinkEscaper.sheetCriterion("Plain Name"))
    }

    @Test
    fun driveQueryLiteralEscapesBackslashAndSingleQuotes() {
        assertEquals("name\\'s", SinkEscaper.driveQueryLiteral("name's"))
        assertEquals("path\\\\file", SinkEscaper.driveQueryLiteral("path\\file"))
        assertEquals("path\\\\\\'file", SinkEscaper.driveQueryLiteral("path\\'file"))
        assertEquals("CleanName", SinkEscaper.driveQueryLiteral("CleanName"))
    }

    @Test
    fun csvCellGuardsAgainstFormulaInjection() {
        // Starts with formula triggers
        assertEquals("'=SUM(A1)", SinkEscaper.csvCell("=SUM(A1)"))
        assertEquals("'+500", SinkEscaper.csvCell("+500"))
        assertEquals("'-200", SinkEscaper.csvCell("-200"))
        assertEquals("'@command", SinkEscaper.csvCell("@command"))
        assertEquals("'\tindent", SinkEscaper.csvCell("\tindent"))
        // \r requires RFC 4180 quoting due to carriage return line break
        assertEquals("\"'\rreturn\"", SinkEscaper.csvCell("\rreturn"))

        // Leading whitespace before formula trigger
        assertEquals("' =SUM(A1)", SinkEscaper.csvCell(" =SUM(A1)"))
    }

    @Test
    fun csvCellCompliesWithRfc4180Quoting() {
        // Commas
        assertEquals("\"Food, Groceries\"", SinkEscaper.csvCell("Food, Groceries"))

        // Quotes inside
        assertEquals("\"He said \"\"hi\"\"\"", SinkEscaper.csvCell("He said \"hi\""))

        // Newlines
        assertEquals("\"Line 1\nLine 2\"", SinkEscaper.csvCell("Line 1\nLine 2"))

        // Combined: Formula trigger + comma
        assertEquals("\"'=SUM(A1), 50\"", SinkEscaper.csvCell("=SUM(A1), 50"))

        // Normal safe cell
        assertEquals("Regular Merchant", SinkEscaper.csvCell("Regular Merchant"))
    }

    @Test
    fun configCellReplacesForbiddenDelimiters() {
        assertEquals("Raj_ Jr", SinkEscaper.configCell("Raj, Jr"))
        assertEquals("order_color", SinkEscaper.configCell("order|color"))
        assertEquals("key_value", SinkEscaper.configCell("key:value"))
        assertEquals("Safe Name", SinkEscaper.configCell("Safe Name"))
    }
}
