package com.financeautomation.data.remote

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.domain.model.Subscription

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

    /** Month tab row heights from the live iOS workbook; rows 4 and below have no custom height (default 15.75 points). */
    val MONTH_TAB_ROW_HEIGHT_POINTS: List<Triple<Int, Int, Double>> = listOf(
        Triple(0, 1, 30.0), Triple(1, 2, 18.0), Triple(2, 3, 21.0)
    )

    /** "Nov26" becomes "EXPENSE LEDGER — NOVEMBER 2026"; a tab name that is not a month tab is upper-cased as is. */
    fun monthTabBannerTitle(tabTitle: String): String {
        val month = try {
            java.time.YearMonth.parse(tabTitle, java.time.format.DateTimeFormatter.ofPattern("MMMyy", java.util.Locale.US))
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US))
                .uppercase()
        } catch (e: java.time.format.DateTimeParseException) {
            tabTitle.uppercase()
        }
        return "EXPENSE LEDGER — $month"
    }

    private const val ROW3_LAST_BORDERED_COL = 25 // column Z

    /** Row 2 border sides per column index, from the live workbook. */
    val ROW2_BORDER_SIDES: Map<Int, Set<String>> = mapOf(
        0 to setOf("top", "bottom", "left"),
        12 to setOf("top", "bottom", "left"),
        13 to setOf("bottom"),
        21 to setOf("top", "bottom"),
        22 to setOf("top", "bottom")
    )

    /** Thin borders on the named sides of a single cell. */
    private fun cellBordersRequest(sheetId: Int, row: Int, col: Int, sides: Set<String>): Map<String, Any?> {
        val request = mutableMapOf<String, Any?>(
            "range" to mapOf(
                "sheetId" to sheetId,
                "startRowIndex" to row,
                "endRowIndex" to row + 1,
                "startColumnIndex" to col,
                "endColumnIndex" to col + 1
            )
        )
        sides.forEach { request[it] = thinBorder() }
        return mapOf("updateBorders" to request)
    }

    /** Debit table columns C, D, E, F, H, K, L (0-based) that are plain text in the iOS reference. */
    val DEBIT_TEXT_COLUMNS: List<Int> = listOf(2, 3, 4, 5, 7, 10, 11)

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

    /** Month tab dropdown ranges: C4:C1000 (Account), F4:F1000 (Category), K4:K1000 (Status), AC4:AC1000 (Repayment Status). */
    val MONTH_TAB_DROPDOWN_RANGES = listOf(
        ColumnRowRange(3, 1000, 28),
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
    private val darkNavyBg = hexToColorMap("1A237E")
    private val ledgerBannerBg = hexToColorMap("1A237E")
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
        currentColumnCount: Int = SheetSchemaConstants.TOTAL_COLUMN_COUNT,
        year: Int = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).year
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()
        val targetRowCount = maxOf(currentRowCount, 1000)
        val targetColumnCount = maxOf(currentColumnCount, SheetSchemaConstants.TOTAL_COLUMN_COUNT)
        val yy = (year % 100).toString().padStart(2, '0')

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
            listOf(24, 25, 7, 12), // H25:L25 (I Owe People)

            // Subscriptions Band
            listOf(42, 43, 0, 18), // A43:R43 Banner
            listOf(43, 44, 0, 3),  // A44:C44 (Active Subs Header)
            listOf(44, 45, 0, 3),  // A45:C45 (Active Subs Value)
            listOf(45, 46, 0, 3),  // A46:C46 (Active Subs Subtitle)
            listOf(43, 44, 3, 6),  // D44:F44 (Monthly Eq Header)
            listOf(44, 45, 3, 6),  // D45:F45 (Monthly Eq Value)
            listOf(45, 46, 3, 6),  // D46:F46 (Monthly Eq Subtitle)
            listOf(43, 44, 6, 9),  // G44:I44 (Next Due Header)
            listOf(44, 45, 6, 9),  // G45:I45 (Next Due Value)
            listOf(45, 46, 6, 9),  // G46:I46 (Next Due Subtitle)
            listOf(47, 48, 0, 9),  // A48:I48 (Closed Subs Header Banner)
            listOf(54, 55, 0, 18), // A55:R55 (Subscriptions Pointer Note)

            // Trips Band
            listOf(55, 56, 0, 18), // A56:R56 Banner
            listOf(56, 57, 0, 3),  // A57:C57 (Active Trip Header)
            listOf(57, 58, 0, 3),  // A58:C58 (Active Trip Value)
            listOf(58, 59, 0, 3),  // A59:C59 (Active Trip Subtitle)
            listOf(56, 57, 3, 6),  // D57:F57 (Active Spend Header)
            listOf(57, 58, 3, 6),  // D58:F58 (Active Spend Value)
            listOf(58, 59, 3, 6),  // D59:F59 (Active Spend Subtitle)
            listOf(56, 57, 6, 9),  // G57:I57 (Trip Spend Month Header)
            listOf(57, 58, 6, 9),  // G58:I58 (Trip Spend Month Value)
            listOf(58, 59, 6, 9),  // G59:I59 (Trip Spend Month Subtitle)
            listOf(56, 57, 9, 12), // J57:L57 (Trips Year Header)
            listOf(57, 58, 9, 12), // J58:L58 (Trips Year Value)
            listOf(58, 59, 9, 12), // J59:L59 (Trips Year Subtitle)
            listOf(60, 61, 0, 18), // A61:R61 (Trips Pointer Note)

            // Insights Band
            listOf(63, 64, 0, 18)  // A64:R64 Banner
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
                value = mapOf("stringValue" to "PERSONAL FINANCE DASHBOARD - $year"),
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

        // 4. Row 3: helper data columns U3:AC3 must have no fill (clears the old dark stripe on existing workbooks)
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
                        "userEnteredFormat" to emptyMap<String, Any?>()
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
            Triple(9, "I OWE PEOPLE", false),
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
                    DashboardFormulaBuilder.buildCurrentBurnFormula(emptyList(), year),
                    burnRedColor,
                    true
                )
            ),
            // Prev Month (D5)
            Pair(
                3,
                Triple(
                    DashboardFormulaBuilder.buildPrevMonthBurnFormula(emptyList(), year),
                    darkTextColor,
                    true
                )
            ),
            // Owed To Me (G5)
            Pair(
                6,
                Triple(
                    """=IFERROR(SUMIF(INDIRECT("'"&TEXT(TODAY(),"mmm")&"$yy"&"'!K4:K"), "Pending", INDIRECT("'"&TEXT(TODAY(),"mmm")&"$yy"&"'!J4:J")), 0)""",
                    greenColor,
                    true
                )
            ),
            // I Owe People (J5) - counts Pending plus Partial
            Pair(
                9,
                Triple(
                    """=IFERROR(SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!AC4:AC"), "Pending", INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!AA4:AA")) + SUMIF(INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!AC4:AC"), "Partial", INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!AA4:AA")), 0)""",
                    burnRedColor,
                    true
                )
            ),
            // Subscriptions (M5)
            Pair(
                12,
                Triple(
                    """=IFERROR(SUMIF(INDIRECT("'"&TEXT(TODAY(),"mmm")&"$yy"&"'!F4:F"), "Subscriptions", INDIRECT("'"&TEXT(TODAY(),"mmm")&"$yy"&"'!I4:I")), 0)""",
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
            val personalBurnFormula = DashboardFormulaBuilder.buildMonthlySummaryBurnFormula(rowNum, emptyList())
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
            val totalOutflowFormula = """=IFERROR(SUMIFS(INDIRECT("'" & ${'$'}U$rowNum & "$yy" & "'!G4:G"), INDIRECT("'" & ${'$'}U$rowNum & "$yy" & "'!D4:D"), "<>[Carried*"), 0)"""
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

        // Active Categories rows (all active categories, minimum 10 rows for clean layout, including Savings and Other)
        val defaultCats = listOf(
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
        val displayCategories = if (categories.isNotEmpty()) {
            val list = categories.toMutableList()
            if (!list.any { it.equals(CategoryConstants.CATEGORY_SAVINGS, ignoreCase = true) }) {
                list.add(CategoryConstants.CATEGORY_SAVINGS)
            }
            if (!list.any { it.equals(CategoryConstants.CATEGORY_OTHER, ignoreCase = true) }) {
                list.add(CategoryConstants.CATEGORY_OTHER)
            }
            list
        } else {
            defaultCats
        }
        val categoryCount = maxOf(10, displayCategories.size)

        for (idx in 0 until categoryCount) {
            val rowNum = 6 + idx
            val catName = displayCategories.getOrNull(idx) ?: ""
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
            val spendFormula = if (catName.isNotBlank()) DashboardFormulaBuilder.buildCategorySpendFormula(rowNum) else ""
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowNum - 1,
                    colIndex = 25,
                    value = if (catName.isNotBlank()) mapOf("formulaValue" to spendFormula) else mapOf("stringValue" to ""),
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

        // The I OWE PEOPLE query returns four columns (person, count, amount, oldest date); there is no Status column
        val iOwePeopleHeaders = listOf("Person", "Txn Count", "Amount I Owe (₹)", "Oldest Date")
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
        val theyOweMeQuery = DashboardFormulaBuilder.buildTheyOweMeQueryFormula(year)
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

        val iOwePeopleQuery = DashboardFormulaBuilder.buildIOwePeopleQueryFormula(year)
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

        // Oldest Date columns of both settlement tables hold dates; show them as yyyy-mm-dd instead of serial numbers
        listOf(4, 10).forEach { dateCol ->
            requests.add(
                mapOf(
                    "repeatCell" to mapOf(
                        "range" to mapOf(
                            "sheetId" to dashboardSheetId,
                            "startRowIndex" to 26,
                            "endRowIndex" to 40,
                            "startColumnIndex" to dateCol,
                            "endColumnIndex" to dateCol + 1
                        ),
                        "cell" to mapOf(
                            "userEnteredFormat" to mapOf(
                                "numberFormat" to mapOf(
                                    "type" to "DATE",
                                    "pattern" to "yyyy-mm-dd"
                                )
                            )
                        ),
                        "fields" to "userEnteredFormat.numberFormat"
                    )
                )
            )
        }

        // 11. Subscriptions Band (Rows 43 to 54)
        // Row 43 Banner (0-based 42)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 42,
                colIndex = 0,
                value = mapOf("stringValue" to "SUBSCRIPTIONS & RECURRING EXPENSES"),
                format = mapOf(
                    "backgroundColor" to settlementsBannerBg,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 11
                    )
                )
            )
        )

        // KPI Card Boxes (Rows 44 to 46)
        listOf(0, 3, 6).forEach { startCol ->
            requests.add(outerBordersRequest(dashboardSheetId, startRow = 43, endRow = 46, startCol = startCol, endCol = startCol + 3))
        }

        // Subscriptions KPI Headers (Row 44, index 43)
        val subHeaders = listOf(
            0 to "ACTIVE SUBSCRIPTIONS",
            3 to "MONTHLY EQUIVALENT",
            6 to "NEXT DUE"
        )
        subHeaders.forEach { (col, title) ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 43,
                    colIndex = col,
                    value = mapOf("stringValue" to title),
                    format = mapOf(
                        "backgroundColor" to lightSlateBg,
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf(
                            "foregroundColor" to darkTextColor,
                            "bold" to true,
                            "fontSize" to 8
                        )
                    )
                )
            )
        }

        // Subscriptions KPI Values (Row 45, index 44)
        // 1. Active Subscriptions Count (A45)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 44,
                colIndex = 0,
                value = mapOf("formulaValue" to DashboardFormulaBuilder.buildSubscriptionsActiveCountFormula()),
                format = mapOf(
                    "backgroundColor" to whiteColor,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to purpleColor,
                        "bold" to true,
                        "fontSize" to 16
                    ),
                    "numberFormat" to mapOf(
                        "type" to "NUMBER",
                        "pattern" to "#,##0"
                    )
                )
            )
        )
        // 2. Monthly Equivalent (D45)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 44,
                colIndex = 3,
                value = mapOf("formulaValue" to DashboardFormulaBuilder.buildSubscriptionsMonthlyEquivalentFormula()),
                format = mapOf(
                    "backgroundColor" to whiteColor,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to darkTextColor,
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
        // 3. Next Due Date (G45)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 44,
                colIndex = 6,
                value = mapOf("formulaValue" to DashboardFormulaBuilder.buildNextDueSubscriptionFormula()),
                format = mapOf(
                    "backgroundColor" to whiteColor,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to hexToColorMap("1465BF"),
                        "bold" to true,
                        "fontSize" to 12
                    )
                )
            )
        )

        // Subscriptions KPI Subtitles (Row 46, index 45)
        val subSubtitles = listOf(
            0 to "Currently Active",
            3 to "₹ / month (normalized)",
            6 to "Upcoming Renewal"
        )
        subSubtitles.forEach { (col, sub) ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 45,
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

        // Closed Subscriptions Header Banner (Row 48, index 47)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 47,
                colIndex = 0,
                value = mapOf("stringValue" to "RECENTLY CLOSED (MAX 5)"),
                format = mapOf(
                    "backgroundColor" to monthlyHeaderBlueBg,
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 9
                    )
                )
            )
        )

        // Closed Subscriptions Table Headers (Row 49, index 48)
        val closedHeaders = listOf(
            0 to "Name",
            1 to "Closed Date",
            2 to "Paid in Total (₹)"
        )
        closedHeaders.forEach { (col, title) ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 48,
                    colIndex = col,
                    value = mapOf("stringValue" to title),
                    format = mapOf(
                        "backgroundColor" to lightSlateBg,
                        "horizontalAlignment" to if (col == 2) "RIGHT" else if (col == 0) "LEFT" else "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf(
                            "foregroundColor" to darkTextColor,
                            "bold" to true,
                            "fontSize" to 8
                        )
                    )
                )
            )
        }

        // Closed Subscriptions Query Formula (Row 50, index 49, cell A50)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 49,
                colIndex = 0,
                value = mapOf("formulaValue" to DashboardFormulaBuilder.buildClosedSubscriptionsQueryFormula()),
                format = mapOf(
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf("fontSize" to 10)
                )
            )
        )

        // Subscriptions Pointer Note (Row 55, index 54, cell A55)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 54,
                colIndex = 0,
                value = mapOf("stringValue" to "* Full list, payment history, and closed subscriptions on the Subscriptions tab"),
                format = mapOf(
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to grayTextColor,
                        "italic" to true,
                        "fontSize" to 8
                    )
                )
            )
        )

        // 12. Trips Band (Rows 56 to 61)
        // Row 56 Banner (0-based 55)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 55,
                colIndex = 0,
                value = mapOf("stringValue" to "TRIPS & EXPEDITIONS SUMMARY"),
                format = mapOf(
                    "backgroundColor" to settlementsBannerBg,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 11
                    )
                )
            )
        )

        // KPI Card Boxes (Rows 57 to 59)
        listOf(0, 3, 6, 9).forEach { startCol ->
            requests.add(outerBordersRequest(dashboardSheetId, startRow = 56, endRow = 59, startCol = startCol, endCol = startCol + 3))
        }

        // Trips KPI Headers (Row 57, index 56)
        val tripHeaders = listOf(
            0 to "ACTIVE TRIP",
            3 to "ACTIVE TRIP SPEND",
            6 to "TRIP SPEND THIS MONTH",
            9 to "TRIPS THIS YEAR"
        )
        tripHeaders.forEach { (col, title) ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 56,
                    colIndex = col,
                    value = mapOf("stringValue" to title),
                    format = mapOf(
                        "backgroundColor" to lightSlateBg,
                        "horizontalAlignment" to "CENTER",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf(
                            "foregroundColor" to darkTextColor,
                            "bold" to true,
                            "fontSize" to 8
                        )
                    )
                )
            )
        }

        // Trips KPI Values (Row 58, index 57)
        // 1. Active Trip Name (A58)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 57,
                colIndex = 0,
                value = mapOf("formulaValue" to """=IFERROR(INDEX(FILTER(Trips!A4:A, Trips!D4:D="Active"), 1), "None")"""),
                format = mapOf(
                    "backgroundColor" to whiteColor,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to hexToColorMap("1465BF"),
                        "bold" to true,
                        "fontSize" to 14
                    )
                )
            )
        )
        // 2. Active Trip Spend (D58)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 57,
                colIndex = 3,
                value = mapOf("formulaValue" to """=IFERROR(INDEX(FILTER(Trips!F4:F, Trips!D4:D="Active"), 1), 0)"""),
                format = mapOf(
                    "backgroundColor" to whiteColor,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to darkTextColor,
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
        // 3. Trip Spend This Month (G58) - excludes Void, Savings, Transfer
        val tripSpendMonthFormula = """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!I4:I"), INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!E4:E"), "?*", INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!D4:D"), "<>[Void]*", INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!F4:F"), "<>Savings", INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!F4:F"), "<>Transfer"), 0)"""
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 57,
                colIndex = 6,
                value = mapOf("formulaValue" to tripSpendMonthFormula),
                format = mapOf(
                    "backgroundColor" to whiteColor,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to burnRedColor,
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
        // 4. Trips This Year (J58)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 57,
                colIndex = 9,
                value = mapOf("formulaValue" to """=IFERROR(COUNTA(FILTER(Trips!A4:A, Trips!A4:A<>"")), 0)"""),
                format = mapOf(
                    "backgroundColor" to whiteColor,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to darkTextColor,
                        "bold" to true,
                        "fontSize" to 16
                    ),
                    "numberFormat" to mapOf(
                        "type" to "NUMBER",
                        "pattern" to "#,##0"
                    )
                )
            )
        )

        // Trips KPI Subtitles (Row 59, index 58)
        val tripSubtitles = listOf(
            0 to "Current Expedition",
            3 to "Total Paid",
            6 to "My Share (excl. Void/Savings)",
            9 to "Total Tracked"
        )
        tripSubtitles.forEach { (col, sub) ->
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = 58,
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

        // Trips Pointer Note (Row 61, index 60, cell A61)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 60,
                colIndex = 0,
                value = mapOf("stringValue" to "* Full breakdown, days, companion settlements, and top categories on the Trips tab"),
                format = mapOf(
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to grayTextColor,
                        "italic" to true,
                        "fontSize" to 8
                    )
                )
            )
        )

        // 13. Insights Band (Rows 64 to 90) - Single-Slice Trip Rule
        // Row 64 Banner (0-based 63)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 63,
                colIndex = 0,
                value = mapOf("stringValue" to "MONTHLY SPEND BREAKDOWN (CATEGORIES & TRIPS)"),
                format = mapOf(
                    "backgroundColor" to settlementsBannerBg,
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf(
                        "foregroundColor" to whiteColor,
                        "bold" to true,
                        "fontSize" to 11
                    )
                )
            )
        )

        // Insights Table Header (Row 64, index 63, Cols U-V)
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = 63,
                colIndex = 20,
                value = mapOf("stringValue" to "Item / Slice"),
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
                rowIndex = 63,
                colIndex = 21,
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

        // Non-trip living burn categories for insights slices (exclude Trip / Travel category since trip is its own slice, and exclude Savings and Transfer which are not living burn)
        val insightsCategories = displayCategories.filter {
            !it.equals(CategoryConstants.CATEGORY_TRIP_TRAVEL, ignoreCase = true) &&
            !it.equals(SheetSchemaConstants.SAVINGS_KEYWORD, ignoreCase = true) &&
            !it.equals("Transfer", ignoreCase = true)
        }
        // categories + 1 trip slice. Refunds are negative and a pie chart cannot plot a negative value,
        // so the refunds row is written below the chart range and is not charted.
        val insightsCount = insightsCategories.size + 1

        // Clear the whole Insights data area first so rows from an earlier, longer category list do not stay.
        requests.add(
            mapOf(
                "updateCells" to mapOf(
                    "range" to mapOf(
                        "sheetId" to dashboardSheetId,
                        "startRowIndex" to 64,
                        "endRowIndex" to 90,
                        "startColumnIndex" to 20,
                        "endColumnIndex" to 22
                    ),
                    "fields" to "userEnteredValue"
                )
            )
        )

        insightsCategories.forEachIndexed { idx, catName ->
            val rowNum = 65 + idx
            // Item Name (Col U)
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowNum - 1,
                    colIndex = 20,
                    value = mapOf("stringValue" to catName),
                    format = mapOf(
                        "backgroundColor" to bodyAltRowBg,
                        "horizontalAlignment" to "LEFT",
                        "verticalAlignment" to "MIDDLE",
                        "textFormat" to mapOf("fontSize" to 10)
                    )
                )
            )
            // Item Spend (Col V): Category spend where Column E is blank (non-trip) and not Void
            val catNonTripFormula = DashboardFormulaBuilder.buildInsightCategorySpendFormula(rowNum)
            requests.add(
                createCellRequest(
                    sheetId = dashboardSheetId,
                    rowIndex = rowNum - 1,
                    colIndex = 21,
                    value = mapOf("formulaValue" to catNonTripFormula),
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

        // Trips Slice row (after categories)
        val tripSliceRowNum = 65 + insightsCategories.size
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = tripSliceRowNum - 1,
                colIndex = 20,
                value = mapOf("stringValue" to "Trips"),
                format = mapOf(
                    "backgroundColor" to bodyAltRowBg,
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf("fontSize" to 10, "bold" to true)
                )
            )
        )
        // Trip Slice Spend: sum across tagged trip rows excluding Void, Savings, Transfer
        val tripSliceFormula = DashboardFormulaBuilder.buildInsightTripSpendFormula()
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = tripSliceRowNum - 1,
                colIndex = 21,
                value = mapOf("formulaValue" to tripSliceFormula),
                format = mapOf(
                    "backgroundColor" to bodyAltRowBg,
                    "horizontalAlignment" to "RIGHT",
                    "verticalAlignment" to "MIDDLE",
                    "numberFormat" to mapOf(
                        "type" to "CURRENCY",
                        "pattern" to CURRENCY_PATTERN
                    ),
                    "textFormat" to mapOf("fontSize" to 10, "bold" to true)
                )
            )
        )

        // Refunds Slice row (subtracts refund inflows from Table 2 to maintain parity with Burn KPI)
        // Placed one row below the last charted row (the chart range is at least 10 rows) so it is never plotted.
        val refundsSliceRowNum = 65 + maxOf(10, insightsCount) + 1
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = refundsSliceRowNum - 1,
                colIndex = 20,
                value = mapOf("stringValue" to "Refunds (not charted)"),
                format = mapOf(
                    "backgroundColor" to bodyAltRowBg,
                    "horizontalAlignment" to "LEFT",
                    "verticalAlignment" to "MIDDLE",
                    "textFormat" to mapOf("fontSize" to 10, "bold" to true)
                )
            )
        )
        val refundsSliceFormula = DashboardFormulaBuilder.buildInsightRefundsFormula()
        requests.add(
            createCellRequest(
                sheetId = dashboardSheetId,
                rowIndex = refundsSliceRowNum - 1,
                colIndex = 21,
                value = mapOf("formulaValue" to refundsSliceFormula),
                format = mapOf(
                    "backgroundColor" to bodyAltRowBg,
                    "horizontalAlignment" to "RIGHT",
                    "verticalAlignment" to "MIDDLE",
                    "numberFormat" to mapOf(
                        "type" to "CURRENCY",
                        "pattern" to CURRENCY_PATTERN
                    ),
                    "textFormat" to mapOf("fontSize" to 10, "bold" to true)
                )
            )
        )

        // 14. Charts: Clustered Column Chart (A7, 520x266), Doughnut Chart (K7, 491x266), Insights Chart (A65, 520x266)
        requests.addAll(
            ChartManager.buildChartRequests(
                dashboardSheetId = dashboardSheetId,
                columnChartColorPrimary = hexToColorMap("1465BF"),
                columnChartColorSecondary = hexToColorMap("90CAF8"),
                categoryCount = categoryCount,
                insightsItemCount = insightsCount
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
        // Row 1 Title Banner: A1:AC1 (cols 0 to 29, row 0 to 1)
        val row1BannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 0,
            "endRowIndex" to 1,
            "startColumnIndex" to 0,
            "endColumnIndex" to 29
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
        // Table 1 Debits: A2:L2 (cols 0 to 12, row 1 to 2)
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

        // Table 2 Inflows: N2:U2 (cols 13 to 21, row 1 to 2)
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

        // Table 3 Payables: W2:AC2 (cols 22 to 29, row 1 to 2)
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
        val bannerTitle = monthTabBannerTitle(tabTitle)
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "startRowIndex" to 0,
                        "endRowIndex" to 1,
                        "startColumnIndex" to 0,
                        "endColumnIndex" to 29
                    ),
                    "cell" to mapOf(
                        "userEnteredValue" to mapOf("stringValue" to bannerTitle),
                        "userEnteredFormat" to mapOf(
                            "backgroundColor" to ledgerBannerBg,
                            "horizontalAlignment" to "CENTER",
                            "verticalAlignment" to "MIDDLE",
                            "textFormat" to mapOf(
                                "foregroundColor" to whiteColor,
                                "bold" to true,
                                "fontSize" to 14
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment)"
                )
            )
        )

        // 5. Populate Row 2 Section Headers
        val sectionDefs = listOf(
            Triple(0, 12, Pair("EXPENSES (MONEY OUT)", debitsHeaderBg)),
            Triple(12, 13, Pair("", spacerGrayBg)),
            Triple(13, 21, Pair("INFLOWS (MONEY IN)", inflowsHeaderBg)),
            Triple(21, 22, Pair("", spacerGrayBg)),
            Triple(22, 29, Pair("I OWE PEOPLE", owePeopleHeaderBg))
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
                                "textFormat" to buildMap {
                                    put("foregroundColor", whiteColor)
                                    put("bold", true)
                                    // Live: the group titles inherit the default size, the blank spacer cells are size 11
                                    if (title.isEmpty()) put("fontSize", 11)
                                }
                            )
                        ),
                        "fields" to "userEnteredValue,userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment)"
                    )
                )
            )
        }

        // Row 2 borders exactly as in the live workbook: A2 and L2 top, bottom and left; M2 bottom only;
        // U2 and V2 top and bottom. (The merged blocks carry their borders on the first cell.)
        ROW2_BORDER_SIDES.forEach { (col, sides) ->
            requests.add(cellBordersRequest(sheetId, row = 1, col = col, sides = sides))
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

        // Hide UUID columns AD, AE, AF (indices 29 to 32)
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
            0 to 90, 1 to 129, 2 to 80, 3 to 160, 4 to 100, 5 to 110, 6 to 100, 7 to 120, 8 to 90,
            9 to 90, 10 to 80, 11 to 150, 12 to 10, 13 to 90, 14 to 75, 15 to 90, 16 to 100, 17 to 120,
            18 to 125, 19 to 115, 20 to 115, 21 to 10, 22 to 95, 23 to 75, 24 to 95, 25 to 120,
            26 to 110, 27 to 95, 28 to 115
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
            format["verticalAlignment"] = "MIDDLE"
            // Thin borders on A3:Z3 only, as in the live workbook
            if (colIdx <= ROW3_LAST_BORDERED_COL) {
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

        // Reaffirm the A3:Z3 grid through one range request so the shared edges (for example K3 and S3) are written
        requests.add(
            mapOf(
                "updateBorders" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "startRowIndex" to 2,
                        "endRowIndex" to 3,
                        "startColumnIndex" to 0,
                        "endColumnIndex" to ROW3_LAST_BORDERED_COL + 1
                    ),
                    "top" to thinBorder(),
                    "bottom" to thinBorder(),
                    "left" to thinBorder(),
                    "right" to thinBorder(),
                    "innerVertical" to thinBorder()
                )
            )
        )

        // 7. Column Number Formatting on Data Rows (Rows 4 to targetRowCount)
        // Currency Columns: Total Amount (6), My Share (8), Owed To Me (9), Inflow Amount (19), Payable Amount (26)
        listOf(6, 8, 9, 19, 26).forEach { colIdx ->
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

        // Plain text format (@) on the debit table columns C, D, E, F, H, K and L, as in the iOS reference
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

        // Date Columns: Debit Date (0), Inflow Date (13), Payable Date (22), Repaid Date (27)
        listOf(0, 13, 22, 27).forEach { colIdx ->
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

        // Time Columns: Debit Time (1), Inflow Time (14), Payable Time (23)
        listOf(1, 14, 23).forEach { colIdx ->
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

    /**
     * Builds atomic batchUpdate requests to upgrade an existing 31-wide month tab
     * to the 32-column master schema with Expense Note at Column L (index 11).
     * Includes insertDimension at index 11, header L3 = "Note", column width 150px,
     * banner merge across A1:AC1, plain text format on Col L, and hiding AD..AF UUIDs.
     */
    fun buildRepairMonthTabSchemaRequests(
        sheetId: Int,
        tabTitle: String,
        rowCount: Int = 1000
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()

        // 1. Insert 1 column at index 11 (Column L)
        requests.add(
            mapOf(
                "insertDimension" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "dimension" to "COLUMNS",
                        "startIndex" to SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE,
                        "endIndex" to SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE + 1
                    ),
                    "inheritFromBefore" to true
                )
            )
        )

        // 2. Set Row 3 Header L3 = "Note"
        requests.add(
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf(
                        "sheetId" to sheetId,
                        "rowIndex" to 2,
                        "columnIndex" to SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE
                    ),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf(
                                    "userEnteredValue" to mapOf("stringValue" to "Note"),
                                    "userEnteredFormat" to mapOf(
                                        "backgroundColor" to headerSlateBg,
                                        "horizontalAlignment" to "CENTER",
                                        "verticalAlignment" to "MIDDLE",
                                        "wrapStrategy" to "WRAP",
                                        "textFormat" to mapOf(
                                            "foregroundColor" to whiteColor,
                                            "bold" to true,
                                            "fontSize" to 9
                                        ),
                                        "borders" to thinBorders()
                                    )
                                )
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment,wrapStrategy,borders)"
                )
            )
        )

        // 3. Set Column L width to 150 px
        requests.add(
            createUpdateDimensionPropertiesRequest(
                sheetId = sheetId,
                dimension = "COLUMNS",
                startIndex = SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE,
                endIndex = SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE + 1,
                pixelSize = 150
            )
        )

        // 4. Update Row 1 Title Banner merge (Cols 0 to 29, A1:AC1) and banner title
        val bannerTitle = monthTabBannerTitle(tabTitle)
        val row1BannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 0,
            "endRowIndex" to 1,
            "startColumnIndex" to 0,
            "endColumnIndex" to 29
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to row1BannerRange)))
        requests.add(mapOf("mergeCells" to mapOf("range" to row1BannerRange, "mergeType" to "MERGE_ALL")))
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to row1BannerRange,
                    "cell" to mapOf(
                        "userEnteredValue" to mapOf("stringValue" to bannerTitle),
                        "userEnteredFormat" to mapOf(
                            "backgroundColor" to ledgerBannerBg,
                            "horizontalAlignment" to "CENTER",
                            "verticalAlignment" to "MIDDLE",
                            "textFormat" to mapOf(
                                "foregroundColor" to whiteColor,
                                "bold" to true,
                                "fontSize" to 14
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment)"
                )
            )
        )

        // 5. Hide UUID columns AD, AE, AF (indices 29 to 32)
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

        // 6. Plain text format (@) on Column L data cells (rows 4 to rowCount)
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "startRowIndex" to 3,
                        "endRowIndex" to rowCount,
                        "startColumnIndex" to SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE,
                        "endColumnIndex" to SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE + 1
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

    fun buildPaidThisMonthFormula(
        row1BasedIndex: Int,
        year: Int = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).year
    ): String {
        val yy = (year % 100).toString().padStart(2, '0')
        return """=IFERROR(SUMIFS(INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!G4:G"), INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!F4:F"), "Subscriptions", INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!D4:D"), "*" & A$row1BasedIndex & "*", INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!D4:D"), "<>[Void]*", INDIRECT("'" & TEXT(TODAY(), "mmm") & "$yy" & "'!D4:D"), "<>[Carried*"), 0)"""
    }

    fun buildPaidThisYearFormula(year: Int, row1BasedIndex: Int): String {
        return DashboardFormulaBuilder.buildPaidThisYearFormula(year, row1BasedIndex)
    }

    fun buildLastPaidDateFormula(year: Int, row1BasedIndex: Int): String {
        return DashboardFormulaBuilder.buildLastPaidDateFormula(year, row1BasedIndex)
    }

    fun buildSubscriptionRowValues(
        sub: Subscription,
        row1BasedIndex: Int,
        year: Int
    ): List<Any?> {
        val anchorDateSerial: Any = try {
            com.financeautomation.core.utils.DateSerialUtils.toDateSerial(
                java.time.LocalDate.ofEpochDay(sub.anchorEpochDay)
            )
        } catch (_: Exception) {
            ""
        }
        val freqText = sub.frequency.name.lowercase().replaceFirstChar { it.uppercase() }
        val activeBool = sub.active
        val perMonthFormula = DashboardFormulaBuilder.buildSubscriptionPerMonthFormula(row1BasedIndex)
        val paidMonthFormula = buildPaidThisMonthFormula(row1BasedIndex, year)
        val paidYearFormula = buildPaidThisYearFormula(year, row1BasedIndex)
        val lastPaidDateFormula = buildLastPaidDateFormula(year, row1BasedIndex)
        val closedDateSerial: Any = if (sub.active || sub.closedDateEpochDay == null) {
            ""
        } else {
            try {
                com.financeautomation.core.utils.DateSerialUtils.toDateSerial(
                    java.time.LocalDate.ofEpochDay(sub.closedDateEpochDay)
                )
            } catch (_: Exception) {
                ""
            }
        }
        val paidInTotalFormula = DashboardFormulaBuilder.buildPaidThisYearFormula(year, row1BasedIndex)

        return listOf(
            sub.name,
            sub.amount ?: "",
            freqText,
            anchorDateSerial,
            sub.account,
            activeBool,
            sub.note,
            sub.keywords,
            perMonthFormula,
            paidMonthFormula,
            paidYearFormula,
            lastPaidDateFormula,
            closedDateSerial,
            paidInTotalFormula,
            sub.id
        )
    }

    fun buildSubscriptionsRequests(
        sheetId: Int,
        accounts: List<String> = emptyList(),
        rowCount: Int = 500,
        columnCount: Int = SheetSchemaConstants.SUBSCRIPTIONS_COLUMN_COUNT,
        year: Int
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()
        val targetRows = maxOf(rowCount, 500)
        val targetCols = maxOf(columnCount, SheetSchemaConstants.SUBSCRIPTIONS_COLUMN_COUNT)

        // Ensure grid size
        requests.add(
            mapOf(
                "updateSheetProperties" to mapOf(
                    "properties" to mapOf(
                        "sheetId" to sheetId,
                        "gridProperties" to mapOf(
                            "rowCount" to targetRows,
                            "columnCount" to targetCols,
                            "frozenRowCount" to 3
                        )
                    ),
                    "fields" to "gridProperties(rowCount,columnCount,frozenRowCount)"
                )
            )
        )

        // Banner merge A1:N1 (Columns 0 to 14, excluding hidden Col O)
        val bannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 0,
            "endRowIndex" to 1,
            "startColumnIndex" to 0,
            "endColumnIndex" to 14
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to bannerRange)))
        requests.add(mapOf("mergeCells" to mapOf("range" to bannerRange, "mergeType" to "MERGE_ALL")))
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to bannerRange,
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
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf(
                        "sheetId" to sheetId,
                        "rowIndex" to 0,
                        "columnIndex" to 0
                    ),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf(
                                    "userEnteredValue" to mapOf("stringValue" to "RECURRING EXPENSES & SUBSCRIPTIONS"),
                                    "userEnteredFormat" to mapOf(
                                        "backgroundColor" to darkNavyBg,
                                        "textFormat" to mapOf(
                                            "fontSize" to 14,
                                            "bold" to true,
                                            "foregroundColor" to whiteColor
                                        ),
                                        "horizontalAlignment" to "CENTER",
                                        "verticalAlignment" to "MIDDLE"
                                    )
                                )
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat"
                )
            )
        )

        // Row 2 Subtitle Note Banner A2:N2
        val subBannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 1,
            "endRowIndex" to 2,
            "startColumnIndex" to 0,
            "endColumnIndex" to 14
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to subBannerRange)))
        requests.add(mapOf("mergeCells" to mapOf("range" to subBannerRange, "mergeType" to "MERGE_ALL")))
        requests.add(
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf("sheetId" to sheetId, "rowIndex" to 1, "columnIndex" to 0),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf(
                                    "userEnteredValue" to mapOf("stringValue" to "* Paid in total covers this workbook's year only. Recurring charges are recorded per month tab."),
                                    "userEnteredFormat" to mapOf(
                                        "textFormat" to mapOf("fontSize" to 9, "italic" to true, "foregroundColor" to grayTextColor),
                                        "horizontalAlignment" to "CENTER",
                                        "verticalAlignment" to "MIDDLE"
                                    )
                                )
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat"
                )
            )
        )

        // Column headers Row 3 (index 2)
        val headers = listOf(
            "Name", "Expected Amount (₹)", "Frequency", "Due Date",
            "Default Account", "Active", "Note", "Keywords",
            "Per Month (₹)", "Paid This Month (₹)", "Paid This Year (₹)", "Last Paid Date",
            "Closed Date", "Paid in Total (₹)", "Subscription ID"
        )
        val row3Values = headers.map { header ->
            mapOf(
                "userEnteredValue" to mapOf("stringValue" to header),
                "userEnteredFormat" to mapOf(
                    "backgroundColor" to headerSlateBg,
                    "textFormat" to mapOf("fontSize" to 9, "bold" to true, "foregroundColor" to whiteColor),
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "wrapStrategy" to "WRAP",
                    "borders" to thinBorders()
                )
            )
        }
        requests.add(
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf("sheetId" to sheetId, "rowIndex" to 2, "columnIndex" to 0),
                    "rows" to listOf(mapOf("values" to row3Values)),
                    "fields" to "userEnteredValue,userEnteredFormat"
                )
            )
        )

        // Column widths
        val widths = listOf(
            0 to 160, 1 to 130, 2 to 100, 3 to 110, 4 to 120,
            5 to 80, 6 to 150, 7 to 150, 8 to 120, 9 to 140, 10 to 140, 11 to 120,
            12 to 110, 13 to 140, 14 to 130
        )
        widths.forEach { (colIdx, px) ->
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

        // Number Formats
        // Expected Amount: Col B (1) -> CURRENCY
        requests.add(createNumberFormatRequest(sheetId, 1, 2, "CURRENCY", "₹#,##0", "RIGHT", targetRows))
        // Due Date: Col D (3) -> DATE
        requests.add(createNumberFormatRequest(sheetId, 3, 4, "DATE", "yyyy-mm-dd", "CENTER", targetRows))
        // Plain text on Note and Keywords: Col G, H (6 to 8)
        requests.add(createNumberFormatRequest(sheetId, 6, 8, "TEXT", "@", "LEFT", targetRows))
        // Per Month: Col I (8) -> CURRENCY
        requests.add(createNumberFormatRequest(sheetId, 8, 9, "CURRENCY", "₹#,##0", "RIGHT", targetRows))
        // Paid This Month: Col J (9) -> CURRENCY
        requests.add(createNumberFormatRequest(sheetId, 9, 10, "CURRENCY", "₹#,##0", "RIGHT", targetRows))
        // Paid This Year: Col K (10) -> CURRENCY
        requests.add(createNumberFormatRequest(sheetId, 10, 11, "CURRENCY", "₹#,##0", "RIGHT", targetRows))
        // Last Paid Date: Col L (11) -> DATE with blank on zero
        requests.add(createNumberFormatRequest(sheetId, 11, 12, "DATE", "yyyy-mm-dd;;", "CENTER", targetRows))
        // Closed Date: Col M (12) -> DATE with blank on zero
        requests.add(createNumberFormatRequest(sheetId, 12, 13, "DATE", "yyyy-mm-dd;;", "CENTER", targetRows))
        // Paid in Total: Col N (13) -> CURRENCY
        requests.add(createNumberFormatRequest(sheetId, 13, 14, "CURRENCY", "₹#,##0", "RIGHT", targetRows))

        // Hide Subscription ID (Col O, index 14)
        requests.add(
            mapOf(
                "updateDimensionProperties" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "dimension" to "COLUMNS",
                        "startIndex" to SheetSchemaConstants.COL_INDEX_SUB_ID,
                        "endIndex" to SheetSchemaConstants.COL_INDEX_SUB_ID + 1
                    ),
                    "properties" to mapOf("hiddenByUser" to true),
                    "fields" to "hiddenByUser"
                )
            )
        )

        return requests
    }

    fun buildTripRowValues(
        trip: com.financeautomation.domain.model.TripTag,
        repaidAmount: Double = 0.0,
        topCategory: String = "",
        row1BasedIndex: Int,
        year: Int
    ): List<Any?> {
        val startDateSerial: Any = trip.startDateEpochDay?.let {
            try {
                com.financeautomation.core.utils.DateSerialUtils.toDateSerial(
                    java.time.LocalDate.ofEpochDay(it)
                )
            } catch (_: Exception) {
                ""
            }
        } ?: ""
        val endDateSerial: Any = trip.endDateEpochDay?.let {
            try {
                com.financeautomation.core.utils.DateSerialUtils.toDateSerial(
                    java.time.LocalDate.ofEpochDay(it)
                )
            } catch (_: Exception) {
                ""
            }
        } ?: ""
        val statusText = if (trip.isActive) "Active" else "Ended"
        val daysFormula = DashboardFormulaBuilder.buildTripDaysFormula(row1BasedIndex)
        val totalPaidFormula = DashboardFormulaBuilder.buildTripTotalPaidFormula(year, row1BasedIndex)
        val myShareFormula = DashboardFormulaBuilder.buildTripMyShareFormula(year, row1BasedIndex)
        val owedToMeFormula = DashboardFormulaBuilder.buildTripOwedToMeFormula(year, row1BasedIndex)

        return listOf(
            trip.name,
            startDateSerial,
            endDateSerial,
            statusText,
            daysFormula,
            totalPaidFormula,
            myShareFormula,
            owedToMeFormula,
            repaidAmount,
            topCategory,
            trip.id
        )
    }

    fun buildTripsRequests(
        sheetId: Int,
        rowCount: Int = 200,
        columnCount: Int = SheetSchemaConstants.TRIPS_COLUMN_COUNT,
        year: Int
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()
        val targetRows = maxOf(rowCount, 200)
        val targetCols = maxOf(columnCount, SheetSchemaConstants.TRIPS_COLUMN_COUNT)

        // Ensure grid size
        requests.add(
            mapOf(
                "updateSheetProperties" to mapOf(
                    "properties" to mapOf(
                        "sheetId" to sheetId,
                        "gridProperties" to mapOf(
                            "rowCount" to targetRows,
                            "columnCount" to targetCols,
                            "frozenRowCount" to 3
                        )
                    ),
                    "fields" to "gridProperties(rowCount,columnCount,frozenRowCount)"
                )
            )
        )

        // Banner merge A1:J1 (Columns 0 to 10, excluding hidden Col K)
        val bannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 0,
            "endRowIndex" to 1,
            "startColumnIndex" to 0,
            "endColumnIndex" to 10
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to bannerRange)))
        requests.add(mapOf("mergeCells" to mapOf("range" to bannerRange, "mergeType" to "MERGE_ALL")))
        requests.add(
            mapOf(
                "repeatCell" to mapOf(
                    "range" to bannerRange,
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
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf(
                        "sheetId" to sheetId,
                        "rowIndex" to 0,
                        "columnIndex" to 0
                    ),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf(
                                    "userEnteredValue" to mapOf("stringValue" to "TRIPS & EXPEDITIONS SUMMARY"),
                                    "userEnteredFormat" to mapOf(
                                        "backgroundColor" to darkNavyBg,
                                        "textFormat" to mapOf(
                                            "fontSize" to 14,
                                            "bold" to true,
                                            "foregroundColor" to whiteColor
                                        ),
                                        "horizontalAlignment" to "CENTER",
                                        "verticalAlignment" to "MIDDLE"
                                    )
                                )
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat"
                )
            )
        )

        // Row 2 Subtitle Note Banner A2:J2
        val tripSubBannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 1,
            "endRowIndex" to 2,
            "startColumnIndex" to 0,
            "endColumnIndex" to 10
        )
        requests.add(mapOf("unmergeCells" to mapOf("range" to tripSubBannerRange)))
        requests.add(mapOf("mergeCells" to mapOf("range" to tripSubBannerRange, "mergeType" to "MERGE_ALL")))
        requests.add(
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf("sheetId" to sheetId, "rowIndex" to 1, "columnIndex" to 0),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf(
                                    "userEnteredValue" to mapOf("stringValue" to "* Trip sums exclude Void, Savings, Transfer. The sheet formulas cannot apply dynamic category exclusions from Settings or rows still in review (Needs Review)."),
                                    "userEnteredFormat" to mapOf(
                                        "textFormat" to mapOf("fontSize" to 9, "italic" to true, "foregroundColor" to grayTextColor),
                                        "horizontalAlignment" to "CENTER",
                                        "verticalAlignment" to "MIDDLE"
                                    )
                                )
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat"
                )
            )
        )

        // Column headers Row 3 (index 2)
        val headers = listOf(
            "Trip Name", "Start Date", "End Date", "Status", "Days",
            "Total Paid (₹)", "My Share (₹)", "Owed To Me (₹)", "Repaid (₹)",
            "Top Category", "Trip ID"
        )
        val row3Values = headers.map { header ->
            mapOf(
                "userEnteredValue" to mapOf("stringValue" to header),
                "userEnteredFormat" to mapOf(
                    "backgroundColor" to headerSlateBg,
                    "textFormat" to mapOf("fontSize" to 9, "bold" to true, "foregroundColor" to whiteColor),
                    "horizontalAlignment" to "CENTER",
                    "verticalAlignment" to "MIDDLE",
                    "wrapStrategy" to "WRAP",
                    "borders" to thinBorders()
                )
            )
        }
        requests.add(
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf("sheetId" to sheetId, "rowIndex" to 2, "columnIndex" to 0),
                    "rows" to listOf(mapOf("values" to row3Values)),
                    "fields" to "userEnteredValue,userEnteredFormat"
                )
            )
        )

        // Column widths
        val widths = listOf(
            0 to 180, 1 to 100, 2 to 100, 3 to 90, 4 to 70,
            5 to 110, 6 to 110, 7 to 110, 8 to 110, 9 to 120, 10 to 100
        )
        widths.forEach { (colIdx, px) ->
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

        // Number Formats
        // Start Date: Col B (1) -> DATE
        requests.add(createNumberFormatRequest(sheetId, 1, 2, "DATE", "yyyy-mm-dd", "CENTER", targetRows))
        // End Date: Col C (2) -> DATE with blank on empty
        requests.add(createNumberFormatRequest(sheetId, 2, 3, "DATE", "yyyy-mm-dd;;", "CENTER", targetRows))
        // Days: Col E (4) -> NUMBER
        requests.add(createNumberFormatRequest(sheetId, 4, 5, "NUMBER", "0", "CENTER", targetRows))
        // Total Paid, My Share, Owed To Me, Repaid: Cols F..I (5..9) -> CURRENCY
        requests.add(createNumberFormatRequest(sheetId, 5, 9, "CURRENCY", "₹#,##0", "RIGHT", targetRows))
        // Top Category: Col J (9) -> TEXT
        requests.add(createNumberFormatRequest(sheetId, 9, 10, "TEXT", "@", "LEFT", targetRows))

        // Hide Trip ID (Col K, index 10)
        requests.add(
            mapOf(
                "updateDimensionProperties" to mapOf(
                    "range" to mapOf(
                        "sheetId" to sheetId,
                        "dimension" to "COLUMNS",
                        "startIndex" to SheetSchemaConstants.COL_INDEX_TRIP_ID,
                        "endIndex" to SheetSchemaConstants.COL_INDEX_TRIP_ID + 1
                    ),
                    "properties" to mapOf("hiddenByUser" to true),
                    "fields" to "hiddenByUser"
                )
            )
        )

        return requests
    }

    fun buildSubscriptionsTitleRepairRequests(sheetId: Int): List<Map<String, Any?>> {
        val bannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 0,
            "endRowIndex" to 1,
            "startColumnIndex" to 0,
            "endColumnIndex" to 14
        )
        return listOf(
            mapOf("unmergeCells" to mapOf("range" to bannerRange)),
            mapOf("mergeCells" to mapOf("range" to bannerRange, "mergeType" to "MERGE_ALL")),
            mapOf(
                "repeatCell" to mapOf(
                    "range" to bannerRange,
                    "cell" to mapOf(
                        "userEnteredFormat" to mapOf(
                            "backgroundColor" to darkNavyBg
                        )
                    ),
                    "fields" to "userEnteredFormat.backgroundColor"
                )
            ),
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf(
                        "sheetId" to sheetId,
                        "rowIndex" to 0,
                        "columnIndex" to 0
                    ),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf(
                                    "userEnteredValue" to mapOf("stringValue" to "RECURRING EXPENSES & SUBSCRIPTIONS"),
                                    "userEnteredFormat" to mapOf(
                                        "backgroundColor" to darkNavyBg,
                                        "textFormat" to mapOf(
                                            "fontSize" to 14,
                                            "bold" to true,
                                            "foregroundColor" to whiteColor
                                        ),
                                        "horizontalAlignment" to "CENTER",
                                        "verticalAlignment" to "MIDDLE"
                                    )
                                )
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat"
                )
            ),
            // Clear existing data validations on Subscriptions tab (Frequency, Default Account, Active)
            createClearDataValidationRequest(sheetId, startRow = 3, endRow = 500, startCol = 2, endCol = 3),
            createClearDataValidationRequest(sheetId, startRow = 3, endRow = 500, startCol = 4, endCol = 5),
            createClearDataValidationRequest(sheetId, startRow = 3, endRow = 500, startCol = 5, endCol = 6)
        )
    }

    fun buildTripsTitleRepairRequests(sheetId: Int): List<Map<String, Any?>> {
        val bannerRange = mapOf(
            "sheetId" to sheetId,
            "startRowIndex" to 0,
            "endRowIndex" to 1,
            "startColumnIndex" to 0,
            "endColumnIndex" to 10
        )
        return listOf(
            mapOf("unmergeCells" to mapOf("range" to bannerRange)),
            mapOf("mergeCells" to mapOf("range" to bannerRange, "mergeType" to "MERGE_ALL")),
            mapOf(
                "repeatCell" to mapOf(
                    "range" to bannerRange,
                    "cell" to mapOf(
                        "userEnteredFormat" to mapOf(
                            "backgroundColor" to darkNavyBg
                        )
                    ),
                    "fields" to "userEnteredFormat.backgroundColor"
                )
            ),
            mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf(
                        "sheetId" to sheetId,
                        "rowIndex" to 0,
                        "columnIndex" to 0
                    ),
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf(
                                    "userEnteredValue" to mapOf("stringValue" to "TRIPS & EXPEDITIONS SUMMARY"),
                                    "userEnteredFormat" to mapOf(
                                        "backgroundColor" to darkNavyBg,
                                        "textFormat" to mapOf(
                                            "fontSize" to 14,
                                            "bold" to true,
                                            "foregroundColor" to whiteColor
                                        ),
                                        "horizontalAlignment" to "CENTER",
                                        "verticalAlignment" to "MIDDLE"
                                    )
                                )
                            )
                        )
                    ),
                    "fields" to "userEnteredValue,userEnteredFormat"
                )
            ),
            // Clear existing data validation on Trips tab (Status)
            createClearDataValidationRequest(sheetId, startRow = 3, endRow = 500, startCol = 3, endCol = 4)
        )
    }

    fun createClearDataValidationRequest(
        sheetId: Int,
        startRow: Int,
        endRow: Int,
        startCol: Int,
        endCol: Int
    ): Map<String, Any?> = mapOf(
        "setDataValidation" to mapOf(
            "range" to mapOf(
                "sheetId" to sheetId,
                "startRowIndex" to startRow,
                "endRowIndex" to endRow,
                "startColumnIndex" to startCol,
                "endColumnIndex" to endCol
            ),
            "rule" to null
        )
    )
}
