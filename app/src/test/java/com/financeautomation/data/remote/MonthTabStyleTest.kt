package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the Sheets API requests for the month tab carry the styles found in the iOS reference.
 */
class MonthTabStyleTest {

    private val requests = SheetProvisioner.buildProvisionRequests(sheetId = 9, tabTitle = "Nov26")

    @Suppress("UNCHECKED_CAST")
    private fun row3Cells(): List<Map<String, Any?>> {
        val update = requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .first { ((it["start"] as Map<*, *>)["rowIndex"]) == 2 }
        val row = (update["rows"] as List<Map<String, Any?>>).first()
        return (row["values"] as List<Map<String, Any?>>).map { it["userEnteredFormat"] as Map<String, Any?> }
    }

    @Suppress("UNCHECKED_CAST")
    private fun row2Requests(): List<Map<String, Any?>> =
        requests.mapNotNull { it["repeatCell"] as? Map<String, Any?> }
            .filter { (it["range"] as Map<*, *>)["startRowIndex"] == 1 }

    private fun hex(color: Any?): String {
        val m = color as Map<*, *>
        fun ch(k: String) = Math.round((m[k] as Number).toFloat() * 255f)
        return "%02X%02X%02X".format(ch("red"), ch("green"), ch("blue"))
    }

    @Test
    fun testRow3HeadersHaveBordersAndWrapOnAToY() {
        val cells = row3Cells()
        for (i in 0..24) {
            val fmt = cells[i]
            assertEquals("col $i wrap", "WRAP", fmt["wrapStrategy"])
            val borders = fmt["borders"] as Map<*, *>
            assertEquals(setOf("top", "bottom", "left", "right"), borders.keys)
        }
        for (i in 25..27) {
            assertNull("col $i has no borders", cells[i]["borders"])
            assertNull("col $i has no vertical alignment", cells[i]["verticalAlignment"])
        }
    }

    @Test
    fun testRow3SpacerCellsUseTheSpacerFill() {
        val cells = row3Cells()
        assertEquals("ECEFF1", hex(cells[11]["backgroundColor"]))
        assertEquals("ECEFF1", hex(cells[20]["backgroundColor"]))
        assertEquals("37474F", hex(cells[0]["backgroundColor"]))
    }

    @Test
    fun testRow2TitlesAreSize10AndTheOwePeopleFillIsF57F17() {
        @Suppress("UNCHECKED_CAST")
        val byTitle = row2Requests().associate {
            val cell = it["cell"] as Map<String, Any?>
            (cell["userEnteredValue"] as Map<*, *>)["stringValue"] as String to cell["userEnteredFormat"] as Map<String, Any?>
        }
        for (title in listOf("EXPENSES (MONEY OUT)", "INFLOWS (MONEY IN)", "I OWE PEOPLE")) {
            val text = byTitle.getValue(title)["textFormat"] as Map<*, *>
            assertEquals(title, 10, text["fontSize"])
        }
        assertEquals("F57F17", hex(byTitle.getValue("I OWE PEOPLE")["backgroundColor"]))
    }

    @Test
    fun testRow2BlocksHaveThinOuterBorders() {
        @Suppress("UNCHECKED_CAST")
        val blocks = requests.mapNotNull { it["updateBorders"] as? Map<String, Any?> }
            .map { it["range"] as Map<*, *> }
            .filter { it["startRowIndex"] == 1 }
            .map { (it["startColumnIndex"] as Int) to (it["endColumnIndex"] as Int) }
            .toSet()
        // A2:K2, L2, M2:T2, U2, V2:AB2
        assertEquals(setOf(0 to 11, 11 to 12, 12 to 20, 20 to 21, 21 to 28), blocks)
    }

    @Test
    fun testDebitTableColumnsAreFormattedAsPlainText() {
        @Suppress("UNCHECKED_CAST")
        val textColumns = requests.mapNotNull { it["repeatCell"] as? Map<String, Any?> }
            .filter {
                val fmt = ((it["cell"] as Map<*, *>)["userEnteredFormat"] as Map<*, *>)["numberFormat"] as? Map<*, *>
                fmt?.get("type") == "TEXT" && fmt["pattern"] == "@"
            }
            .map { (it["range"] as Map<*, *>)["startColumnIndex"] as Int }
            .toSet()
        assertEquals(setOf(2, 3, 4, 5, 7, 10), textColumns)
    }

    @Test
    fun testNoConditionalFormattingIsAdded() {
        assertFalse(requests.any { it.containsKey("addConditionalFormatRule") })
        assertTrue(requests.isNotEmpty())
    }
}
