package com.financeautomation.testutil

import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A small scripted Google Sheets server. By default every call succeeds with an empty sheet that has the current
 * month tab. [fail] can turn any individual request into an HTTP error, so a test can say "the second uuid lookup
 * returns 429" and see how the repository reacts. Unlike an always-successful fake this lets a test prove the error
 * handling.
 */
class ScriptedSheetsServer(
    private val tabs: List<String> = listOf(currentTab())
) {
    /** One recorded request. */
    data class Call(val method: String, val url: String, val body: String)

    val calls = CopyOnWriteArrayList<Call>()

    /** Return an HTTP status and body to fail this request, or null to let it succeed. */
    var fail: (Call, Int) -> Pair<Int, String>? = { _, _ -> null }

    /** Body of the next AC column read (a uuid lookup), by default an empty column. */
    var uuidColumnValues: (Call) -> List<String> = { emptyList() }

    /** Optional override for any GET values read; return a JSON body or null for the default. */
    var customRead: (Call) -> String? = { null }

    private val json = "application/json; charset=utf-8".toMediaType()
    private var counter = 0
    private var appendedRow = 3

    private val tokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "test_token"
        override fun refreshAccessToken(): String = "test_token"
    }

    fun client(): GoogleSheetsClient {
        val interceptor = Interceptor { chain -> respond(chain.request()) }
        return GoogleSheetsClient(OkHttpClient.Builder().addInterceptor(interceptor).build(), tokenProvider)
    }

    private fun bodyOf(request: Request): String {
        val b = request.body ?: return ""
        val buf = okio.Buffer()
        b.writeTo(buf)
        return buf.readUtf8()
    }

    private fun respond(request: Request): Response {
        val call = Call(request.method, request.url.toString(), bodyOf(request))
        calls.add(call)
        val index = counter++
        val failure = fail(call, index)
        val (code, body) = failure ?: (200 to successBody(call))
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody(json))
            .build()
    }

    private fun successBody(call: Call): String {
        val url = call.url
        val method = call.method
        customRead(call)?.let { if (method == "GET") return it }
        return when {
            method == "GET" && url.contains("values:batchGet") -> {
                val tab = tabs.first()
                """{"valueRanges":[
                    {"range":"'$tab'!A4:K","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AC4:AC","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!M4:T","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AD4:AD","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!V4:AB","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AE4:AE","majorDimension":"ROWS","values":[]}
                ]}"""
            }
            method == "GET" && url.contains("/values/") && (url.contains("AC") || url.contains("AD") || url.contains("AE")) -> {
                val values = uuidColumnValues(call).joinToString(",") { "[\"$it\"]" }
                """{"range":"'Sheet'!AC3:AC","majorDimension":"ROWS","values":[$values]}"""
            }
            method == "GET" && url.contains("/values/") -> """{"range":"'Sheet'!A3:K","majorDimension":"ROWS","values":[]}"""
            method == "GET" -> {
                val sheets = tabs.mapIndexed { i, t ->
                    """{"properties":{"sheetId":$i,"title":"$t","gridProperties":{"rowCount":1000,"columnCount":35}}}"""
                }.joinToString(",")
                """{"spreadsheetId":"test_sheet_id","sheets":[$sheets]}"""
            }
            (method == "POST" && url.contains(":append")) ||
                (method == "PUT" && url.contains("/values/") && (url.contains(":K") || url.contains("%3AK"))) -> {
                appendedRow++
                """{"spreadsheetId":"test_sheet_id","updatedRange":"'Sheet'!A$appendedRow:K$appendedRow","updatedCells":11}"""
            }
            method == "PUT" -> """{"updatedCells":1}"""
            method == "POST" && url.contains("values:batchUpdate") -> """{"totalUpdatedCells":12,"responses":[]}"""
            method == "POST" && url.contains("batchUpdate") -> """{"totalUpdatedCells":0,"replies":[]}"""
            else -> "{}"
        }
    }

    companion object {
        fun currentTab(): String = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("MMMyy", Locale.US))
    }
}
