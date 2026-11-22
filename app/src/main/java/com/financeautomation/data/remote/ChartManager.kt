package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.safeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Manages Google Sheets embedded charts and workbook properties:
 * - Setting en_IN locale and Asia/Kolkata timezone to ensure correct formulas.
 * - Deduplicating embedded charts before addChart runs.
 * - Generating chart specification payloads for Dashboard.
 */
object ChartManager {

    private val json = Json { ignoreUnknownKeys = true }

    const val COLUMN_CHART_TITLE = "Spending by Month: Personal Burn and Total Outflow"
    const val DOUGHNUT_CHART_TITLE = "Current Month Spend by Category"
    const val INSIGHTS_CHART_TITLE = "Current Month Spend (trips shown as one slice)"

    /** Titles used by earlier builds; charts with these titles are removed before the new ones are added. */
    val LEGACY_CHART_TITLES: Set<String> = setOf(
        "Full Year Spending by Month (Personal Burn vs Total Outflow)",
        "Current Month Spend (Categories & Trips)"
    )

    /** Minimum number of category rows covered by the category doughnut range. */
    const val MIN_CATEGORY_CHART_ROWS = 20

    private const val TITLE_TEXT_COLOR = 0x20 / 255f

    /** Dark, bold, readable chart title format (matches the provisioner dark text colour 202020). */
    private fun titleTextFormat(): Map<String, Any?> = mapOf(
        "foregroundColor" to mapOf("red" to TITLE_TEXT_COLOR, "green" to TITLE_TEXT_COLOR, "blue" to TITLE_TEXT_COLOR),
        "bold" to true,
        "fontSize" to 12
    )

    /**
     * Builds updateSpreadsheetProperties request to enforce en_GB locale and Asia/Kolkata timezone.
     * Prevents TEXT(TODAY(), "mmm") from returning non-English month strings on non-English Google accounts.
     */
    fun buildLocaleAndTimeZoneRequest(
        locale: String = "en_GB",
        timeZone: String = "Asia/Kolkata"
    ): Map<String, Any?> {
        return mapOf(
            "updateSpreadsheetProperties" to mapOf(
                "properties" to mapOf(
                    "locale" to locale,
                    "timeZone" to timeZone
                ),
                "fields" to "locale,timeZone"
            )
        )
    }

    /**
     * Queries Google Sheets API for existing embedded charts on [dashboardSheetId].
     * Returns a map of chart title to chartId.
     */
    suspend fun getExistingChartsOnDashboard(
        spreadsheetId: String,
        dashboardSheetId: Int,
        okHttpClient: OkHttpClient,
        baseUrl: String,
        tokenProvider: TokenProvider?
    ): Result<Map<String, Int>> = withContext(Dispatchers.IO) {
        safeCall {
            val url = "$baseUrl/$spreadsheetId?fields=sheets(properties.sheetId%2Ccharts(chartId%2Cspec.title))"
            val requestBuilder = Request.Builder().url(url)
            tokenProvider?.getAccessToken()?.let { token ->
                requestBuilder.header("Authorization", "Bearer $token")
            }

            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw createHttpException(response.code, bodyString, "getExistingCharts failed", spreadsheetId, GoogleEndpoint.SHEETS_GET)
                }

                val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
                val sheetsArray = jsonResponse["sheets"]?.jsonArray.orEmpty()
                val chartsMap = mutableMapOf<String, Int>()

                for (sheetElem in sheetsArray) {
                    val sheetObj = sheetElem.jsonObject
                    val sheetId = sheetObj["properties"]?.jsonObject?.get("sheetId")?.jsonPrimitive?.content?.toIntOrNull()
                    if (sheetId == dashboardSheetId) {
                        val chartsArray = sheetObj["charts"]?.jsonArray.orEmpty()
                        for (chartElem in chartsArray) {
                            val chartObj = chartElem.jsonObject
                            val chartId = chartObj["chartId"]?.jsonPrimitive?.content?.toIntOrNull()
                            val title = chartObj["spec"]?.jsonObject?.get("title")?.jsonPrimitive?.content.orEmpty()
                            if (chartId != null && title.isNotBlank()) {
                                chartsMap[title] = chartId
                            }
                        }
                    }
                }
                chartsMap
            }
        }
    }

    /**
     * Builds deleteEmbeddedObject batchUpdate requests for existing charts matching target titles.
     */
    fun buildDeleteChartRequests(
        existingCharts: Map<String, Int>,
        titlesToDelete: Set<String>
    ): List<Map<String, Any?>> {
        val requests = mutableListOf<Map<String, Any?>>()
        for (title in titlesToDelete) {
            val chartId = existingCharts[title]
            if (chartId != null) {
                requests.add(
                    mapOf(
                        "deleteEmbeddedObject" to mapOf(
                            "objectId" to chartId
                        )
                    )
                )
            }
        }
        return requests
    }

    /**
     * Builds addChart batchUpdate requests for the Clustered Column Chart, Doughnut Chart, and Insights Chart.
     * The Doughnut Chart dynamically scales its row range to match [categoryCount] (minimum 20 rows).
     * The Insights Chart shows Categories & Trips breakdown anchored at A65.
     */
    fun buildChartRequests(
        dashboardSheetId: Int,
        columnChartColorPrimary: Map<String, Float>,
        columnChartColorSecondary: Map<String, Float>,
        categoryCount: Int = 10,
        insightsItemCount: Int = 11
    ): List<Map<String, Any?>> {
        val categoryEndRow = 5 + maxOf(MIN_CATEGORY_CHART_ROWS, categoryCount)
        val insightsEndRow = 64 + maxOf(10, insightsItemCount)
        return listOf(
            // 1. Clustered Column Chart (A7, 520x266)
            mapOf(
                "addChart" to mapOf(
                    "chart" to mapOf(
                        "spec" to mapOf(
                            "title" to COLUMN_CHART_TITLE,
                            "titleTextFormat" to titleTextFormat(),
                            "basicChart" to mapOf(
                                "chartType" to "COLUMN",
                                "legendPosition" to "BOTTOM_LEGEND",
                                "axis" to listOf(
                                    mapOf("position" to "BOTTOM_AXIS", "title" to "Month"),
                                    mapOf("position" to "LEFT_AXIS", "title" to "Rupees")
                                ),
                                "domains" to listOf(
                                    mapOf(
                                        "domain" to mapOf(
                                            "sourceRange" to mapOf(
                                                "sources" to listOf(
                                                    mapOf(
                                                        "sheetId" to dashboardSheetId,
                                                        "startRowIndex" to 4,
                                                        "endRowIndex" to 17,
                                                        "startColumnIndex" to 20,
                                                        "endColumnIndex" to 21
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "series" to listOf(
                                    mapOf(
                                        "series" to mapOf(
                                            "sourceRange" to mapOf(
                                                "sources" to listOf(
                                                    mapOf(
                                                        "sheetId" to dashboardSheetId,
                                                        "startRowIndex" to 4,
                                                        "endRowIndex" to 17,
                                                        "startColumnIndex" to 21,
                                                        "endColumnIndex" to 22
                                                    )
                                                )
                                            )
                                        ),
                                        "targetAxis" to "LEFT_AXIS",
                                        "color" to columnChartColorPrimary
                                    ),
                                    mapOf(
                                        "series" to mapOf(
                                            "sourceRange" to mapOf(
                                                "sources" to listOf(
                                                    mapOf(
                                                        "sheetId" to dashboardSheetId,
                                                        "startRowIndex" to 4,
                                                        "endRowIndex" to 17,
                                                        "startColumnIndex" to 22,
                                                        "endColumnIndex" to 23
                                                    )
                                                )
                                            )
                                        ),
                                        "targetAxis" to "LEFT_AXIS",
                                        "color" to columnChartColorSecondary
                                    )
                                ),
                                "headerCount" to 1
                            )
                        ),
                        "position" to mapOf(
                            "overlayPosition" to mapOf(
                                "anchorCell" to mapOf(
                                    "sheetId" to dashboardSheetId,
                                    "rowIndex" to 6,
                                    "columnIndex" to 0
                                ),
                                "widthPixels" to 520,
                                "heightPixels" to 266
                            )
                        )
                    )
                )
            ),
            // 2. Doughnut Chart (K7, 491x266)
            mapOf(
                "addChart" to mapOf(
                    "chart" to mapOf(
                        "spec" to mapOf(
                            "title" to DOUGHNUT_CHART_TITLE,
                            "titleTextFormat" to titleTextFormat(),
                            "pieChart" to mapOf(
                                "legendPosition" to "LABELED_LEGEND",
                                "pieHole" to 0.5,
                                "domain" to mapOf(
                                    "sourceRange" to mapOf(
                                        "sources" to listOf(
                                            mapOf(
                                                "sheetId" to dashboardSheetId,
                                                "startRowIndex" to 5,
                                                "endRowIndex" to categoryEndRow,
                                                "startColumnIndex" to 24,
                                                "endColumnIndex" to 25
                                            )
                                        )
                                    )
                                ),
                                "series" to mapOf(
                                    "sourceRange" to mapOf(
                                        "sources" to listOf(
                                            mapOf(
                                                "sheetId" to dashboardSheetId,
                                                "startRowIndex" to 5,
                                                "endRowIndex" to categoryEndRow,
                                                "startColumnIndex" to 25,
                                                "endColumnIndex" to 26
                                            )
                                        )
                                    )
                                )
                            )
                        ),
                        "position" to mapOf(
                            "overlayPosition" to mapOf(
                                "anchorCell" to mapOf(
                                    "sheetId" to dashboardSheetId,
                                    "rowIndex" to 6,
                                    "columnIndex" to 10
                                ),
                                "widthPixels" to 491,
                                "heightPixels" to 266
                            )
                        )
                    )
                )
            ),
            // 3. Insights Doughnut Chart (A65, 520x266) - Categories & Trips Single-Slice Rule
            mapOf(
                "addChart" to mapOf(
                    "chart" to mapOf(
                        "spec" to mapOf(
                            "title" to INSIGHTS_CHART_TITLE,
                            "titleTextFormat" to titleTextFormat(),
                            "pieChart" to mapOf(
                                "legendPosition" to "LABELED_LEGEND",
                                "pieHole" to 0.5,
                                "domain" to mapOf(
                                    "sourceRange" to mapOf(
                                        "sources" to listOf(
                                            mapOf(
                                                "sheetId" to dashboardSheetId,
                                                "startRowIndex" to 64,
                                                "endRowIndex" to insightsEndRow,
                                                "startColumnIndex" to 20,
                                                "endColumnIndex" to 21
                                            )
                                        )
                                    )
                                ),
                                "series" to mapOf(
                                    "sourceRange" to mapOf(
                                        "sources" to listOf(
                                            mapOf(
                                                "sheetId" to dashboardSheetId,
                                                "startRowIndex" to 64,
                                                "endRowIndex" to insightsEndRow,
                                                "startColumnIndex" to 21,
                                                "endColumnIndex" to 22
                                            )
                                        )
                                    )
                                )
                            )
                        ),
                        "position" to mapOf(
                            "overlayPosition" to mapOf(
                                "anchorCell" to mapOf(
                                    "sheetId" to dashboardSheetId,
                                    "rowIndex" to 64,
                                    "columnIndex" to 0
                                ),
                                "widthPixels" to 520,
                                "heightPixels" to 266
                            )
                        )
                    )
                )
            )
        )
    }
}

