package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants

/**
 * Validates that repair operations do not delete dimensions, sheets, ranges,
 * or clear data, never overwrite userEnteredValue in row index >= 3 on data tabs,
 * and never shrink grid dimensions (rowCount or columnCount).
 */
object SheetRepairSafetyValidator {

    data class Violation(
        val requestType: String,
        val reason: String,
        val details: String
    ) {
        override fun toString(): String = "$requestType: $reason ($details)"
    }

    private val FORBIDDEN_REQUEST_KEYS = setOf(
        "deleteDimension",
        "deleteSheet",
        "deleteRange"
    )

    /**
     * Requests that rewrite, move or remove existing cell contents. A repair of a data tab must never send them.
     * (The Sheets API has no request called "clear"; clearing is done with updateCells or deleteRange.)
     */
    private val DATA_TAB_FORBIDDEN_REQUEST_KEYS = setOf(
        "findReplace",
        "pasteData",
        "appendCells",
        "cutPaste",
        "deleteDuplicates",
        "trimWhitespace",
        "sortRange",
        "insertRange",
        "moveDimension"
    )

    /** True when a request "fields" mask can write cell values: it names userEnteredValue or is the wildcard "*". */
    private fun fieldsIncludeValues(fields: String): Boolean =
        fields.contains("userEnteredValue") || fields.split(",").any { it.trim() == "*" }

    fun validateRepairRequests(
        requests: List<Map<String, Any?>>,
        isDataTab: Boolean = true,
        currentGridRows: Int = 1000,
        currentGridCols: Int = SheetSchemaConstants.TOTAL_COLUMN_COUNT
    ): List<Violation> {
        val violations = mutableListOf<Violation>()

        for ((index, req) in requests.withIndex()) {
            // 1. Check forbidden deletion/clearing request types
            for (forbiddenKey in FORBIDDEN_REQUEST_KEYS) {
                if (req.containsKey(forbiddenKey)) {
                    violations.add(
                        Violation(
                            requestType = forbiddenKey,
                            reason = "Destructive request type '$forbiddenKey' is forbidden in repair operations",
                            details = "Req #$index"
                        )
                    )
                }
            }

            // 1b. Requests that rewrite or move existing data are forbidden on data tabs
            if (isDataTab) {
                for (key in DATA_TAB_FORBIDDEN_REQUEST_KEYS) {
                    if (req.containsKey(key)) {
                        violations.add(
                            Violation(
                                requestType = key,
                                reason = "Request type '$key' can change existing data and is forbidden in repair operations on data tabs",
                                details = "Req #$index"
                            )
                        )
                    }
                }
            }

            // 2. Check grid shrink in updateSheetProperties
            val updateSheetProps = req["updateSheetProperties"] as? Map<*, *>
            if (updateSheetProps != null) {
                val props = updateSheetProps["properties"] as? Map<*, *>
                val gridProps = props?.get("gridProperties") as? Map<*, *>
                val newRows = (gridProps?.get("rowCount") as? Number)?.toInt()
                val newCols = (gridProps?.get("columnCount") as? Number)?.toInt()

                if (newRows != null && newRows < currentGridRows) {
                    violations.add(
                        Violation(
                            requestType = "updateSheetProperties",
                            reason = "Cannot shrink grid rowCount from $currentGridRows to $newRows",
                            details = "Req #$index"
                        )
                    )
                }
                if (newCols != null && newCols < currentGridCols) {
                    violations.add(
                        Violation(
                            requestType = "updateSheetProperties",
                            reason = "Cannot shrink grid columnCount from $currentGridCols to $newCols",
                            details = "Req #$index"
                        )
                    )
                }
            }

            // 3. Ensure no userEnteredValue is written into row index >= 3 on data tabs
            if (isDataTab) {
                val updateCells = req["updateCells"] as? Map<*, *>
                if (updateCells != null) {
                    val fields = updateCells["fields"] as? String ?: ""
                    val writesValues = fieldsIncludeValues(fields)
                    if (writesValues) {
                        val start = updateCells["start"] as? Map<*, *>
                        val range = updateCells["range"] as? Map<*, *>
                        val startRow = (start?.get("rowIndex") as? Number)?.toInt()
                            ?: (range?.get("startRowIndex") as? Number)?.toInt()
                            ?: 0
                        val rows = updateCells["rows"] as? List<*>

                        if (rows != null) {
                            for ((offset, rowElem) in rows.withIndex()) {
                                val actualRow = startRow + offset
                                if (actualRow >= 3) {
                                    val rowMap = rowElem as? Map<*, *>
                                    val values = rowMap?.get("values") as? List<*>
                                    val hasVal = values?.any { cell ->
                                        val cellMap = cell as? Map<*, *>
                                        cellMap?.containsKey("userEnteredValue") == true
                                    } == true
                                    if (hasVal) {
                                        violations.add(
                                            Violation(
                                                requestType = "updateCells",
                                                reason = "Cannot write userEnteredValue into row index $actualRow (data rows 4 and below)",
                                                details = "Req #$index"
                                            )
                                        )
                                    }
                                }
                            }
                        } else if (startRow >= 3) {
                            violations.add(
                                Violation(
                                    requestType = "updateCells",
                                    reason = "Cannot write userEnteredValue into row index $startRow (data rows 4 and below)",
                                    details = "Req #$index"
                                )
                            )
                        }
                    }
                }

                val repeatCell = req["repeatCell"] as? Map<*, *>
                if (repeatCell != null) {
                    val fields = repeatCell["fields"] as? String ?: ""
                    if (fieldsIncludeValues(fields)) {
                        val range = repeatCell["range"] as? Map<*, *>
                        val endRow = (range?.get("endRowIndex") as? Number)?.toInt() ?: 0
                        if (endRow > 3) {
                            violations.add(
                                Violation(
                                    requestType = "repeatCell",
                                    reason = "Cannot repeatCell with userEnteredValue on row index >= 3",
                                    details = "Req #$index"
                                )
                            )
                        }
                    }
                }
            }
        }

        return violations
    }
}
