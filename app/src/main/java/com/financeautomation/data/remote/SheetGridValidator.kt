package com.financeautomation.data.remote

/**
 * Validates that all batchUpdate requests in a Google Sheets operation address
 * cells and dimensions within the target sheet's grid boundaries (columnCount and rowCount).
 */
object SheetGridValidator {

    data class Violation(
        val requestType: String,
        val field: String,
        val attemptedIndex: Int,
        val limit: Int,
        val details: String
    ) {
        override fun toString(): String {
            return "$requestType: $field=$attemptedIndex exceeds grid limit of $limit ($details)"
        }
    }

    fun validateRequests(
        requests: List<Map<String, Any?>>,
        initialColumnCount: Int = 26,
        initialRowCount: Int = 1000
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        var currentCols = initialColumnCount
        var currentRows = initialRowCount

        for ((index, req) in requests.withIndex()) {
            // Track dynamic grid property expansion within the batch
            val updateSheetProps = req["updateSheetProperties"] as? Map<*, *>
            if (updateSheetProps != null) {
                val props = updateSheetProps["properties"] as? Map<*, *>
                val gridProps = props?.get("gridProperties") as? Map<*, *>
                val newCols = (gridProps?.get("columnCount") as? Number)?.toInt()
                val newRows = (gridProps?.get("rowCount") as? Number)?.toInt()
                if (newCols != null) currentCols = maxOf(currentCols, newCols)
                if (newRows != null) currentRows = maxOf(currentRows, newRows)
            }

            val appendDim = req["appendDimension"] as? Map<*, *>
            if (appendDim != null) {
                val dim = appendDim["dimension"] as? String
                val length = (appendDim["length"] as? Number)?.toInt() ?: 0
                if (dim == "COLUMNS") currentCols += length
                if (dim == "ROWS") currentRows += length
            }

            // 1. updateDimensionProperties
            val updateDim = req["updateDimensionProperties"] as? Map<*, *>
            if (updateDim != null) {
                val range = updateDim["range"] as? Map<*, *>
                val dim = range?.get("dimension") as? String
                val startIndex = (range?.get("startIndex") as? Number)?.toInt() ?: 0
                val endIndex = (range?.get("endIndex") as? Number)?.toInt() ?: 0

                if (dim == "COLUMNS") {
                    if (startIndex > currentCols) {
                        violations.add(Violation("updateDimensionProperties", "startIndex", startIndex, currentCols, "Req #$index"))
                    }
                    if (endIndex > currentCols) {
                        violations.add(Violation("updateDimensionProperties", "endIndex", endIndex, currentCols, "Req #$index"))
                    }
                } else if (dim == "ROWS") {
                    if (startIndex > currentRows) {
                        violations.add(Violation("updateDimensionProperties", "startIndex", startIndex, currentRows, "Req #$index"))
                    }
                    if (endIndex > currentRows) {
                        violations.add(Violation("updateDimensionProperties", "endIndex", endIndex, currentRows, "Req #$index"))
                    }
                }
            }

            // 2. updateCells
            val updateCells = req["updateCells"] as? Map<*, *>
            if (updateCells != null) {
                val start = updateCells["start"] as? Map<*, *>
                val startCol = (start?.get("columnIndex") as? Number)?.toInt() ?: 0
                val startRow = (start?.get("rowIndex") as? Number)?.toInt() ?: 0
                val rows = updateCells["rows"] as? List<*>

                if (rows != null) {
                    val maxRowIdx = startRow + rows.size
                    if (maxRowIdx > currentRows) {
                        violations.add(Violation("updateCells", "rowIndex", maxRowIdx, currentRows, "Req #$index"))
                    }
                    for (rowElem in rows) {
                        val rowMap = rowElem as? Map<*, *>
                        val values = rowMap?.get("values") as? List<*>
                        if (values != null) {
                            val maxColIdx = startCol + values.size
                            if (maxColIdx > currentCols) {
                                violations.add(Violation("updateCells", "columnIndex", maxColIdx, currentCols, "Req #$index"))
                                break
                            }
                        }
                    }
                }
            }

            // 3. mergeCells and unmergeCells
            val mergeCells = (req["mergeCells"] ?: req["unmergeCells"]) as? Map<*, *>
            if (mergeCells != null) {
                val range = mergeCells["range"] as? Map<*, *>
                val startCol = (range?.get("startColumnIndex") as? Number)?.toInt() ?: 0
                val endCol = (range?.get("endColumnIndex") as? Number)?.toInt() ?: 0
                val startRow = (range?.get("startRowIndex") as? Number)?.toInt() ?: 0
                val endRow = (range?.get("endRowIndex") as? Number)?.toInt() ?: 0

                if (startCol > currentCols) violations.add(Violation("mergeCells", "startColumnIndex", startCol, currentCols, "Req #$index"))
                if (endCol > currentCols) violations.add(Violation("mergeCells", "endColumnIndex", endCol, currentCols, "Req #$index"))
                if (startRow > currentRows) violations.add(Violation("mergeCells", "startRowIndex", startRow, currentRows, "Req #$index"))
                if (endRow > currentRows) violations.add(Violation("mergeCells", "endRowIndex", endRow, currentRows, "Req #$index"))
            }

            // 4. repeatCell
            val repeatCell = req["repeatCell"] as? Map<*, *>
            if (repeatCell != null) {
                val range = repeatCell["range"] as? Map<*, *>
                val startCol = (range?.get("startColumnIndex") as? Number)?.toInt() ?: 0
                val endCol = (range?.get("endColumnIndex") as? Number)?.toInt() ?: 0
                val startRow = (range?.get("startRowIndex") as? Number)?.toInt() ?: 0
                val endRow = (range?.get("endRowIndex") as? Number)?.toInt() ?: 0

                if (startCol > currentCols) violations.add(Violation("repeatCell", "startColumnIndex", startCol, currentCols, "Req #$index"))
                if (endCol > currentCols) violations.add(Violation("repeatCell", "endColumnIndex", endCol, currentCols, "Req #$index"))
                if (startRow > currentRows) violations.add(Violation("repeatCell", "startRowIndex", startRow, currentRows, "Req #$index"))
                if (endRow > currentRows) violations.add(Violation("repeatCell", "endRowIndex", endRow, currentRows, "Req #$index"))
            }

            // 5. setDataValidation
            val dataValidation = req["setDataValidation"] as? Map<*, *>
            if (dataValidation != null) {
                val range = dataValidation["range"] as? Map<*, *>
                val startCol = (range?.get("startColumnIndex") as? Number)?.toInt() ?: 0
                val endCol = (range?.get("endColumnIndex") as? Number)?.toInt() ?: 0

                if (startCol > currentCols) violations.add(Violation("setDataValidation", "startColumnIndex", startCol, currentCols, "Req #$index"))
                if (endCol > currentCols) violations.add(Violation("setDataValidation", "endColumnIndex", endCol, currentCols, "Req #$index"))
            }

            // 6. addConditionalFormatRule
            val condFormat = req["addConditionalFormatRule"] as? Map<*, *>
            if (condFormat != null) {
                val rule = condFormat["rule"] as? Map<*, *>
                val ranges = rule?.get("ranges") as? List<*>
                ranges?.forEach { rangeElem ->
                    val range = rangeElem as? Map<*, *>
                    val startCol = (range?.get("startColumnIndex") as? Number)?.toInt() ?: 0
                    val endCol = (range?.get("endColumnIndex") as? Number)?.toInt() ?: 0
                    if (startCol > currentCols) violations.add(Violation("addConditionalFormatRule", "startColumnIndex", startCol, currentCols, "Req #$index"))
                    if (endCol > currentCols) violations.add(Violation("addConditionalFormatRule", "endColumnIndex", endCol, currentCols, "Req #$index"))
                }
            }
        }
        return violations
    }
}
