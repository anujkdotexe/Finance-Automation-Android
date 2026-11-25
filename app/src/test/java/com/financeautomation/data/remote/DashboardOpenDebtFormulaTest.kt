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
import org.junit.Assert.assertFalse
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

    private val currentTab = """TEXT(TODAY(), "mmm") & "26""""
    private val nextTab = """TEXT(EDATE(TODAY(), 1), "mmm") & IF(MONTH(TODAY()) = 12, "27", "26")"""

    @Test
    fun owedToMeKpiAddsTheNextMonthTabWithItsOwnFallback() {
        val formula = DashboardFormulaBuilder.buildOwedToMeKpiFormula(2026)
        fun term(tab: String) =
            """IFERROR(SUMIF(INDIRECT("'" & $tab & "'!K4:K"), "Pending", INDIRECT("'" & $tab & "'!J4:J")), 0)"""
        assertEquals("=" + term(currentTab) + " + " + term(nextTab), formula)
    }

    @Test
    fun iOweKpiCountsPendingAndPartialInBothTabsWithSeparateFallbacks() {
        val formula = DashboardFormulaBuilder.buildIOweKpiFormula(2026)
        fun term(tab: String): String {
            val status = """INDIRECT("'" & $tab & "'!AC4:AC")"""
            val amount = """INDIRECT("'" & $tab & "'!AA4:AA")"""
            return """IFERROR(SUMIF($status, "Pending", $amount) + SUMIF($status, "Partial", $amount), 0)"""
        }
        assertEquals("=" + term(currentTab) + " + " + term(nextTab), formula)
    }

    @Test
    fun inDecemberNoOpenDebtFormulaReadsTheJanuaryTabOfTheSameYear() {
        expected(2026).values.forEach { formula ->
            assertTrue(formula.contains(nextTab))
            assertFalse(
                "an unconditional same year suffix would read the January that is already over",
                formula.contains("""TEXT(EDATE(TODAY(), 1), "mmm") & "26"""")
            )
        }
    }

    @Test
    fun theSettlementTablesFallBackToTheCurrentTabAlone() {
        listOf(
            DashboardFormulaBuilder.buildTheyOweMeQueryFormula(2026) to "A4:L",
            DashboardFormulaBuilder.buildIOwePeopleQueryFormula(2026) to "W4:AC"
        ).forEach { (formula, columns) ->
            assertTrue(formula.startsWith("""=IFERROR(QUERY(QUERY({INDIRECT("'" & $currentTab & "'!$columns"); """))
            assertTrue(formula.contains(""", IFERROR(QUERY(QUERY(INDIRECT("'" & $currentTab & "'!$columns"), """))
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
