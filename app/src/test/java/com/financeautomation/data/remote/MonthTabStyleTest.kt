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
        for (i in 0..25) {
            val fmt = cells[i]
            assertEquals("col $i wrap", "WRAP", fmt["wrapStrategy"])
            val borders = fmt["borders"] as Map<*, *>
            assertEquals(setOf("top", "bottom", "left", "right"), borders.keys)
        }
        for (i in 26..28) {
            assertNull("col $i has no borders", cells[i]["borders"])
        }
    }

    @Test
    fun testRow3SpacerCellsUseTheSpacerFill() {
        val cells = row3Cells()
        assertEquals("ECEFF1", hex(cells[12]["backgroundColor"]))
        assertEquals("ECEFF1", hex(cells[21]["backgroundColor"]))
        assertEquals("37474F", hex(cells[0]["backgroundColor"]))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testRow3SpacerHeaderCellsAreBlank() {
        val update = requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .first { ((it["start"] as Map<*, *>)["rowIndex"]) == 2 }
        val values = ((update["rows"] as List<Map<String, Any?>>).first()["values"] as List<Map<String, Any?>>)
            .map { (it["userEnteredValue"] as Map<*, *>)["stringValue"] }
        assertEquals("", values[12])
        assertEquals("", values[21])
        assertEquals("Date", values[0])
    }

    @Test
    fun testRow2TitlesHaveNoSizeAndTheOwePeopleFillIsF57F17() {
        @Suppress("UNCHECKED_CAST")
        val byTitle = row2Requests().associate {
            val cell = it["cell"] as Map<String, Any?>
            (cell["userEnteredValue"] as Map<*, *>)["stringValue"] as String to cell["userEnteredFormat"] as Map<String, Any?>
        }
        for (title in listOf("EXPENSES (MONEY OUT)", "INFLOWS (MONEY IN)", "I OWE PEOPLE")) {
            val text = byTitle.getValue(title)["textFormat"] as Map<*, *>
            assertEquals(title, false, text.containsKey("fontSize"))
        }
        assertEquals("F57F17", hex(byTitle.getValue("I OWE PEOPLE")["backgroundColor"]))
    }

    @Test
    fun testRow2BordersMatchTheLiveWorkbook() {
        @Suppress("UNCHECKED_CAST")
        val bySide = requests.mapNotNull { it["updateBorders"] as? Map<String, Any?> }
            .filter { (it["range"] as Map<*, *>)["startRowIndex"] == 1 }
            .associate { req ->
                ((req["range"] as Map<*, *>)["startColumnIndex"] as Int) to
                    setOf("top", "bottom", "left", "right").filter { req.containsKey(it) }.toSet()
            }
        assertEquals(
            mapOf(
                0 to setOf("top", "bottom", "left"),
                12 to setOf("top", "bottom", "left"),
                13 to setOf("bottom"),
                21 to setOf("top", "bottom"),
                22 to setOf("top", "bottom")
            ),
            bySide
        )
    }

    @Test
    fun testRow3GridIsReaffirmedOverAToY() {
        @Suppress("UNCHECKED_CAST")
        val grid = requests.mapNotNull { it["updateBorders"] as? Map<String, Any?> }
            .first { (it["range"] as Map<*, *>).let { r -> r["startRowIndex"] == 2 } }
        assertEquals(26, (grid["range"] as Map<*, *>)["endColumnIndex"])
        assertEquals(true, grid.containsKey("innerVertical"))
    }

    @Test
    fun testRow3IsVerticallyCenteredOnEveryHeaderCell() {
        row3Cells().forEachIndexed { i, fmt -> assertEquals("col $i", "MIDDLE", fmt["verticalAlignment"]) }
    }

    @Test
    fun testSpacerCellsOnRow2AreSize11AndTitlesHaveNoSize() {
        for (request in row2Requests()) {
            @Suppress("UNCHECKED_CAST")
            val cell = request["cell"] as Map<String, Any?>
            val title = (cell["userEnteredValue"] as Map<*, *>)["stringValue"] as String
            val text = (cell["userEnteredFormat"] as Map<*, *>)["textFormat"] as Map<*, *>
            if (title.isEmpty()) assertEquals(11, text["fontSize"]) else assertEquals(false, text.containsKey("fontSize"))
        }
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
        assertEquals(setOf(2, 3, 4, 5, 7, 10, 11), textColumns)
    }

    @Test
    fun testNoConditionalFormattingIsAdded() {
        assertFalse(requests.any { it.containsKey("addConditionalFormatRule") })
        assertTrue(requests.isNotEmpty())
    }

    @Test
    fun testCurrencyColumnsUseTheLiveRupeePatternWithoutQuotes() {
        @Suppress("UNCHECKED_CAST")
        val currencyColumns = requests.mapNotNull { it["repeatCell"] as? Map<String, Any?> }
            .filter {
                val fmt = ((it["cell"] as Map<*, *>)["userEnteredFormat"] as Map<*, *>)["numberFormat"] as? Map<*, *>
                fmt?.get("type") == "CURRENCY" && fmt["pattern"] == "₹#,##0"
            }
            .map { (it["range"] as Map<*, *>)["startColumnIndex"] as Int }
            .toSet()
        assertEquals(setOf(6, 8, 9, 19, 26), currencyColumns)
    }

    @Test
    fun testBannerTitleWritesTheMonthInFull() {
        assertEquals("EXPENSE LEDGER — NOVEMBER 2026", SheetProvisioner.monthTabBannerTitle("Nov26"))
        assertEquals("EXPENSE LEDGER — DECEMBER 2026", SheetProvisioner.monthTabBannerTitle("Dec26"))
        assertEquals("EXPENSE LEDGER — JANUARY 2027", SheetProvisioner.monthTabBannerTitle("Jan27"))
        assertEquals("EXPENSE LEDGER — SEPTEMBER 2026", SheetProvisioner.monthTabBannerTitle("Sep26"))
        assertEquals("EXPENSE LEDGER — SOMETHING", SheetProvisioner.monthTabBannerTitle("Something"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testBannerIsSize14WithTheLiveFill() {
        val banner = requests.mapNotNull { it["repeatCell"] as? Map<String, Any?> }
            .first { (it["range"] as Map<*, *>).let { r -> r["startRowIndex"] == 0 && r["endColumnIndex"] == 29 } }
        val fmt = (banner["cell"] as Map<String, Any?>)["userEnteredFormat"] as Map<String, Any?>
        assertEquals(14, (fmt["textFormat"] as Map<*, *>)["fontSize"])
        assertEquals("1A237E", hex(fmt["backgroundColor"]))
        val value = ((banner["cell"] as Map<*, *>)["userEnteredValue"] as Map<*, *>)["stringValue"]
        assertEquals("EXPENSE LEDGER — NOVEMBER 2026", value)
    }
}
