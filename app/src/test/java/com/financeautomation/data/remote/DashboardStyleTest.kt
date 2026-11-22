package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the Dashboard requests carry the title band, italic subtitle and KPI card box styles of the iOS reference.
 */
class DashboardStyleTest {

    private val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4, year = 2026)

    @Suppress("UNCHECKED_CAST")
    private fun bandFormat(row: Int, startCol: Int): Map<String, Any?>? =
        requests.mapNotNull { it["repeatCell"] as? Map<String, Any?> }
            .firstOrNull {
                val r = it["range"] as Map<*, *>
                r["startRowIndex"] == row && r["startColumnIndex"] == startCol &&
                    ((it["cell"] as Map<*, *>)["userEnteredFormat"] as Map<*, *>).containsKey("textFormat")
            }?.let { (it["cell"] as Map<String, Any?>)["userEnteredFormat"] as Map<String, Any?> }

    @Test
    fun testTitleBandCellsRightOfTheMergeAreStyled() {
        val row1 = bandFormat(row = 0, startCol = 20)!!
        val text = row1["textFormat"] as Map<*, *>
        assertEquals("Google Sans", text["fontFamily"])
        assertEquals(18, text["fontSize"])
        assertEquals(true, text["bold"])
        assertEquals("CENTER", row1["horizontalAlignment"])
        assertEquals("MIDDLE", row1["verticalAlignment"])
    }

    @Test
    fun testBandRangesCoverUToAC() {
        @Suppress("UNCHECKED_CAST")
        val range = requests.mapNotNull { it["repeatCell"] as? Map<String, Any?> }
            .map { it["range"] as Map<*, *> }
            .first { it["startRowIndex"] == 0 && it["startColumnIndex"] == 20 && it["endColumnIndex"] == 29 }
        assertEquals(1, range["endRowIndex"])
    }

    @Test
    fun testSubtitleBandIsItalic() {
        val text = bandFormat(row = 1, startCol = 20)!!["textFormat"] as Map<*, *>
        assertEquals(true, text["italic"])
        assertEquals(10, text["fontSize"])
    }

    @Test
    fun testLastUpdatedCellIsItalic() {
        @Suppress("UNCHECKED_CAST")
        val cell = requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .first {
                val r = it["range"] as Map<*, *>
                r["startRowIndex"] == 1 && r["startColumnIndex"] == 0
            }
        @Suppress("UNCHECKED_CAST")
        val fmt = (((cell["rows"] as List<Map<String, Any?>>).first()["values"] as List<Map<String, Any?>>).first()["userEnteredFormat"]) as Map<*, *>
        assertEquals(true, (fmt["textFormat"] as Map<*, *>)["italic"])
    }

    @Test
    fun testEachKpiCardHasAThinBox() {
        @Suppress("UNCHECKED_CAST")
        val boxes = requests.mapNotNull { it["updateBorders"] as? Map<String, Any?> }
            .map { it["range"] as Map<*, *> }
            .filter { it["startRowIndex"] == 3 }
            .map { (it["startColumnIndex"] as Int) to (it["endColumnIndex"] as Int) }
            .toSet()
        assertEquals(setOf(0 to 3, 3 to 6, 6 to 9, 9 to 12, 12 to 15, 15 to 18), boxes)
        assertTrue(requests.any { it.containsKey("updateBorders") })
    }

    @Suppress("UNCHECKED_CAST")
    private fun cellFormat(row: Int, col: Int): Map<String, Any?> {
        val cell = requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .first {
                val r = it["range"] as Map<*, *>
                r["startRowIndex"] == row && r["startColumnIndex"] == col
            }
        return (((cell["rows"] as List<Map<String, Any?>>).first()["values"] as List<Map<String, Any?>>).first()["userEnteredFormat"]) as Map<String, Any?>
    }

    private fun hex(color: Any?): String {
        val m = color as Map<*, *>
        fun ch(k: String) = Math.round((m[k] as Number).toFloat() * 255f)
        return "%02X%02X%02X".format(ch("red"), ch("green"), ch("blue"))
    }

    @Test
    fun testKpiValuesUseTheLiveRupeePatternWithoutQuotes() {
        for (col in listOf(0, 3, 6, 9, 12, 15)) {
            val pattern = (cellFormat(4, col)["numberFormat"] as Map<*, *>)["pattern"]
            assertEquals("col $col", "₹#,##0", pattern)
        }
    }

    @Test
    fun testOwePeopleHeaderHasNoFontSizeOverride() {
        val text = cellFormat(3, 9)["textFormat"] as Map<*, *>
        assertEquals(false, text.containsKey("fontSize"))
        assertEquals(8, (cellFormat(3, 0)["textFormat"] as Map<*, *>)["fontSize"])
    }

    @Test
    fun testDailyAverageValueIsBlue() {
        val text = cellFormat(4, 15)["textFormat"] as Map<*, *>
        assertEquals("1465BF", hex(text["foregroundColor"]))
    }

    @Test
    fun testS3AndT3HaveTheLightSlateFill() {
        @Suppress("UNCHECKED_CAST")
        val fill = requests.mapNotNull { it["repeatCell"] as? Map<String, Any?> }
            .first {
                val r = it["range"] as Map<*, *>
                r["startRowIndex"] == 2 && r["startColumnIndex"] == 18 && r["endColumnIndex"] == 20
            }
        val fmt = (fill["cell"] as Map<*, *>)["userEnteredFormat"] as Map<*, *>
        assertEquals("EBEEF1", hex(fmt["backgroundColor"]))
    }

    @Test
    fun testIOwePeopleHeaderRowHasPersonCountAmountAndOldest() {
        val headers = (7..10).map { col ->
            @Suppress("UNCHECKED_CAST")
            val cell = requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
                .first {
                    val r = it["range"] as Map<*, *>
                    r["startRowIndex"] == 25 && r["startColumnIndex"] == col
                }
            @Suppress("UNCHECKED_CAST")
            val value = ((cell["rows"] as List<Map<String, Any?>>).first()["values"] as List<Map<String, Any?>>).first()
            (value["userEnteredValue"] as Map<*, *>)["stringValue"]
        }
        assertEquals(listOf("Person", "Txn Count", "Amount I Owe (₹)", "Oldest Date"), headers)
    }

    @Test
    fun testSettlementHeaderRowUsesTheLiveFillWrapAndBorders() {
        assertEquals("1A237E", hex(cellFormat(25, 11)["backgroundColor"]))
        assertEquals("WRAP", cellFormat(25, 11)["wrapStrategy"])
        @Suppress("UNCHECKED_CAST")
        val header = requests.mapNotNull { it["updateBorders"] as? Map<String, Any?> }
            .first { (it["range"] as Map<*, *>).let { r -> r["startRowIndex"] == 25 && r["endColumnIndex"] == 12 } }
        assertEquals(true, header.containsKey("innerVertical"))
        assertEquals(false, header.containsKey("right"))
    }

    @Test
    fun testTitleUsesAPlainHyphen() {
        @Suppress("UNCHECKED_CAST")
        val cell = requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .first { (it["range"] as Map<*, *>).let { r -> r["startRowIndex"] == 0 && r["startColumnIndex"] == 0 } }
        @Suppress("UNCHECKED_CAST")
        val value = ((cell["rows"] as List<Map<String, Any?>>).first()["values"] as List<Map<String, Any?>>).first()
        assertEquals("PERSONAL FINANCE DASHBOARD - 2026", (value["userEnteredValue"] as Map<*, *>)["stringValue"])
    }
}
