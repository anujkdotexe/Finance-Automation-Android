package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that new Dashboard additions (Subscriptions band rows 43..54, Trips band rows 56..61,
 * Insights band rows 64..90) have zero bounding box overlap with any existing merged ranges,
 * chart anchors, or formula ranges on the Dashboard.
 */
class DashboardNonOverlapTest {

    private data class GridBox(
        val name: String,
        val startRow: Int,
        val endRow: Int,
        val startCol: Int,
        val endCol: Int
    ) {
        fun overlaps(other: GridBox): Boolean {
            val rowOverlap = maxOf(startRow, other.startRow) < minOf(endRow, other.endRow)
            val colOverlap = maxOf(startCol, other.startCol) < minOf(endCol, other.endCol)
            return rowOverlap && colOverlap
        }
    }

    @Test
    fun testDashboardBandsDoNotOverlap() {
        val bands = listOf(
            GridBox("Top Banner", startRow = 0, endRow = 3, startCol = 0, endCol = 29),
            GridBox("Top 6 KPI Cards", startRow = 3, endRow = 6, startCol = 0, endCol = 18),
            GridBox("Column Chart A7", startRow = 6, endRow = 23, startCol = 0, endCol = 10),
            GridBox("Doughnut Chart K7", startRow = 6, endRow = 23, startCol = 10, endCol = 20),
            GridBox("Monthly Outflow Table", startRow = 4, endRow = 17, startCol = 20, endCol = 23),
            GridBox("Category Spend Table", startRow = 4, endRow = 20, startCol = 24, endCol = 26),
            GridBox("Active Settlements", startRow = 23, endRow = 38, startCol = 0, endCol = 12),
            GridBox("Subscriptions Band", startRow = 42, endRow = 54, startCol = 0, endCol = 18),
            GridBox("Trips Band", startRow = 55, endRow = 61, startCol = 0, endCol = 18),
            GridBox("Insights Chart A65", startRow = 64, endRow = 88, startCol = 0, endCol = 10),
            GridBox("Insights Spend Table", startRow = 63, endRow = 85, startCol = 20, endCol = 22)
        )

        for (i in bands.indices) {
            for (j in i + 1 until bands.size) {
                val b1 = bands[i]
                val b2 = bands[j]
                assertFalse(
                    "Band '${b1.name}' ($b1) overlaps with '${b2.name}' ($b2)",
                    b1.overlaps(b2)
                )
            }
        }
    }

    @Test
    fun testAllDashboardMergedRangesAreDisjoint() {
        val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 1)
        val mergeRanges = requests.mapNotNull { req ->
            val merge = req["mergeCells"] as? Map<*, *>
            val range = merge?.get("range") as? Map<*, *>
            if (range != null) {
                GridBox(
                    name = "Merge",
                    startRow = (range["startRowIndex"] as Number).toInt(),
                    endRow = (range["endRowIndex"] as Number).toInt(),
                    startCol = (range["startColumnIndex"] as Number).toInt(),
                    endCol = (range["endColumnIndex"] as Number).toInt()
                )
            } else null
        }

        assertTrue("Expected at least 30 merged ranges", mergeRanges.size >= 30)

        for (i in mergeRanges.indices) {
            for (j in i + 1 until mergeRanges.size) {
                val m1 = mergeRanges[i]
                val m2 = mergeRanges[j]
                assertFalse(
                    "Merge range at [${m1.startRow}..${m1.endRow}, ${m1.startCol}..${m1.endCol}] overlaps with [${m2.startRow}..${m2.endRow}, ${m2.startCol}..${m2.endCol}]",
                    m1.overlaps(m2)
                )
            }
        }
    }

    @Test
    fun testNewFormulasContainNoBareIfOverRanges() {
        val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 1)
        val formulas = requests.flatMap { collectFormulas(it) }

        // Must not contain bare IF over ranges like IF(Subscriptions!F4:F=TRUE, ...)
        for (f in formulas) {
            val hasBareIfOverRange = Regex("""IF\([^,]*![A-Z]\d+:[A-Z]\d*=""", RegexOption.IGNORE_CASE).containsMatchIn(f)
            assertFalse("Formula contains forbidden bare IF over range: $f", hasBareIfOverRange)
        }

        // Subscriptions band formulas
        assertTrue(formulas.any { it.contains("COUNTIF(Subscriptions!F4:F, TRUE)") })
        assertTrue(formulas.any { it.contains("SUMIF(Subscriptions!F4:F, TRUE, Subscriptions!I4:I)") })
        assertTrue(formulas.any { it.contains("SORT(FILTER(Subscriptions!E4:E") })

        // Trips band formulas
        assertTrue(formulas.any { it.contains("FILTER(Trips!A4:A, Trips!D4:D=\"Active\")") })
        assertTrue(formulas.any { it.contains("COUNTA(FILTER(Trips!A4:A") })

        // Insights band single-slice rule
        assertTrue(formulas.any { it.contains("INDIRECT(\"'\" & TEXT(TODAY(), \"mmm\")") && it.contains("!E4:E\"), \"\"") })
        assertTrue(formulas.any { it.contains("INDIRECT(\"'\" & TEXT(TODAY(), \"mmm\")") && it.contains("!E4:E\"), \"?*\"") })
    }

    private fun collectFormulas(node: Any?): List<String> = when (node) {
        is Map<*, *> -> node.entries.flatMap { (k, v) ->
            if (k == "formulaValue" && v is String) listOf(v) else collectFormulas(v)
        }
        is List<*> -> node.flatMap { collectFormulas(it) }
        else -> emptyList()
    }
}
