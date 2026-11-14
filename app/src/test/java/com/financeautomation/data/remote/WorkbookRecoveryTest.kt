package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime

/** A stored workbook the signed in account cannot open is replaced by a search, not reported as an error. */
class WorkbookRecoveryTest {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val tokens = object : TokenProvider {
        override fun getAccessToken(): String = "t"
        override fun refreshAccessToken(): String = "t"
    }
    private val year = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
    private val yearIds = mutableMapOf<Int, String>()

    private val prefs: PreferencesRepository = Proxy.newProxyInstance(
        PreferencesRepository::class.java.classLoader, arrayOf(PreferencesRepository::class.java)
    ) { _, m, args ->
        when (m.name) {
            "getSpreadsheetIdForYearSync" -> yearIds[args!![0] as Int]
            "setSpreadsheetIdForYear" -> { yearIds[args!![0] as Int] = args[1] as String; null }
            else -> if (kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(m.returnType)) flowOf<Any?>(null) else null
        }
    } as PreferencesRepository

    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody(json)).build()

    private fun resolver(blockedCode: Int, calls: MutableList<String>): WorkbookResolver {
        val drive = GoogleDriveClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "drive ${r.method} ${r.url.encodedPath}"
                if (r.method == "GET" && r.url.encodedPath.endsWith("/files")) {
                    response(r, 200, """{"files":[{"id":"new_id","name":"Finance$year"}]}""")
                } else response(r, 200, "{}")
            }).build(), tokens
        )
        val sheets = GoogleSheetsClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val r = chain.request()
                calls += "sheets ${r.method} ${r.url.encodedPath}"
                when {
                    r.url.encodedPath.contains("old_id") ->
                        response(r, blockedCode, """{"error":{"code":$blockedCode,"message":"The caller does not have permission","errors":[{"reason":"forbidden"}],"status":"X"}}""")
                    r.method == "GET" -> response(r, 200, """{"spreadsheetId":"new_id","sheets":[{"properties":{"sheetId":0,"title":"Dashboard","gridProperties":{"rowCount":1000,"columnCount":35}}},{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":1000,"columnCount":35}}}]}""")
                    else -> response(r, 200, """{"replies":[{"addSheet":{"properties":{"sheetId":7,"title":"Tab"}}}],"totalUpdatedCells":0}""")
                }
            }).build(), tokens
        )
        return WorkbookResolver(drive, sheets, prefs)
    }

    @Test
    fun aForbiddenStoredWorkbookIsReplacedByTheOneDriveFinds() = runBlocking {
        yearIds[year] = "old_id"
        val calls = mutableListOf<String>()
        val res = resolver(403, calls).resolveForYear(year, forceValidateTabs = true)
        assertTrue("expected success but was $res", res is Result.Success)
        assertEquals("new_id", (res as Result.Success).data)
        assertEquals("new_id", yearIds[year])
        assertTrue("Drive was searched", calls.any { it.startsWith("drive GET") && it.endsWith("/files") })
    }

    @Test
    fun aMissingStoredWorkbookIsReplacedToo() = runBlocking {
        yearIds[year] = "old_id"
        val res = resolver(404, mutableListOf()).resolveForYear(year, forceValidateTabs = true)
        assertEquals(Result.Success("new_id"), res)
    }
}
