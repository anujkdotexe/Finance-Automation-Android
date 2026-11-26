package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.RegenerateDashboardUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * An existing workbook gets the current Dashboard formulas once, on its own. Real resolver, use case, gateway and
 * Sheets client over a scripted server and a movable clock.
 */
class DashboardFormulaUpgradeTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private class MovableClock(var now: Instant, private val zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MovableClock(now, zone)
        override fun instant(): Instant = now
    }

    private val clock = MovableClock(Instant.parse("2026-11-20T06:00:00Z"), zone)
    private val posts = mutableListOf<String>()
    private var writeStatus = 200
    private val preferences = mutableMapOf<String, String?>(
        PreferencesRepository.keyLastWorkbookVerifiedDate(2026) to LocalDate.now(clock).toString()
    )

    private val resolver: WorkbookResolver = run {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            var code = 200
            val body = if (request.method == "GET") {
                """{"sheets":[{"properties":{"sheetId":7,"title":"Dashboard"}},{"properties":{"sheetId":8,"title":"Nov26"}}]}"""
            } else {
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                posts.add(buffer.readUtf8())
                code = writeStatus
                """{"replies":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("scripted")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val tokens = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }

        @Suppress("UNCHECKED_CAST")
        val prefs = Proxy.newProxyInstance(
            PreferencesRepository::class.java.classLoader, arrayOf(PreferencesRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getPreference" -> preferences[args!![0] as String]
                "setPreference" -> { preferences[args!![0] as String] = args[1] as String?; null }
                "getSpreadsheetIdForYearSync", "getSpreadsheetIdSync" -> "test_sheet_id"
                else -> null
            }
        } as PreferencesRepository

        val categories = Proxy.newProxyInstance(
            CategoryRepository::class.java.classLoader, arrayOf(CategoryRepository::class.java)
        ) { _, method, _ ->
            if (method.name == "getAllCategories") listOf(Category(id = "c1", name = "Groceries")) else null
        } as CategoryRepository

        val accounts = Proxy.newProxyInstance(
            AccountRepository::class.java.classLoader, arrayOf(AccountRepository::class.java)
        ) { _, _, _ -> null } as AccountRepository

        val sheets = GoogleSheetsClient(http, tokens)
        val gateway = DashboardRemoteGatewayImpl(sheets, tokens, http, Dispatchers.Unconfined)
        WorkbookResolver(
            googleDriveClient = GoogleDriveClient(http, tokens),
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            regenerateDashboardUseCase = RegenerateDashboardUseCase(gateway, prefs, accounts, categories, Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            clock = clock
        )
    }

    private val version = RegenerateDashboardUseCase.versionKey("test_sheet_id")

    private fun requestsOf(body: String): List<JsonObject> =
        Json.parseToJsonElement(body).jsonObject["requests"]!!.jsonArray.map { it.jsonObject }

    /** The formula or text each single cell write puts at (row, column), zero based. */
    private fun cellsOf(body: String): Map<Pair<Int, Int>, String> = requestsOf(body).mapNotNull { request ->
        val update = request["updateCells"]?.jsonObject ?: return@mapNotNull null
        val range = update["range"]!!.jsonObject
        val value = update["rows"]?.jsonArray?.firstOrNull()?.jsonObject?.get("values")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("userEnteredValue")?.jsonObject ?: return@mapNotNull null
        val text = (value["formulaValue"] ?: value["stringValue"])?.jsonPrimitive?.content ?: return@mapNotNull null
        (range["startRowIndex"]!!.jsonPrimitive.int to range["startColumnIndex"]!!.jsonPrimitive.int) to text
    }.toMap()

    @Test
    fun anExistingWorkbookIsUpgradedOnceWithoutTouchingAnyLedgerTab() = runBlocking {
        assertTrue(resolver.resolveForYear(2026) is Result.Success)

        assertEquals(1, posts.size)
        assertTrue(
            "only cell updates on the Dashboard are sent",
            requestsOf(posts.single()).all {
                it.keys == setOf("updateCells") &&
                    it["updateCells"]!!.jsonObject["range"]!!.jsonObject["sheetId"]!!.jsonPrimitive.int == 7
            }
        )
        val cells = cellsOf(posts.single())
        assertEquals(DashboardFormulaBuilder.buildOwedToMeKpiFormula(2026), cells[4 to 6])
        assertEquals(DashboardFormulaBuilder.buildTheyOweMeQueryFormula(2026), cells[26 to 0])
        assertEquals("Groceries", cells[64 to 20])
        assertEquals(DashboardFormulaBuilder.buildInsightCategorySpendFormula(65, 2026), cells[64 to 21])
        assertEquals("the old header at L26 is cleared", "", cells[25 to 11])
        assertEquals(RegenerateDashboardUseCase.FORMULA_VERSION.toString(), preferences[version])

        clock.now = clock.now.plusMillis(WorkbookResolver.FORMULA_UPGRADE_RETRY_MS + 1)
        resolver.resolveForYear(2026)
        assertEquals("an upgraded workbook is not written again", 1, posts.size)
    }

    @Test
    fun aWorkbookWhoseConfigWasNeverPulledIsNotUpgraded() = runBlocking {
        val pending = PreferencesRepository.keyConfigPullPending("test_sheet_id")
        preferences[pending] = "1"

        assertTrue(resolver.resolveForYear(2026) is Result.Success)

        assertTrue("this device's own categories are not written", posts.isEmpty())
        assertNull(preferences[version])

        preferences[pending] = null
        resolver.resolveForYear(2026)
        assertEquals(1, posts.size)
    }

    @Test
    fun aFailedUpgradeRecordsNothingAndIsTriedAgainAfterAPause() = runBlocking {
        writeStatus = 500

        assertTrue("a failed upgrade does not fail the resolve", resolver.resolveForYear(2026) is Result.Success)
        assertNull(preferences[version])
        val attempts = posts.size

        writeStatus = 200
        resolver.resolveForYear(2026)
        assertEquals("not retried at once", attempts, posts.size)

        clock.now = clock.now.plusMillis(WorkbookResolver.FORMULA_UPGRADE_RETRY_MS + 1)
        resolver.resolveForYear(2026)
        assertEquals(attempts + 1, posts.size)
        assertEquals(RegenerateDashboardUseCase.FORMULA_VERSION.toString(), preferences[version])
    }
}
