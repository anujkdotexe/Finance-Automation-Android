package com.financeautomation.testutil

import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import java.net.URLDecoder

/**
 * An in-memory Google Sheets endpoint that holds one hidden Config tab. The real [GoogleSheetsClient] talks to it
 * through an OkHttp interceptor, so the production request building and response parsing are exercised.
 * [cells] is the tab: index 0 is row 1. Handles tab listing, values.get, values.update (PUT) and values:batchUpdate.
 */
class FakeConfigSheet(initialRows: List<List<String>> = defaultRows()) {

    val cells: MutableList<MutableList<String>> = initialRows.map { it.toMutableList() }.toMutableList()

    /** Every values.get range requested, decoded, in order. */
    val readRanges = mutableListOf<String>()

    private val json = Json { ignoreUnknownKeys = true }

    private val tokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "token"
        override fun refreshAccessToken(): String = "token"
    }

    fun client(): GoogleSheetsClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> respond(chain) }).build()
        return GoogleSheetsClient(http, tokenProvider)
    }

    /** Appends a data row (type, key, value1..value4, updatedAt, updatedBy, deleted) at the end of the tab. */
    fun addRow(vararg row: String) {
        cells.add(row.toMutableList())
    }

    /** Index into [cells] of the first data row whose type and key match, ignoring case, or -1. */
    fun indexOfKey(type: String, key: String): Int =
        cells.indexOfFirst { it.getOrNull(0).equals(type, ignoreCase = true) && it.getOrNull(1).equals(key, ignoreCase = true) }

    private fun respond(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = URLDecoder.decode(request.url.toString(), "UTF-8")
        val body = when {
            request.method == "GET" && url.contains("/values/") -> valuesGet(url)
            request.method == "GET" -> tabsGet()
            request.method == "PUT" && url.contains("/values/") -> valuesPut(url, readBody(request))
            request.method == "POST" && url.contains("values:batchUpdate") -> valuesBatchUpdate(readBody(request))
            else -> "{}"
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun readBody(request: okhttp3.Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun tabsGet(): String =
        """{"sheets":[{"properties":{"sheetId":1,"title":"Config","gridProperties":{"rowCount":100000}}}]}"""

    private data class Range(val startCol: Int, val startRow: Int, val endCol: Int?, val endRow: Int?)

    private fun parseRange(raw: String): Range {
        val spec = raw.substringAfter("!").substringBefore("?")
        val parts = spec.split(":")
        fun col(s: String) = s.filter { it.isLetter() }.uppercase().fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
        fun row(s: String) = s.filter { it.isDigit() }.toIntOrNull()
        val start = parts[0]
        val end = parts.getOrNull(1)
        return Range(
            startCol = col(start),
            startRow = row(start) ?: 1,
            endCol = end?.let { col(it) }?.takeIf { it >= 0 },
            endRow = end?.let { row(it) }
        )
    }

    private fun valuesGet(url: String): String {
        val range = url.substringAfter("/values/").substringBefore("?")
        readRanges.add(range)
        val r = parseRange(range)
        val rows = mutableListOf<JsonArray>()
        val lastRow = minOf(r.endRow ?: cells.size, cells.size)
        for (rowNumber in r.startRow..lastRow) {
            val row = cells.getOrNull(rowNumber - 1) ?: break
            val endCol = r.endCol ?: (row.size - 1)
            val slice = (r.startCol..minOf(endCol, row.size - 1)).map { row[it] }
            // The real API trims trailing empty cells
            val trimmed = slice.dropLastWhile { it.isEmpty() }
            rows.add(JsonArray(trimmed.map { JsonPrimitive(it) }))
        }
        return buildJsonObject { put("values", JsonArray(rows)) }.toString()
    }

    private fun write(range: String, values: JsonArray) {
        val r = parseRange(range)
        values.forEachIndexed { i, rowElement ->
            val rowIdx = r.startRow - 1 + i
            while (cells.size <= rowIdx) cells.add(mutableListOf())
            val row = cells[rowIdx]
            rowElement.jsonArray.forEachIndexed { j, cell ->
                val colIdx = r.startCol + j
                while (row.size <= colIdx) row.add("")
                row[colIdx] = cell.jsonPrimitive.content
            }
        }
    }

    private fun valuesPut(url: String, body: String): String {
        val obj: JsonObject = json.parseToJsonElement(body).jsonObject
        val range = url.substringAfter("/values/").substringBefore("?")
        write(range, obj["values"]!!.jsonArray)
        return "{}"
    }

    private fun valuesBatchUpdate(body: String): String {
        val obj: JsonObject = json.parseToJsonElement(body).jsonObject
        obj["data"]!!.jsonArray.forEach { entry ->
            val e = entry.jsonObject
            write(e["range"]!!.jsonPrimitive.content, e["values"]!!.jsonArray)
        }
        return "{}"
    }

    companion object {
        fun defaultRows(): List<List<String>> = listOf(
            listOf("Type", "Key", "Value1", "Value2", "Value3", "Value4", "UpdatedAt", "UpdatedBy", "Deleted"),
            listOf("meta", "schemaVersion", "1", "", "", "", "2026-01-01T00:00:00Z", "system", "")
        )
    }
}
