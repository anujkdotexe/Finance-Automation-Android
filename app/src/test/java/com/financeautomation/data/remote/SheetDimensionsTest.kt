package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Asserts the row heights and column widths sent to the Sheets API match the iOS reference.
 * The API takes pixels; the reference gives points (1 pixel = 0.75 point) and Google exports
 * a column of N pixels as N / 8 + 0.125 characters.
 */
class SheetDimensionsTest {

    private data class Dim(val dimension: String, val start: Int, val end: Int, val pixels: Int)

    private fun dims(requests: List<Map<String, Any?>>, sheetId: Int): List<Dim> =
        requests.mapNotNull { it["updateDimensionProperties"] as? Map<*, *> }
            .filter { (it["range"] as Map<*, *>)["sheetId"] == sheetId }
            .mapNotNull {
                val range = it["range"] as Map<*, *>
                val props = it["properties"] as Map<*, *>
                val px = props["pixelSize"] as? Int ?: return@mapNotNull null
                Dim(range["dimension"] as String, range["startIndex"] as Int, range["endIndex"] as Int, px)
            }

    private fun List<Dim>.pixelsFor(dimension: String, index: Int): Int? =
        lastOrNull { it.dimension == dimension && index >= it.start && index < it.end }?.pixels

    @Test
    fun testPointsAreConvertedToPixels() {
        assertEquals(40, SheetProvisioner.pointsToPixels(30.0))
        assertEquals(24, SheetProvisioner.pointsToPixels(18.0))
        assertEquals(28, SheetProvisioner.pointsToPixels(21.0))
        assertEquals(17, SheetProvisioner.pointsToPixels(12.5))
        assertEquals(14, SheetProvisioner.pointsToPixels(10.5))
        assertEquals(17, SheetProvisioner.pointsToPixels(12.75))
    }

    @Test
    fun testMonthTabRowHeightsMatchIosReference() {
        val d = dims(SheetProvisioner.buildProvisionRequests(sheetId = 9, tabTitle = "Nov26"), 9)
        // Live Nov26: row 1 is 30 points, rows 2 and 3 are 18 and 21, rows 4 and below have no custom height
        assertEquals(40, d.pixelsFor("ROWS", 0))
        assertEquals(24, d.pixelsFor("ROWS", 1))
        assertEquals(28, d.pixelsFor("ROWS", 2))
        assertEquals(null, d.pixelsFor("ROWS", 3))
        assertEquals(null, d.pixelsFor("ROWS", 999))
    }

    @Test
    fun testMonthTabColumnWidthsMatchIosReference() {
        val d = dims(SheetProvisioner.buildProvisionRequests(sheetId = 9, tabTitle = "Nov26"), 9)
        assertEquals(90, d.pixelsFor("COLUMNS", 0))
        assertEquals(129, d.pixelsFor("COLUMNS", 1)) // B 16.25
        assertEquals(160, d.pixelsFor("COLUMNS", 3)) // D 20.13
        assertEquals(90, d.pixelsFor("COLUMNS", 8))
        assertEquals(90, d.pixelsFor("COLUMNS", 9))
        assertEquals(80, d.pixelsFor("COLUMNS", 10))
        assertEquals(150, d.pixelsFor("COLUMNS", 11)) // L Note
        assertEquals(10, d.pixelsFor("COLUMNS", 12)) // M Spacer 1
        assertEquals(75, d.pixelsFor("COLUMNS", 14)) // O Time
        assertEquals(100, d.pixelsFor("COLUMNS", 16)) // Q Type
        assertEquals(115, d.pixelsFor("COLUMNS", 19)) // T Amount
        assertEquals(115, d.pixelsFor("COLUMNS", 20)) // U Replaced By
        assertEquals(10, d.pixelsFor("COLUMNS", 21)) // V Spacer 2
        assertEquals(95, d.pixelsFor("COLUMNS", 22)) // W Date
        assertEquals(115, d.pixelsFor("COLUMNS", 28)) // AC Status
    }

    @Test
    fun testDashboardRowHeightsMatchIosReference() {
        val d = dims(SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4), 4)
        // Live iOS rows 1 to 7: 24, 13.5, default, 14.25, 21, 16.5, 12.75 points
        assertEquals(32, d.pixelsFor("ROWS", 0))
        assertEquals(18, d.pixelsFor("ROWS", 1))
        assertEquals(null, d.pixelsFor("ROWS", 2))
        assertEquals(19, d.pixelsFor("ROWS", 3))
        assertEquals(28, d.pixelsFor("ROWS", 4))
        assertEquals(22, d.pixelsFor("ROWS", 5))
        assertEquals(17, d.pixelsFor("ROWS", 6))
        assertEquals(18, d.pixelsFor("ROWS", 7))
        assertEquals(18, d.pixelsFor("ROWS", 22))
        assertEquals(24, d.pixelsFor("ROWS", 23))
        assertEquals(22, d.pixelsFor("ROWS", 24))
        assertEquals(28, d.pixelsFor("ROWS", 25))
        assertEquals(20, d.pixelsFor("ROWS", 26))
        assertEquals(20, d.pixelsFor("ROWS", 35))
        assertEquals(null, d.pixelsFor("ROWS", 36))
    }

    @Test
    fun testDashboardColumnWidthsMatchIosReference() {
        val d = dims(SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4), 4)
        // Live column A is 48.0 characters wide (383 px with the calibration N / 8 + 0.125); B to D are 8.6
        assertEquals(383, d.pixelsFor("COLUMNS", 0))
        assertEquals(68, d.pixelsFor("COLUMNS", 1))
        assertEquals(68, d.pixelsFor("COLUMNS", 3))
        assertEquals(93, d.pixelsFor("COLUMNS", 4))
        assertEquals(10, d.pixelsFor("COLUMNS", 6))
        assertEquals(68, d.pixelsFor("COLUMNS", 12)) // M shares the L width in iOS
        assertEquals(144, d.pixelsFor("COLUMNS", 22))
        assertFalse(d.any { it.dimension == "COLUMNS" && it.start != 0 && it.pixels > 200 })
    }
}
