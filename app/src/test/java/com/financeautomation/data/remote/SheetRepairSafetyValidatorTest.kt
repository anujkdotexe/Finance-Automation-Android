package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetRepairSafetyValidatorTest {

    @Test
    fun testFailsOnDeleteDimension() {
        val requests = listOf(
            mapOf("deleteDimension" to mapOf("range" to mapOf("dimension" to "ROWS", "startIndex" to 10, "endIndex" to 11)))
        )
        val violations = SheetRepairSafetyValidator.validateRepairRequests(requests)
        assertEquals(1, violations.size)
        assertEquals("deleteDimension", violations[0].requestType)
    }

    @Test
    fun testFailsOnDeleteSheet() {
        val requests = listOf(
            mapOf("deleteSheet" to mapOf("sheetId" to 123))
        )
        val violations = SheetRepairSafetyValidator.validateRepairRequests(requests)
        assertEquals(1, violations.size)
        assertEquals("deleteSheet", violations[0].requestType)
    }

    @Test
    fun testFailsOnDeleteRange() {
        val requests = listOf(
            mapOf("deleteRange" to mapOf("range" to mapOf("sheetId" to 123), "shiftDimension" to "ROWS"))
        )
        val violations = SheetRepairSafetyValidator.validateRepairRequests(requests)
        assertEquals(1, violations.size)
        assertEquals("deleteRange", violations[0].requestType)
    }

    @Test
    fun testFailsOnClear() {
        val requests = listOf(
            mapOf("clear" to mapOf("range" to "Nov26!A4:AE"))
        )
        val violations = SheetRepairSafetyValidator.validateRepairRequests(requests)
        assertEquals(1, violations.size)
        assertEquals("clear", violations[0].requestType)
    }

    @Test
    fun testFailsOnWritingUserEnteredValueIntoRowIndex3OrAboveOnDataTab() {
        val requests = listOf(
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf("sheetId" to 123, "rowIndex" to 3, "columnIndex" to 0),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf("userEnteredValue" to mapOf("stringValue" to "DangerousOverwrite"))
                            )
                        )
                    ),
                    "fields" to "userEnteredValue"
                )
            )
        )
        val violations = SheetRepairSafetyValidator.validateRepairRequests(requests, isDataTab = true)
        assertEquals(1, violations.size)
        assertEquals("updateCells", violations[0].requestType)
        assertTrue(violations[0].reason.contains("Cannot write userEnteredValue into row index 3"))
    }

    @Test
    fun testAllowsFormattingAndValidationOnRowIndex3OrAbove() {
        val requests = listOf(
            // Formatting only
            mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf("sheetId" to 123, "startRowIndex" to 3, "endRowIndex" to 1000, "startColumnIndex" to 6, "endColumnIndex" to 7),
                    "cell" to mapOf("userEnteredFormat" to mapOf("numberFormat" to mapOf("type" to "CURRENCY"))),
                    "fields" to "userEnteredFormat(numberFormat)"
                )
            ),
            // Data validation only
            mapOf(
                "setDataValidation" to mapOf(
                    "range" to mapOf("sheetId" to 123, "startRowIndex" to 3, "endRowIndex" to 1000, "startColumnIndex" to 10, "endColumnIndex" to 11),
                    "rule" to mapOf("condition" to mapOf("type" to "ONE_OF_LIST"))
                )
            )
        )
        val violations = SheetRepairSafetyValidator.validateRepairRequests(requests, isDataTab = true)
        assertTrue("Formatting and validation on rows 4+ must not trigger violations", violations.isEmpty())
    }

    @Test
    fun testFailsOnGridShrink() {
        val requests = listOf(
            mapOf(
                "updateSheetProperties" to mapOf(
                    "properties" to mapOf(
                        "sheetId" to 123,
                        "gridProperties" to mapOf(
                            "rowCount" to 800,
                            "columnCount" to 26
                        )
                    ),
                    "fields" to "gridProperties(rowCount,columnCount)"
                )
            )
        )
        val violations = SheetRepairSafetyValidator.validateRepairRequests(
            requests = requests,
            currentGridRows = 1000,
            currentGridCols = 31
        )
        assertEquals(2, violations.size)
        assertTrue(violations.any { it.reason.contains("shrink grid rowCount from 1000 to 800") })
        assertTrue(violations.any { it.reason.contains("shrink grid columnCount from 31 to 26") })
    }

    @Test
    fun testRepairKeeps1500RowsAnd35ColsAndAllDataCellsUnchanged() {
        // Tab has 1500 rows, 35 columns, and 20 data rows in Nov26
        val initialRows = 1500
        val initialCols = 35

        val repairRequests = SheetProvisioner.buildProvisionRequests(
            sheetId = 999,
            tabTitle = "Nov26",
            currentRowCount = initialRows,
            currentColumnCount = initialCols,
            existingRuleFormulas = setOf("=\$K4=\"Void\"", "=OR(\$K4=\"Carried Fwd\", \$K4=\"Carried\")")
        )

        // Validate with safety validator
        val violations = SheetRepairSafetyValidator.validateRepairRequests(
            requests = repairRequests,
            isDataTab = true,
            currentGridRows = initialRows,
            currentGridCols = initialCols
        )
        assertTrue("Repair requests must have zero safety violations on expanded grid", violations.isEmpty())

        // Assert grid is not shrunk
        val gridUpdate = repairRequests.first { it.containsKey("updateSheetProperties") }["updateSheetProperties"] as Map<*, *>
        val gridProps = (gridUpdate["properties"] as Map<*, *>)["gridProperties"] as Map<*, *>
        val updatedRows = gridProps["rowCount"] as Int
        val updatedCols = gridProps["columnCount"] as Int

        assertEquals(1500, updatedRows)
        assertEquals(35, updatedCols)

        // Assert no conditional format rules duplicated since they already exist
        val addedCfRules = repairRequests.filter { it.containsKey("addConditionalFormatRule") }
        assertEquals("Existing conditional format rules must not be duplicated", 0, addedCfRules.size)

        // Assert no values written to row index >= 3
        for (req in repairRequests) {
            val updateCells = req["updateCells"] as? Map<*, *>
            if (updateCells != null) {
                val start = updateCells["start"] as? Map<*, *>
                val startRow = (start?.get("rowIndex") as? Number)?.toInt() ?: 0
                val rows = updateCells["rows"] as? List<*>
                if (rows != null) {
                    for ((idx, _) in rows.withIndex()) {
                        assertTrue("No cell value may be written to row index >= 3", startRow + idx < 3)
                    }
                }
            }
        }
    }
}
