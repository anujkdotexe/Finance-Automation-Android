package com.financeautomation.data.remote

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.SheetSchemaConstants

/**
 * Generates Google Sheets API v4 batchUpdate requests to provision the complete
 * Master Ledger workbook and monthly tabs strictly matching the iOS master ledger layout:
 * 1. Dashboard: Title banner, last updated formula, 6 KPI cards with living burn formulas,
 *    Monthly summary trend table, Category spend breakdown table, and Active Settlements query table.
 * 2. Month tabs (for example Nov26): 31-column schema, frozen headers, spacer columns,
 *    currency number formats (₹#,##0), date formats (dd/mm/yyyy), time formats (hh:mm am/pm),
 *    dropdown data validations (Status, Repayment Status, Accounts, Categories),
 *    and conditional formatting (strikethrough on Void, italic on Carried).
 */
object SheetProvisioner {

    fun hexToColorMap(hex: String): Map<String, Float> {
        val cleanHex = hex.removePrefix("#")
        val r = cleanHex.substring(0, 2).toInt(16) / 255f
        val g = cleanHex.substring(2, 4).toInt(16) / 255f
        val b = cleanHex.substring(4, 6).toInt(16) / 255f
        return mapOf(
            "red" to r,
            "green" to g,
            "blue" to b,
            "alpha" to 1.0f
        )
    }

    /** Sheets API pixelSize is in pixels; the iOS reference gives row heights in points (1 pixel = 0.75 point). */
    fun pointsToPixels(points: Double): Int = Math.round(points / 0.75).toInt()

    /**
     * Dashboard row heights from the live iOS workbook: start row, end row (exclusive), 0-based, and points.
     * Row 3 and rows after 36 have no custom height and stay unset.
     */
    val DASHBOARD_ROW_HEIGHT_POINTS: List<Triple<Int, Int, Double>> = listOf(
        Triple(0, 1, 24.0), Triple(1, 2, 13.5), Triple(3, 4, 14.25),
        Triple(4, 5, 21.0), Triple(5, 6, 16.5), Triple(6, 7, 12.75), Triple(7, 23, 13.5),
        Triple(23, 24, 18.0), Triple(24, 25, 16.5), Triple(25, 26, 21.0), Triple(26, 36, 15.0)
    )

    /** Month tab row heights from the iOS reference (rows 4 to 1000 are 12.5 points). */
    val MONTH_TAB_ROW_HEIGHT_POINTS: List<Triple<Int, Int, Double>> = listOf(
        Triple(0, 1, 27.0), Triple(1, 2, 18.0), Triple(2, 3, 21.0), Triple(3, 1000, 12.5)
    )

    private const val ROW3_LAST_BORDERED_COL = 24 // column Y

    /** Debit table columns C, D, E, F, H, K (0-based) that are plain text in the iOS reference. */
    val DEBIT_TEXT_COLUMNS: List<Int> = listOf(2, 3, 4, 5, 7, 10)

    private fun thinBorder(): Map<String, Any?> =
        mapOf("style" to "SOLID", "width" to 1, "color" to mapOf("red" to 0f, "green" to 0f, "blue" to 0f))

    private fun thinBorders(): Map<String, Any?> =
        mapOf("top" to thinBorder(), "bottom" to thinBorder(), "left" to thinBorder(), "right" to thinBorder())

    /** Thin outer border around a rectangular block of cells. */
    private fun outerBordersRequest(sheetId: Int, startRow: Int, endRow: Int, startCol: Int, endCol: Int): Map<String, Any?> =
        mapOf(
            "updateBorders" to mapOf(
                "range" to mapOf(
                    "sheetId" to sheetId,
                    "startRowIndex" to startRow,
                    "endRowIndex" to endRow,
                    "startColumnIndex" to startCol,
                    "endColumnIndex" to endCol
                ),
                "top" to thinBorder(),
                "bottom" to thinBorder(),
                "left" to thinBorder(),
                "right" to thinBorder()
            )
        )

    private const val GOOGLE_SANS = "Google Sans"

    /** Rupee pattern exactly as in the live iOS workbook (no quotes around the sign). */
    const val CURRENCY_PATTERN: String = "₹#,##0"

    /** Text style (Google Sans, bold or italic as given, centered) for the blank cells of a title band row. */
    private fun bandStyleRequest(
        sheetId: Int,
        row: Int,
        startCol: Int,
        endCol: Int,
        fontSize: Int,
        color: Map<String, Float>,
        italic: Boolean
    ): Map<String, Any?> =
        mapOf(
            "repeatCell" to mapOf(
                "range" to mapOf(
                    "sheetId" to sheetId,
                    "startRowIndex" to row,
                    "endRowIndex" to row + 1,
                    "startColumnIndex" to startCol,
                    "endColumnIndex" to endCol
                ),
                "cell" to mapOf(
                    "userEnteredFormat" to mapOf(
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf(
                            "fontFamily" to GOOGLE_SANS,
                            "fontSize" to fontSize,
                            "bold" to !italic,
                            "italic" to italic,
                            "foregroundColor" to color
                        )
                    )
                ),
                "fields" to "userEnteredFormat(horizontalAlignment,verticalAlignment,textFormat(fontFamily,fontSize,bold,italic,foregroundColor))"
            )
        )

    /** A range to clear: 0-based start row, exclusive end row, 0-based column. */
    data class ColumnRowRange(val startRow: Int, val endRow: Int, val column: Int)

    /** Dashboard C7:C23, C26:C1000, F7:F23 and F26:F1000. */
    val DASHBOARD_DROPDOWN_RANGES = listOf(
        ColumnRowRange(6, 23, 2),
        ColumnRowRange(25, 1000, 2),
        ColumnRowRange(6, 23, 5),
        ColumnRowRange(25, 1000, 5)
    )

    /** Month tab AB4:AB1000, C4:C1000, F4:F1000 and K4:K1000. */
    val MONTH_TAB_DROPDOWN_RANGES = listOf(
        ColumnRowRange(3, 1000, 27),
        ColumnRowRange(3, 1000, 2),
        ColumnRowRange(3, 1000, 5),
        ColumnRowRange(3, 1000, 10)
    )

    /**
     * A setDataValidation request with no rule clears validation on its range.
     */
    fun clearDataValidationRequests(sheetId: Int, ranges: List<ColumnRowRange>): List<Map<String, Any?>> =
        ranges.map { r ->
            mapOf(
                "setDataValidation" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "startRowIndex" to r.startRow,
                        "endRowIndex" to r.endRow,
                        "startColumnIndex" to r.column,
                        "endColumnIndex" to r.column + 1
                    )
                )
            )
        }

    private val whiteColor = mapOf("red" to 1f, "green" to 1f, "blue" to 1f, "alpha" to 1f)
    private val grayTextColor = hexToColorMap("747474")
    private val darkTextColor = hexToColorMap("202020")
    private val burnRedColor = hexToColorMap("C52727")
    private val greenColor = hexToColorMap("2D7D32")
    private val purpleColor = hexToColorMap("7A1FA1")
    private val blueSectionColor = hexToColorMap("3D85C6")
    private val lightSlateBg = hexToColorMap("EBEEF1")
    private val darkNavyBg = hexToColorMap("19227E")
    private val accentNavyBg = hexToColorMap("273492")
    private val buttonNavyBg = hexToColorMap("283593")
    private val headerSlateBg = hexToColorMap("37474F")
    private val settlementsBannerBg = hexToColorMap("37464E")
    private val settlementsHeaderBg = hexToColorMap("1A237E")
    private val amberBg = hexToColorMap("F47F17")
    private val owePeopleHeaderBg = hexToColorMap("F57F17")
    private val debitsHeaderBg = hexToColorMap("C62828")
    private val inflowsHeaderBg = hexToColorMap("2E7D32")
    private val spacerGrayBg = hexToColorMap("ECEFF1")
    private val voidGrayColor = hexToColorMap("9E9E9E")
    private val monthlyHeaderBlueBg = hexToColorMap("1465BF")
    private val bodyAltRowBg = hexToColorMap("F9F9F9")

    /**
     * Builds complete batchUpdate requests to format and populate the Dashboard sheet
     * matching the iOS master ledger layout.
     */
    fun buildDashboardRequests(
        dashboardSheetId: Int,
        categories: List<String> = emptyList(),
        currentRowCount: Int = 1000,
        currentColumnCount: Int = SheetSchemaConstants.TOTAL_COLUMN_COUNT
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()
        val targetRowCount = maxOf(currentRowCount, 1000)
        val targetColumnCount = maxOf(currentColumnCount, SheetSchemaConstants.TOTAL_COLUMN_COUNT)

        // 0. Ensure grid dimensions (at least 1000 rows x 26 columns, never shrink)
        requests.add(
            mapOf(
                "updateSheetProperties" to mapOf(
                    "properties" to mapOf(
                        "sheetId" to dashboardSheetId,
                        "gridProperties" to mapOf(
                            "rowCount" to targetRowCount,
                            "columnCount" to targetColumnCount
                        )
                    ),
                    "fields" to "gridProperties(rowCount,columnCount)"
                )
            )
        )

        // Clear any dropdowns left by earlier versions (the iOS reference has none)
        requests.addAll(clearDataValidationRequests(dashboardSheetId, DASHBOARD_DROPDOWN_RANGES))

        // 1. Merged Ranges in Dashboard (idempotent: unmerge preceding merge)
        val mergeRanges = listOf(
            // Title banner & subtitles
            listOf(0, 1, 0, 20),   // A1:T1
            listOf(1, 2, 0, 20),   // A2:T2
            listOf(2, 3, 0, 18),   // A3:R3
            // KPI Cards (Row 4 header, Row 5 value, Row 6 subtitle)
            listOf(3, 4, 0, 3),    // A4:C4 (Current Burn)
            listOf(4, 5, 0, 3),    // A5:C5
            listOf(5, 6, 0, 3),    // A6:C6
            listOf(3, 4, 3, 6),    // D4:F4 (Prev Month)
            listOf(4, 5, 3, 6),    // D5:F5
            listOf(5, 6, 3, 6),    // D6:F6
            listOf(3, 4, 6, 9),    // G4:I4 (Owed To Me)
            listOf(4, 5, 6, 9),    // G5:I5
            listOf(5, 6, 6, 9),    // G6:I6
            listOf(3, 4, 9, 12),   // J4:L4 (I Owe People)
            listOf(4, 5, 9, 12),   // J5:L5
            listOf(5, 6, 9, 12),   // J6:L6
            listOf(3, 4, 12, 15),  // M4:O4 (Subscriptions)
            listOf(4, 5, 12, 15),  // M5:O5
            listOf(5, 6, 12, 15),  // M6:O6
            listOf(3, 4, 15, 18),  // P4:R4 (Daily Average)
            listOf(4, 5, 15, 18),  // P5:R5
            listOf(5, 6, 15, 18),  // P6:R6
            // Active Settlements
            listOf(23, 24, 0, 12), // A24:L24 (Active Settlements Banner)
            listOf(24, 25, 0, 6),  // A25:F25 (They Owe Me)
            listOf(24, 25, 7, 12)  // H25:L25 (I Owe People)
        )

        mergeRanges.forEach { r ->
            val range = mapOf(
                "sheetId" to dashboardSheetId,
                "startRowIndex" to r[0],
                "endRowIndex" to r[1],
                "startColumnIndex" to r[2],
                "endColumnIndex" to r[3]
            )
            requests.add(mapOf("unmergeCells" to mapOf("range" to range)))
            requests.add(
                mapOf(
                    "mergeCells" to mapOf(
                        "range" to range,
                        "mergeType" to "MERGE_ALL"
                    )
                )
            )
        }

        // 2. Row 1: Title Banner
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf(
                        "sheetId" to dashboardSheetId,
                        "startRowIndex" to 0,
                        "endRowIndex" to 1,
                        "startColumnIndex" to 0,
                        "endColumnIndex" to 29
                    ),
                    "cell" to mapOf(
                        "userEnteredFormat" to mapOf(
                            "backgroundColor" to darkNavyBg
                        )
                    ),
                    "fields" to "userEnteredFormat.backgroundColor"
                )
            )
        )

        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 0,
                colIndex = 0,
                value = mapOf("stringValue" to "PERSONAL FINANCE DASHBOARD — 2026"),
                format = mapOf(
                    "backgroundColor" to darkNavyBg,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 14
                    )
                )
            )
        )

        // 3. Row 2: Subtitle Last Updated
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf(
                        "sheetId" to dashboardSheetId,
                        "startRowIndex" to 1,
                        "endRowIndex" to 2,
                        "startColumnIndex" to 0,
                        "endColumnIndex" to 29
                    ),
                    "cell" to mapOf(
                        "userEnteredFormat" to mapOf(
                            "backgroundColor" to lightSlateBg
                        )
                    ),
                    "fields" to "userEnteredFormat.backgroundColor"
                )
            )
        )

        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 1,
                colIndex = 0,
                value = mapOf("formulaValue" to """="Last updated: " & TEXT(NOW(), "DD MMM YYYY, HH:MM")"""),
                format = mapOf(
                    "backgroundColor" to lightSlateBg,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to grayTextColor,
                        "bold" to false,
                        "italic" to true,
                        "fontSize" to 9
                    )
                )
            )
        )

        // 4. Row 3: Section Header
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf(
                        "sheetId" to dashboardSheetId,
                        "startRowIndex" to 2,
                        "endRowIndex" to 3,
                        "startColumnIndex" to 20,
                        "endColumnIndex" to 29
                    ),
                    "cell" to mapOf(
                        "userEnteredFormat" to mapOf(
                            "backgroundColor" to accentNavyBg
                        )
                    ),
                    "fields" to "userEnteredFormat.backgroundColor"
                )
            )
        )

        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 2,
                colIndex = 0,
                value = mapOf("stringValue" to "THIS MONTH AT A GLANCE"),
                format = mapOf(
                    "backgroundColor" to lightSlateBg,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "fontFamily" to GOOGLE_SANS,
                        "foregroundColor" to blueSectionColor,
                        "bold" to true,
                        "fontSize" to 11
                    )
                )
            )
        )

        // Styled (empty) cells to the right of the merged title rows, as in the iOS reference
        requests.add(bandStyleRequest(dashboardSheetId, row = 0, startCol = 20, endCol = 29, fontSize = 18, color = whiteColor, italic = false))
        requests.add(bandStyleRequest(dashboardSheetId, row = 1, startCol = 20, endCol = 29, fontSize = 10, color = hexToColorMap("536D79"), italic = true))
        requests.add(bandStyleRequest(dashboardSheetId, row = 2, startCol = 18, endCol = 29, fontSize = 11, color = whiteColor, italic = false))

        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf(
                        "sheetId" to dashboardSheetId,
                        "startRowIndex" to 2,
                        "endRowIndex" to 3,
                        "startColumnIndex" to 18,
                        "endColumnIndex" to 20
                    ),
                    "cell" to mapOf("userEnteredFormat" to mapOf("backgroundColor" to lightSlateBg)),
                    "fields" to "userEnteredFormat.backgroundColor"
                )
            )
        )

        // Thin box around each KPI card (rows 4 to 6)
        listOf(0, 3, 6, 9, 12, 15).forEach { startCol ->
            requests.add(outerBordersRequest(dashboardSheetId, startRow = 3, endRow = 6, startCol = startCol, endCol = startCol + 3))
        }

        // 5. KPI Header Titles (Row 4)
        val kpiHeaders = listOf(
            Triple(0, "CURRENT BURN", false),
            Triple(3, "PREV MONTH", false),
            Triple(6, "OWED TO ME", false),
            Triple(9, "I OWE PEOPLE", true),
            Triple(12, "SUBSCRIPTIONS", false),
            Triple(15, "DAILY AVERAGE", false)
        )

        kpiHeaders.forEach { (col, title, isNavyBg) ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 3,
                    colIndex = col,
                    value = mapOf("stringValue" to title),
                    format = mapOf(
                        "backgroundColor" to if (isNavyBg) buttonNavyBg else whiteColor,
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to buildMap {
                            put("foregroundColor", if (isNavyBg) whiteColor else grayTextColor)
                            put("bold", true)
                            // The live workbook leaves the navy I OWE PEOPLE header at the default size
                            if (!isNavyBg) put("fontSize", 8)
                        }
                    )
                )
            )
        }

        // 6. KPI Values & Formulas (Row 5)
        val kpiFormulas = listOf(
            // Current Burn (A5)
            Pair(
                0,
                Triple(
                    """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!I4:I"), INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Savings", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Transfer"), 0)""",
                    burnRedColor,
                    true
                )
            ),
            // Prev Month (D5)
            Pair(
                3,
                Triple(
                    """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "'!I4:I"), INDIRECT("'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "'!F4:F"), "<>Savings", INDIRECT("'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "'!F4:F"), "<>Transfer"), 0)""",
                    darkTextColor,
                    true
                )
            ),
            // Owed To Me (G5)
            Pair(
                6,
                Triple(
                    """=IFERROR(SUMIF(INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!K4:K"), "Pending", INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!J4:J")), 0)""",
                    greenColor,
                    true
                )
            ),
            // I Owe People (J5) - counts Pending plus Partial
            Pair(
                9,
                Triple(
                    """=IFERROR(SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!AB4:AB"), "Pending", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!Z4:Z")) + SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!AB4:AB"), "Partial", INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!Z4:Z")), 0)""",
                    burnRedColor,
                    true
                )
            ),
            // Subscriptions (M5)
            Pair(
                12,
                Triple(
                    """=IFERROR(SUMIF(INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!F4:F"), "Subscriptions", INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!I4:I")), 0)""",
                    purpleColor,
                    true
                )
            ),
            // Daily Average (P5)
            Pair(
                15,
                Triple(
                    """=IFERROR(A5 / DAY(TODAY()), 0)""",
                    hexToColorMap("1465BF"),
                    true
                )
            )
        )

        kpiFormulas.forEach { (col, data) ->
            val (formula, textColor, isCurrency) = data
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 4,
                    colIndex = col,
                    value = mapOf("formulaValue" to formula),
                    format = mapOf(
                        "backgroundColor" to whiteColor,
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf(
                            "foregroundColor" to textColor,
                            "bold" to true,
                            "fontSize" to 16
                        ),
                        "numberFormat" to mapOf(
                            "type" to "CURRENCY",
                            "pattern" to CURRENCY_PATTERN
                        )
                    )
                )
            )
        }

        // 7. KPI Subtitles (Row 6)
        val kpiSubtitles = listOf(
            0 to "This Month",
            3 to "Last Month",
            6 to "Receivables",
            9 to "Payables",
            12 to "Recurring",
            15 to "₹ / day"
        )

        kpiSubtitles.forEach { (col, sub) ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 5,
                    colIndex = col,
                    value = mapOf("stringValue" to sub),
                    format = mapOf(
                        "backgroundColor" to whiteColor,
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf(
                            "foregroundColor" to grayTextColor,
                            "bold" to false,
                            "fontSize" to 8
                        )
                    )
                )
            )
        }

        // 8. Monthly Outflow Trend Table (Cols U-W, Rows 5 to 17)
        // Header Row 5
        val monthlyHeaders = listOf(
            Triple(20, "Month", "CENTER"),
            Triple(21, "Personal Burn", "RIGHT"),
            Triple(22, "Total Outflow", "RIGHT")
        )
        monthlyHeaders.forEach { (col, title, align) ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 4,
                    colIndex = col,
                    value = mapOf("stringValue" to title),
                    format = mapOf(
                        "backgroundColor" to monthlyHeaderBlueBg,
                        "horizontalAlignment" to align,
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf(
                            "foregroundColor" to whiteColor,
                            "bold" to true,
                            "fontSize" to 9
                        )
                    )
                )
            )
        }

        // Month Rows 6 to 17
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        months.forEachIndexed { idx, m ->
            val rowNum = 6 + idx
            // Month Name (Col U)
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowNum - 1,
                    colIndex = 20,
                    value = mapOf("stringValue" to m),
                    format = mapOf(
                        "backgroundColor" to bodyAltRowBg,
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf("fontSize" to 10)
                    )
                )
            )
            // Personal Burn (Col V)
            val personalBurnFormula = """=IFERROR(SUMIFS(INDIRECT("'" & ${'$'}U$rowNum & RIGHT(YEAR(TODAY()), 2) & "'!I4:I"), INDIRECT("'" & ${'$'}U$rowNum & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Savings", INDIRECT("'" & ${'$'}U$rowNum & RIGHT(YEAR(TODAY()), 2) & "'!F4:F"), "<>Transfer"), 0)"""
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowNum - 1,
                    colIndex = 21,
                    value = mapOf("formulaValue" to personalBurnFormula),
                    format = mapOf(
                        "backgroundColor" to bodyAltRowBg,
                        "horizontalAlignment" to "RIGHT",
                        "verticalAlignment" to "MIDDLE",
                        "numberFormat" to mapOf(
                            "type" to "CURRENCY",
                            "pattern" to CURRENCY_PATTERN
                        ),
                        "textFormat" to mapOf("fontSize" to 10)
                    )
                )
            )
            // Total Outflow (Col W) - excludes [Carried*
            val totalOutflowFormula = """=IFERROR(SUMIFS(INDIRECT("'" & ${'$'}U$rowNum & RIGHT(YEAR(TODAY()), 2) & "'!G4:G"), INDIRECT("'" & ${'$'}U$rowNum & RIGHT(YEAR(TODAY()), 2) & "'!D4:D"), "<>[Carried*"), 0)"""
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowNum - 1,
                    colIndex = 22,
                    value = mapOf("formulaValue" to totalOutflowFormula),
                    format = mapOf(
                        "backgroundColor" to bodyAltRowBg,
                        "horizontalAlignment" to "RIGHT",
                        "verticalAlignment" to "MIDDLE",
                        "numberFormat" to mapOf(
                            "type" to "CURRENCY",
                            "pattern" to CURRENCY_PATTERN
                        ),
                        "textFormat" to mapOf("fontSize" to 10)
                    )
                )
            )
        }

        // 9. Category Breakdown Table (Cols Y-Z, Rows 5 to 15)
        // Header Row 5
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 4,
                colIndex = 24,
                value = mapOf("stringValue" to "Category"),
                format = mapOf(
                    "backgroundColor" to lightSlateBg,
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to darkTextColor,
                        "bold" to true,
                        "fontSize" to 9
                    )
                )
            )
        )
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 4,
                colIndex = 25,
                value = mapOf("stringValue" to "Spend"),
                format = mapOf(
                    "backgroundColor" to monthlyHeaderBlueBg,
                    "horizontalAlignment" to "RIGHT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 9
                    )
                )
            )
        )

        // Active Categories rows (10 categories Y6:Y15 including Savings and Other)
        val displayCategories = if (categories.isNotEmpty()) {
            categories.take(10)
        } else {
            listOf(
                CategoryConstants.CATEGORY_FOOD_AND_DRINK,
                CategoryConstants.CATEGORY_GROCERIES,
                CategoryConstants.CATEGORY_TRANSIT,
                CategoryConstants.CATEGORY_SUBSCRIPTIONS,
                CategoryConstants.CATEGORY_SHOPPING,
                CategoryConstants.CATEGORY_TRIP_TRAVEL,
                CategoryConstants.CATEGORY_HEALTH,
                CategoryConstants.CATEGORY_FAMILY,
                CategoryConstants.CATEGORY_SAVINGS,
                CategoryConstants.CATEGORY_OTHER
            )
        }

        displayCategories.forEachIndexed { idx, catName ->
            val rowNum = 6 + idx
            // Category Name (Col Y)
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowNum - 1,
                    colIndex = 24,
                    value = mapOf("stringValue" to catName),
                    format = mapOf(
                        "backgroundColor" to bodyAltRowBg,
                        "horizontalAlignment" to "LEFT",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf("fontSize" to 10)
                    )
                )
            )
            // Category Spend Formula (Col Z)
            val spendFormula = """=IFERROR(SUMIF(INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!F4:F"), Y$rowNum, INDIRECT("'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"'!I4:I")), 0)"""
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowNum - 1,
                    colIndex = 25,
                    value = mapOf("formulaValue" to spendFormula),
                    format = mapOf(
                        "backgroundColor" to bodyAltRowBg,
                        "horizontalAlignment" to "RIGHT",
                        "verticalAlignment" to "MIDDLE",
                        "numberFormat" to mapOf(
                            "type" to "CURRENCY",
                            "pattern" to CURRENCY_PATTERN
                        ),
                        "textFormat" to mapOf("fontSize" to 10)
                    )
                )
            )
        }

        // 10. Active Settlements & Pending Balances (Rows 24 to 27)
        // Row 24: Title Banner A24:L24
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 23,
                colIndex = 0,
                value = mapOf("stringValue" to "ACTIVE SETTLEMENTS & PENDING BALANCES"),
                format = mapOf(
                    "backgroundColor" to settlementsBannerBg,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 10
                    )
                )
            )
        )

        // Row 25: Section Banners A25:F25 and H25:L25
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 24,
                colIndex = 0,
                value = mapOf("stringValue" to "THEY OWE ME"),
                format = mapOf(
                    "backgroundColor" to greenColor,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 10
                    )
                )
            )
        )
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 24,
                colIndex = 7,
                value = mapOf("stringValue" to "I OWE PEOPLE"),
                format = mapOf(
                    "backgroundColor" to amberBg,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 10
                    )
                )
            )
        )

        // Row 26: Column Headers matching iOS template
        val theyOweMeHeaders = listOf("Person", "Original (₹)", "Paid Back (₹)", "Net Owed (₹)", "Oldest Date", "Txn Count")
        theyOweMeHeaders.forEachIndexed { cIdx, h ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 25,
                    colIndex = cIdx,
                    value = mapOf("stringValue" to h),
                    format = mapOf(
                        "backgroundColor" to settlementsHeaderBg,
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "wrapStrategy" to "WRAP",
                        "textFormat" to mapOf(
                            "foregroundColor" to whiteColor,
                            "bold" to true,
                            "fontSize" to 9
                        )
                    )
                )
            )
        }

        val iOwePeopleHeaders = listOf("Person", "Txn Count", "Amount I Owe (₹)", "Oldest Date", "Status")
        iOwePeopleHeaders.forEachIndexed { cIdx, h ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 25,
                    colIndex = 7 + cIdx,
                    value = mapOf("stringValue" to h),
                    format = mapOf(
                        "backgroundColor" to settlementsHeaderBg,
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "wrapStrategy" to "WRAP",
                        "textFormat" to mapOf(
                            "foregroundColor" to whiteColor,
                            "bold" to true,
                            "fontSize" to 9
                        )
                    )
                )
            )
        }

        // Thin borders as in the live workbook: banners, the header row (A26:L26, no right edge on L26) and the query cells
        requests.add(outerBordersRequest(dashboardSheetId, startRow = 23, endRow = 24, startCol = 0, endCol = 12))
        requests.add(outerBordersRequest(dashboardSheetId, startRow = 24, endRow = 25, startCol = 0, endCol = 6))
        requests.add(outerBordersRequest(dashboardSheetId, startRow = 24, endRow = 25, startCol = 7, endCol = 12))
        requests.add(
            mapOf(
                "updateBorders" to mapOf(
                    "range" to mapOf(
                        "sheetId" to dashboardSheetId,
                        "startRowIndex" to 25,
                        "endRowIndex" to 26,
                        "startColumnIndex" to 0,
                        "endColumnIndex" to 12
                    ),
                    "top" to thinBorder(),
                    "bottom" to thinBorder(),
                    "left" to thinBorder(),
                    "innerVertical" to thinBorder()
                )
            )
        )
        requests.add(outerBordersRequest(dashboardSheetId, startRow = 26, endRow = 27, startCol = 0, endCol = 1))
        requests.add(outerBordersRequest(dashboardSheetId, startRow = 26, endRow = 27, startCol = 7, endCol = 8))

        // Row 27: Dynamic Two-Stage QUERY Formulas
        val theyOweMeQuery = """=IFERROR(QUERY(QUERY(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!A4:K"), "SELECT H, G - I, G - I - J, J, A WHERE K = 'Pending' AND J > 0 AND H != 'Personal' LABEL G - I '', G - I - J ''", 0), "SELECT Col1, SUM(Col2), SUM(Col3), SUM(Col4), MIN(Col5), COUNT(Col1) GROUP BY Col1 LABEL Col1 '', SUM(Col2) '', SUM(Col3) '', SUM(Col4) '', MIN(Col5) '', COUNT(Col1) ''", 0), "")"""
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 26,
                colIndex = 0,
                value = mapOf("formulaValue" to theyOweMeQuery),
                format = mapOf(
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf("fontSize" to 10)
                )
            )
        )

        val iOwePeopleQuery = """=IFERROR(QUERY(QUERY(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!V4:AB"), "SELECT X, Z, V WHERE (AB = 'Pending' OR AB = 'Partial') AND Z > 0", 0), "SELECT Col1, COUNT(Col1), SUM(Col2), MIN(Col3) GROUP BY Col1 LABEL Col1 '', COUNT(Col1) '', SUM(Col2) '', MIN(Col3) ''", 0), "")"""
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 26,
                colIndex = 7,
                value = mapOf("formulaValue" to iOwePeopleQuery),
                format = mapOf(
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf("fontSize" to 10)
                )
            )
        )

        // 11. Charts: Clustered Column Chart (A7, 520x266) & Doughnut Chart (K7, 491x266)
        requests.addAll(
            ChartManager.buildChartRequests(
                dashboardSheetId = dashboardSheetId,
                columnChartColorPrimary = hexToColorMap("1465BF"),
                columnChartColorSecondary = hexToColorMap("90CAF8")
            )
        )

        // 12. Dashboard Column Widths (matching iOS master reference)
        val dashboardColWidths = listOf(
            0 to 383, 1 to 68, 2 to 68, 3 to 68, 4 to 93, 5 to 68, 6 to 10, 7 to 104, 8 to 59,
            9 to 71, 10 to 84, 11 to 68, 12 to 68, 13 to 68, 14 to 68, 15 to 68, 16 to 68, 17 to 68,
            18 to 85, 19 to 20, 20 to 85, 21 to 98, 22 to 144, 23 to 96, 24 to 104, 25 to 85,
            26 to 85, 27 to 85, 28 to 85
        )
        dashboardColWidths.forEach { (colIdx, px) ->
            requests.add(
                createUpdateDimensionPropertiesRequest(
                    sheetId = dashboardSheetId,
                    dimension = "COLUMNS",
                    startIndex = colIdx,
                    endIndex = colIdx + 1,
                    pixelSize = px
                )
            )
        }

        // 13. Dashboard Row Heights (iOS reference values in points, converted to the pixels the API expects)
        DASHBOARD_ROW_HEIGHT_POINTS.forEach { (startRow, endRowExclusive, points) ->
            requests.add(
                createUpdateDimensionPropertiesRequest(
                    sheetId = dashboardSheetId,
                    dimension = "ROWS",
                    startIndex = startRow,
                    endIndex = endRowExclusive,
                    pixelSize = pointsToPixels(points)
                )
            )
        }

        return requests
    }

    /**
     * Builds the complete list of Google Sheets v4 batchUpdate requests to provision a 31-column month tab
     * matching the iOS master ledger layout.
     */
    fun buildProvisionRequests(
        sheetId: Int,
        tabTitle: String,
        accounts: List<String> = emptyList(),
        categories: List<String> = emptyList(),
        currentRowCount: Int = 1000,
        currentColumnCount: Int = SheetSchemaConstants.TOTAL_COLUMN_COUNT,
        existingRuleFormulas: Set<String> = emptySet()
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()
        val targetRowCount = maxOf(currentRowCount, 1000)
        val targetColumnCount = maxOf(currentColumnCount, SheetSchemaConstants.TOTAL_COLUMN_COUNT)

        // 1. Ensure grid dimensions (at least 1000 rows x 31 columns, never shrink) and freeze top 3 rows
        requests.add(
            mapOf(
                "updateSheetProperties" to mapOf(
                    "properties" to mapOf(
                        "sheetId" to sheetId,
                        "gridProperties" to mapOf(
                            "rowCount" to targetRowCount,
                            "columnCount" to targetColumnCount,
                            "frozenRowCount" to 3
                        )
                    ),
                    "fields" to "gridProperties(rowCount,columnCount,frozenRowCount)"
                )
            )
        )

        // Clear any dropdowns left by earlier versions (the iOS reference has none)
        requests.addAll(clearDataValidationRequests(sheetId, MONTH_TAB_DROPDOWN_RANGES))

        // 2. Set Column widths for Spacers (Col L = 11, Col U = 20) to 10px
        listOf(SheetSchemaConstants.SPACER_1_COL_INDEX, SheetSchemaConstants.SPACER_2_COL_INDEX).forEach { colIndex ->
            requests.add(
                mapOf(
                    "updateDimensionProperties" to mapOf(
                        "range" to mapOf(
                            "sheetId" to sheetId,
                            "dimension" to "COLUMNS",
                            "startIndex" to colIndex,
                            "endIndex" to colIndex + 1
                        ),
                        "properties" to mapOf(
                            "pixelSize" to 10
                        ),
                        "fields" to "pixelSize"
                    )
                )
            )
        }

        // 3. Merges (idempotent: unmerge preceding merge to allow re-running):
        // Row 1 Title Banner: A1:AB1 (cols 0 to 28, row 0 to 1)
        val row1BannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 0,
            "endRowIndex" to 1,
            "startColumnIndex" to 0,
            "endColumnIndex" to 28
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to row1BannerRange)))
        requests.add(
            mapOf(
                "mergeCells" to mapOf(
                    "range" to row1BannerRange,
                    "mergeType" to "MERGE_ALL"
                )
            )
        )

        // Row 2 Section Group Merges:
        // Table 1 Debits: A2:K2 (cols 0 to 11, row 1 to 2)
        val table1Range = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 1,
            "endRowIndex" to 2,
            "startColumnIndex" to SheetSchemaConstants.TABLE_1_START_COL_INDEX,
            "endColumnIndex" to SheetSchemaConstants.TABLE_1_END_COL_INDEX + 1
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to table1Range)))
        requests.add(
            mapOf(
                "mergeCells" to mapOf(
                    "range" to table1Range,
                    "mergeType" to "MERGE_ALL"
                )
            )
        )

        // Table 2 Inflows: M2:T2 (cols 12 to 20, row 1 to 2)
        val table2Range = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 1,
            "endRowIndex" to 2,
            "startColumnIndex" to SheetSchemaConstants.TABLE_2_START_COL_INDEX,
            "endColumnIndex" to SheetSchemaConstants.TABLE_2_END_COL_INDEX + 1
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to table2Range)))
        requests.add(
            mapOf(
                "mergeCells" to mapOf(
                    "range" to table2Range,
                    "mergeType" to "MERGE_ALL"
                )
            )
        )

        // Table 3 Payables: V2:AB2 (cols 21 to 28, row 1 to 2)
        val table3Range = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 1,
            "endRowIndex" to 2,
            "startColumnIndex" to SheetSchemaConstants.TABLE_3_START_COL_INDEX,
            "endColumnIndex" to SheetSchemaConstants.TABLE_3_END_COL_INDEX + 1
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to table3Range)))
        requests.add(
            mapOf(
                "mergeCells" to mapOf(
                    "range" to table3Range,
                    "mergeType" to "MERGE_ALL"
                )
            )
        )

        // 4. Populate Row 1 Title Banner
        val bannerTitle = "EXPENSE LEDGER — ${tabTitle.uppercase()}"
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "startRowIndex" to 0,
                        "endRowIndex" to 1,
                        "startColumnIndex" to 0,
                        "endColumnIndex" to 28
                    ),
                    "cell" to mapOf(
                        "userEnteredValue" to mapOf("stringValue" to bannerTitle),
                        "userEnteredFormat" to mapOf(
                            "backgroundColor" to darkNavyBg,
                            "horizontalAlignment" to "CENTER",
                            "verticalAlignment" to "MIDDLE",
                            "textFormat" to mapOf(
                                "foregroundColor" to whiteColor,
                                "bold" to true,
                                "fontSize" to 16
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment)"
                )
            )
        )

        // 5. Populate Row 2 Section Headers
        val sectionDefs = listOf(
            Triple(0, 11, Pair("EXPENSES (MONEY OUT)", debitsHeaderBg)),
            Triple(11, 12, Pair("", spacerGrayBg)),
            Triple(12, 20, Pair("INFLOWS (MONEY IN)", inflowsHeaderBg)),
            Triple(20, 21, Pair("", spacerGrayBg)),
            Triple(21, 28, Pair("I OWE PEOPLE", owePeopleHeaderBg))
        )

        sectionDefs.forEach { (startCol, endCol, content) ->
            val (title, color) = content
            requests.add(
                mapOf(
                    "repeatCell" to mapOf(
                        "range" to mapOf(
                            "sheetId" to sheetId,
                            "startRowIndex" to 1,
                            "endRowIndex" to 2,
                            "startColumnIndex" to startCol,
                            "endColumnIndex" to endCol
                        ),
                        "cell" to mapOf(
                            "userEnteredValue" to mapOf("stringValue" to title),
                            "userEnteredFormat" to mapOf(
                                "backgroundColor" to color,
                                "horizontalAlignment" to "CENTER",
                                "verticalAlignment" to "MIDDLE",
                                "textFormat" to mapOf(
                                    "foregroundColor" to whiteColor,
                                    "bold" to true,
                                    "fontSize" to 10
                                )
                            )
                        ),
                        "fields" to "userEnteredValue,userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment)"
                    )
                )
            )
        }

        // Thin borders around each row 2 block (A2:K2, L2, M2:T2, U2, V2:AB2), as in the iOS reference
        sectionDefs.forEach { (startCol, endCol, _) ->
            requests.add(
                outerBordersRequest(sheetId, startRow = 1, endRow = 2, startCol = startCol, endCol = endCol)
            )
        }

        // Fill Spacer columns L and U with #ECEFF1 across all rows (0 to 1000)
        listOf(SheetSchemaConstants.SPACER_1_COL_INDEX, SheetSchemaConstants.SPACER_2_COL_INDEX).forEach { colIdx ->
            requests.add(
                mapOf(
                    "repeatCell" to mapOf(
                        "range" to mapOf(
                            "sheetId" to sheetId,
                            "startRowIndex" to 0,
                            "endRowIndex" to 1000,
                            "startColumnIndex" to colIdx,
                            "endColumnIndex" to colIdx + 1
                        ),
                        "cell" to mapOf(
                            "userEnteredFormat" to mapOf(
                                "backgroundColor" to spacerGrayBg
                            )
                        ),
                        "fields" to "userEnteredFormat.backgroundColor"
                    )
                )
            )
        }

        // Hide UUID columns AC, AD, AE (indices 28 to 31)
        requests.add(
            mapOf(
                "updateDimensionProperties" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "dimension" to "COLUMNS",
                        "startIndex" to SheetSchemaConstants.COL_INDEX_DEBIT_UUID,
                        "endIndex" to SheetSchemaConstants.TOTAL_COLUMN_COUNT
                    ),
                    "properties" to mapOf(
                        "hiddenByUser" to true
                    ),
                    "fields" to "hiddenByUser"
                )
            )
        )

        // Exact Column Widths matching iOS master template
        val colWidths = listOf(
            0 to 90, 1 to 75, 2 to 80, 3 to 140, 4 to 100, 5 to 110, 6 to 100, 7 to 120, 8 to 90,
            9 to 90, 10 to 80, 11 to 10, 12 to 90, 13 to 112, 14 to 90, 15 to 112, 16 to 120,
            17 to 125, 18 to 115, 19 to 115, 20 to 10, 21 to 95, 22 to 75, 23 to 95, 24 to 120,
            25 to 110, 26 to 95, 27 to 115
        )
        colWidths.forEach { (colIdx, px) ->
            requests.add(
                createUpdateDimensionPropertiesRequest(
                    sheetId = sheetId,
                    dimension = "COLUMNS",
                    startIndex = colIdx,
                    endIndex = colIdx + 1,
                    pixelSize = px
                )
            )
        }

        // Row heights (iOS reference values in points, converted to the pixels the API expects)
        MONTH_TAB_ROW_HEIGHT_POINTS.forEach { (startRow, endRowExclusive, points) ->
            requests.add(
                createUpdateDimensionPropertiesRequest(
                    sheetId = sheetId,
                    dimension = "ROWS",
                    startIndex = startRow,
                    endIndex = endRowExclusive,
                    pixelSize = pointsToPixels(points)
                )
            )
        }

        // 6. Populate Row 3 Column Headers
        val columnHeaders = SheetSchemaConstants.SHEET_COLUMNS.map { letter ->
            SheetSchemaConstants.SHEET_COLUMN_HEADERS[letter].orEmpty()
        }

        val row3Values = columnHeaders.mapIndexed { colIdx, header ->
            val isSpacer = colIdx == SheetSchemaConstants.SPACER_1_COL_INDEX || colIdx == SheetSchemaConstants.SPACER_2_COL_INDEX
            val format = mutableMapOf<String, Any?>(
                "backgroundColor" to (if (isSpacer) spacerGrayBg else headerSlateBg),
                "horizontalAlignment" to "CENTER",
                "wrapStrategy" to "WRAP",
                "textFormat" to mapOf(
                    "foregroundColor" to whiteColor,
                    "bold" to true,
                    "fontSize" to 9
                )
            )
            // The iOS reference has a vertical alignment and thin borders on A3:Y3 only; Z3:AB3 are plain
            if (colIdx <= ROW3_LAST_BORDERED_COL) {
                format["verticalAlignment"] = "MIDDLE"
                format["borders"] = thinBorders()
            }
            mapOf(
                // The spacer header cells are blank in the iOS reference
                "userEnteredValue" to mapOf("stringValue" to (if (isSpacer) "" else header)),
                "userEnteredFormat" to format
            )
        }

        requests.add(
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf(
                        "sheetId" to sheetId,
                        "rowIndex" to 2,
                        "columnIndex" to 0
                    ),
                    "rows" to listOf(
                        mapOf("values" to row3Values)
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment,wrapStrategy,borders)"
                )
            )
        )

        // 7. Column Number Formatting on Data Rows (Rows 4 to targetRowCount)
        // Currency Columns: Total Amount (6), My Share (8), Owed To Me (9), Inflow Amount (18), Payable Amount (25)
        listOf(6, 8, 9, 18, 25).forEach { colIdx ->
            requests.add(
                createNumberFormatRequest(
                    sheetId = sheetId,
                    startCol = colIdx,
                    endCol = colIdx + 1,
                    formatType = "CURRENCY",
                    pattern = CURRENCY_PATTERN,
                    align = "RIGHT",
                    endRow = targetRowCount
                )
            )
        }

        // Plain text format (@) on the debit table columns C, D, E, F, H and K, as in the iOS reference
        DEBIT_TEXT_COLUMNS.forEach { colIdx ->
            requests.add(
                mapOf(
                    "repeatCell" to mapOf(
                        "range" to mapOf(
                            "sheetId" to sheetId,
                            "startRowIndex" to 3,
                            "endRowIndex" to 999,
                            "startColumnIndex" to colIdx,
                            "endColumnIndex" to colIdx + 1
                        ),
                        "cell" to mapOf(
                            "userEnteredFormat" to mapOf(
                                "numberFormat" to mapOf("type" to "TEXT", "pattern" to "@")
                            )
                        ),
                        "fields" to "userEnteredFormat.numberFormat"
                    )
                )
            )
        }

        // Date Columns: Debit Date (0), Inflow Date (12), Payable Date (21), Repaid Date (26)
        listOf(0, 12, 21, 26).forEach { colIdx ->
            requests.add(
                createNumberFormatRequest(
                    sheetId = sheetId,
                    startCol = colIdx,
                    endCol = colIdx + 1,
                    formatType = "DATE",
                    pattern = "dd/mm/yyyy",
                    align = "CENTER",
                    endRow = targetRowCount
                )
            )
        }

        // Time Columns: Debit Time (1), Inflow Time (13), Payable Time (22)
        listOf(1, 13, 22).forEach { colIdx ->
            requests.add(
                createNumberFormatRequest(
                    sheetId = sheetId,
                    startCol = colIdx,
                    endCol = colIdx + 1,
                    formatType = "TIME",
                    pattern = "hh:mm am/pm",
                    align = "CENTER",
                    endRow = targetRowCount
                )
            )
        }

        // The iOS reference has no conditional formatting on the month tab, so none is added.
        // (existingRuleFormulas is kept in the signature for callers that read existing rules.)

        return requests
    }

    private fun createUpdateDimensionPropertiesRequest(
        sheetId: Int,
        dimension: String,
        startIndex: Int,
        endIndex: Int,
        pixelSize: Int? = null,
        hiddenByUser: Boolean? = null
    ): Map<String, Any?> {
        val properties = mutableMapOf<String, Any>()
        val fields = mutableListOf<String>()
        if (pixelSize != null) {
            properties["pixelSize"] = pixelSize
            fields.add("pixelSize")
        }
        if (hiddenByUser != null) {
            properties["hiddenByUser"] = hiddenByUser
            fields.add("hiddenByUser")
        }
        return mapOf(
            "updateDimensionProperties" to mapOf(
                "range" to mapOf(
                    "sheetId" to sheetId,
                    "dimension" to dimension,
                    "startIndex" to startIndex,
                    "endIndex" to endIndex
                ),
                "properties" to properties,
                "fields" to fields.joinToString(",")
            )
        )
    }

    private fun createCellRequest(
        sheetId: Int,
        rowIndex: Int,
        colIndex: Int,
        value: Map<String, Any?>,
        format: Map<String, Any?>
    ): Map<String, Any?> {
        return mapOf(
            "updateCells" to mapOf(
                "range" to mapOf(
                    "sheetId" to sheetId,
                    "startRowIndex" to rowIndex,
                    "endRowIndex" to rowIndex + 1,
                    "startColumnIndex" to colIndex,
                    "endColumnIndex" to colIndex + 1
                ),
                "rows" to listOf(
                    mapOf(
                        "values" to listOf(
                            mapOf(
                                "userEnteredValue" to value,
                                "userEnteredFormat" to format
                            )
                        )
                    )
                ),
                "fields" to "userEnteredValue,userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment,numberFormat,wrapStrategy)"
            )
        )
    }

    private fun createNumberFormatRequest(
        sheetId: Int,
        startCol: Int,
        endCol: Int,
        formatType: String,
        pattern: String,
        align: String,
        endRow: Int = 1000
    ): Map<String, Any?> {
        return mapOf(
            "repeatCell" to mapOf(
                "range" to mapOf(
                    "sheetId" to sheetId,
                    "startRowIndex" to 3,
                    "endRowIndex" to endRow,
                    "startColumnIndex" to startCol,
                    "endColumnIndex" to endCol
                ),
                "cell" to mapOf(
                    "userEnteredFormat" to mapOf(
                        "horizontalAlignment" to align,
                        "numberFormat" to mapOf(
                            "type" to formatType,
                            "pattern" to pattern
                        )
                    )
                ),
                "fields" to "userEnteredFormat(horizontalAlignment,numberFormat)"
            )
        )
    }
}
