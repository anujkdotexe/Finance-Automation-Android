package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the Dashboard requests carry the title band, italic subtitle and KPI card box styles of the iOS reference.
 */
class DashboardStyleTest {

    private val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4)

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
            .map { (it["startColumnIndex"] as Int) to (it["endColumnIndex"] as Int) }
            .toSet()
        assertEquals(setOf(0 to 3, 3 to 6, 6 to 9, 9 to 12, 12 to 15, 15 to 18), boxes)
        assertTrue(requests.any { it.containsKey("updateBorders") })
    }
}
