package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetGridValidatorTest {

    @Test
    fun testValidatorCatchesViolationsOn26ColumnGridWithoutExpansion() {
        // Build raw requests that address columns beyond index 25 (e.g. column 28 to 31) without expansion
        val badRequests = listOf<Map<String, Any?>>(
            mapOf(
                "updateDimensionProperties" to mapOf(
                    "range" to mapOf(
                        "sheetId" to 0,
                        "dimension" to "COLUMNS",
                        "startIndex" to 28,
                        "endIndex" to 31
                    ),
                    "properties" to mapOf("hiddenByUser" to true),
                    "fields" to "hiddenByUser"
                )
            ),
            mapOf(
                "mergeCells" to mapOf(
                    "range" to mapOf(
                        "sheetId" to 0,
                        "startRowIndex" to 0,
                        "endRowIndex" to 1,
                        "startColumnIndex" to 0,
                        "endColumnIndex" to 28
                    ),
                    "mergeType" to "MERGE_ALL"
                )
            ),
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf(
                        "sheetId" to 0,
                        "rowIndex" to 2,
                        "columnIndex" to 25
                    ),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf("userEnteredValue" to mapOf("stringValue" to "A")),
                                mapOf("userEnteredValue" to mapOf("stringValue" to "B"))
                            )
                        )
                    ),
                    "fields" to "userEnteredValue"
                )
            )
        )

        val violations = SheetGridValidator.validateRequests(
            requests = badRequests,
            initialColumnCount = 26,
            initialRowCount = 1000
        )

        assertFalse("Expected violations on unexpanded 26-column grid", violations.isEmpty())
        assertTrue(violations.any { it.requestType == "updateDimensionProperties" && it.attemptedIndex == 31 })
        assertTrue(violations.any { it.requestType == "mergeCells" && it.attemptedIndex == 28 })
        assertTrue(violations.any { it.requestType == "updateCells" && it.attemptedIndex == 27 })
    }

    @Test
    fun testMonthTabDec26ProvisionRequestsPassValidationWithSelfExpandingGridProperties() {
        val monthRequests = SheetProvisioner.buildProvisionRequests(
            sheetId = 10,
            tabTitle = "Dec26"
        )

        // Starting from a default 26-column grid, the batch expands itself via updateSheetProperties
        val violations = SheetGridValidator.validateRequests(
            requests = monthRequests,
            initialColumnCount = 26,
            initialRowCount = 1000
        )

        assertTrue(
            "Expected 0 violations for month tab provision batch with self-expanding grid, found: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun testMonthTabProvisionRequestsPassValidationWithSelfExpandingGridProperties() {
        val monthRequests = SheetProvisioner.buildProvisionRequests(
            sheetId = 20,
            tabTitle = "Nov26"
        )

        val violations = SheetGridValidator.validateRequests(
            requests = monthRequests,
            initialColumnCount = 26,
            initialRowCount = 1000
        )

        assertTrue(
            "Expected 0 violations for Nov26 provision batch with self-expanding grid, found: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun testDashboardRequestsFitWithinDefault26ColumnGrid() {
        val dashboardRequests = SheetProvisioner.buildDashboardRequests(
            dashboardSheetId = 0
        )

        val violations = SheetGridValidator.validateRequests(
            requests = dashboardRequests,
            initialColumnCount = 26,
            initialRowCount = 1000
        )

        assertTrue(
            "Expected 0 violations for Dashboard batch within 26-column grid, found: $violations",
            violations.isEmpty()
        )
    }

    private fun clearedRanges(requests: List<Map<String, Any?>>, sheetId: Int): Set<String> =
        requests.mapNotNull { it["setDataValidation"] as? Map<*, *> }
            .filter { it["rule"] == null }
            .map { it["range"] as Map<*, *> }
            .filter { it["sheetId"] == sheetId }
            .map {
                val idx = it["startColumnIndex"] as Int
                val col = if (idx < 26) ('A' + idx).toString() else "A" + ('A' + (idx - 26))
                "$col${(it["startRowIndex"] as Int) + 1}:$col${it["endRowIndex"]}"
            }
            .toSet()

    @Test
    fun testDashboardRepairRequestsClearDropdowns() {
        val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 5)
        assertEquals(setOf("C7:C23", "C26:C1000", "F7:F23", "F26:F1000"), clearedRanges(requests, 5))
        assertTrue(SheetGridValidator.validateRequests(requests, initialColumnCount = 26, initialRowCount = 1000).isEmpty())
    }

    @Test
    fun testMonthTabRepairRequestsClearDropdowns() {
        val requests = SheetProvisioner.buildProvisionRequests(sheetId = 7, tabTitle = "Nov26")
        assertEquals(setOf("AB4:AB1000", "C4:C1000", "F4:F1000", "K4:K1000"), clearedRanges(requests, 7))
        assertTrue(SheetGridValidator.validateRequests(requests, initialColumnCount = 26, initialRowCount = 1000).isEmpty())
        assertTrue(SheetRepairSafetyValidator.validateRepairRequests(requests).isEmpty())
    }
}
