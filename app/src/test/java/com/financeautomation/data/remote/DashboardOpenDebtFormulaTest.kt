package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open debt cells of the Dashboard (G5, J5, A27, H27): which formulas are written, and that provisioning and the
 * regenerate path write the same ones. These tests check the requests sent to Google; nothing here evaluates a
 * formula, so the computed result in a real sheet is not tested.
 */
class DashboardOpenDebtFormulaTest {

    private fun expected(year: Int): Map<Pair<Int, Int>, String> = mapOf(
        (4 to 6) to DashboardFormulaBuilder.buildOwedToMeKpiFormula(year),
        (4 to 9) to DashboardFormulaBuilder.buildIOweKpiFormula(year),
        (26 to 0) to DashboardFormulaBuilder.buildTheyOweMeQueryFormula(year),
        (26 to 7) to DashboardFormulaBuilder.buildIOwePeopleQueryFormula(year)
    )

    /** One row of a month tab: its status and the amount in the column the tile sums. */
    private data class Debt(val status: String, val amount: Double)

    private val receivableTerm = Regex(
        """IFERROR\(SUMIF\(INDIRECT\("'(\w+)'!K4:K"\), "Pending", INDIRECT\("'\1'!J4:J"\)\), 0\)"""
    )
    private val payableTerm = Regex(
        """IFERROR\(SUMIF\(INDIRECT\("'(\w+)'!AC4:AC"\), "Pending", INDIRECT\("'\1'!AA4:AA"\)\) \+ """ +
            """SUMIF\(INDIRECT\("'\1'!AC4:AC"\), "Partial", INDIRECT\("'\1'!AA4:AA"\)\), 0\)"""
    )

    /**
     * Computes a tile formula over [workbook]. Only the one shape the tiles use is understood, and the whole formula
     * must be made of it, so anything else fails the test. A tab that is not in the workbook contributes nothing,
     * which is what IFERROR does with it.
     */
    private fun evaluate(formula: String, term: Regex, workbook: Map<String, List<Debt>>, counted: Set<String>): Double {
        val terms = term.findAll(formula).toList()
        assertEquals("the formula must consist of known terms only", "=" + terms.joinToString(" + ") { it.value }, formula)
        return terms.sumOf { match ->
            workbook[match.groupValues[1]].orEmpty().filter { it.status in counted }.sumOf { it.amount }
        }
    }

    @Test
    fun owedToMeCountsDebtsStillInEarlierMonthsAndACarriedPairOnce() {
        val workbook = mapOf(
            "Sep26" to listOf(Debt("Pending", 100.0)),
            "Oct26" to listOf(Debt("Carried Fwd", 50.0), Debt("Pending", 20.0), Debt("Settled", 7.0)),
            "Nov26" to listOf(Debt("Pending", 50.0))
        )

        val total = evaluate(DashboardFormulaBuilder.buildOwedToMeKpiFormula(2026), receivableTerm, workbook, setOf("Pending"))

        assertEquals(170.0, total, 0.001)
    }

    @Test
    fun iOweCountsPendingAndPartialInEveryMonthTabOfTheWorkbook() {
        val workbook = mapOf(
            "Jan26" to listOf(Debt("Carried Fwd", 900.0)),
            "Mar26" to listOf(Debt("Partial", 40.0)),
            "Dec26" to listOf(Debt("Pending", 900.0))
        )

        val total = evaluate(
            DashboardFormulaBuilder.buildIOweKpiFormula(2026), payableTerm, workbook, setOf("Pending", "Partial")
        )

        assertEquals(940.0, total, 0.001)
    }

    @Test
    fun aNewYearsWorkbookReadsOnlyItsOwnMonthTabs() {
        val workbook = mapOf(
            "Dec26" to listOf(Debt("Pending", 500.0)),
            "Jan27" to listOf(Debt("Pending", 30.0))
        )

        val total = evaluate(DashboardFormulaBuilder.buildOwedToMeKpiFormula(2027), receivableTerm, workbook, setOf("Pending"))

        assertEquals(30.0, total, 0.001)
    }

    @Test
    fun theSettlementTablesStackEveryMonthWithItsOwnSameWidthFallbackRow() {
        listOf(
            Triple(DashboardFormulaBuilder.buildTheyOweMeQueryFormula(2026), "A4:L", 12),
            Triple(DashboardFormulaBuilder.buildIOwePeopleQueryFormula(2026), "W4:AC", 7)
        ).forEach { (formula, columns, width) ->
            val blocks = Regex("""IFERROR\(INDIRECT\("'(\w+)'!$columns"\), \{(.*?)\}\)""").findAll(formula).toList()
            assertEquals(DashboardFormulaBuilder.monthTabsForYear(2026), blocks.map { it.groupValues[1] })
            blocks.forEach { block ->
                val values = block.groupValues[2].replace(Regex("""DATE\([^)]*\)"""), "DATE").split(", ")
                assertEquals("a fallback row of another width breaks the whole stack", width, values.size)
            }
            assertTrue(formula.startsWith("=ARRAYFORMULA(IFERROR(QUERY(QUERY({"))
            assertTrue("a single cell formula must stay far below the 50000 character limit", formula.length < 10000)
        }
    }

    @Test
    fun provisioningWritesTheSharedOpenDebtFormulas() {
        val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 4, year = 2026)
        val written = mutableMapOf<Pair<Int, Int>, String>()
        requests.forEach { request ->
            val update = request["updateCells"] as? Map<*, *> ?: return@forEach
            val range = (update["range"] ?: update["start"]) as? Map<*, *> ?: return@forEach
            val row = (range["startRowIndex"] ?: range["rowIndex"]) as? Int ?: return@forEach
            val column = (range["startColumnIndex"] ?: range["columnIndex"]) as? Int ?: return@forEach
            val formula = firstFormula(update["rows"]) ?: return@forEach
            written[row to column] = formula
        }
        expected(2026).forEach { (cell, formula) -> assertEquals("cell $cell", formula, written[cell]) }
    }

    @Test
    fun regeneratingAnExistingWorkbookRewritesTheOpenDebtCellsForThatWorkbooksYear() = runBlocking {
        var posted: String? = null
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val body = if (request.method == "GET") {
                """{"sheets":[{"properties":{"sheetId":7,"title":"Dashboard"}},{"properties":{"sheetId":8,"title":"Nov25"}}]}"""
            } else {
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                posted = buffer.readUtf8()
                """{"replies":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val tokens = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }
        val gateway = DashboardRemoteGatewayImpl(GoogleSheetsClient(client, tokens), tokens, client, Dispatchers.Unconfined)

        val result = gateway.regenerateDashboard("test_sheet_id", emptyList())

        assertTrue("regenerate must succeed: $result", result is Result.Success)
        val written = mutableMapOf<Pair<Int, Int>, String>()
        Json.parseToJsonElement(posted!!).jsonObject["requests"]!!.jsonArray.forEach { element ->
            val update = element.jsonObject["updateCells"]?.jsonObject ?: return@forEach
            val range = update["range"]!!.jsonObject
            val formula = update["rows"]!!.jsonArray.first().jsonObject["values"]!!.jsonArray.first()
                .jsonObject["userEnteredValue"]?.jsonObject?.get("formulaValue")?.jsonPrimitive?.content ?: return@forEach
            written[range["startRowIndex"]!!.jsonPrimitive.int to range["startColumnIndex"]!!.jsonPrimitive.int] = formula
        }
        expected(2025).forEach { (cell, formula) -> assertEquals("cell $cell", formula, written[cell]) }
        // Monthly outflow trend (W6) belongs to the workbook's year too, not to today's
        assertEquals(DashboardFormulaBuilder.buildMonthlySummaryOutflowFormula(6, 2025), written[5 to 22])
    }

    private fun firstFormula(node: Any?): String? = when (node) {
        is Map<*, *> -> (node["formulaValue"] as? String) ?: node.values.firstNotNullOfOrNull { firstFormula(it) }
        is List<*> -> node.firstNotNullOfOrNull { firstFormula(it) }
        else -> null
    }
}
