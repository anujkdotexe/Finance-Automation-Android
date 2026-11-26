package com.financeautomation.money

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.RateLimitInterceptor
import com.financeautomation.data.remote.SheetsReadGate
import com.financeautomation.data.remote.TokenProvider
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory Google Sheets test applier and server.
 * Accurately parses and applies updateCells, appendCells, values:batchUpdate, and values.update writes
 * into an in-memory grid per tab. Throws on unknown request types to ensure request contract safety.
 * Uses kotlinx.serialization for pure JVM unit test execution.
 */
class FakeSheetApplier(
    val tabs: MutableList<String> = mutableListOf("Nov26", "Config", "Subscriptions", "Trips")
) {
    data class Call(val method: String, val url: String, val body: String)

    val calls = CopyOnWriteArrayList<Call>()

    // tabName -> row (1-based) -> col (1-based, 1=A) -> value
    val grid = ConcurrentHashMap<String, ConcurrentHashMap<Int, ConcurrentHashMap<Int, Any?>>>()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    var forceFindRowError: Boolean = false

    /** When true, cell formatting requests fail and change nothing. */
    var failFormatting: Boolean = false

    /** How many formatting requests failed because of [failFormatting]. */
    var failedFormattings: Int = 0

    /** When set, every value write and clear answers with this HTTP status and changes nothing. */
    var forceValueWriteStatus: Int? = null

    private var writesUntilFault = 0
    private var faultAfterApplying = false

    /**
     * Makes one coming value write fail with HTTP 500: the [number]th from now, counting every values:batchUpdate
     * call. With [applied] the write reaches the sheet first, as when only the reply is lost.
     */
    fun failValueWrite(number: Int, applied: Boolean) {
        writesUntilFault = number
        faultAfterApplying = applied
    }

    /** True once the write chosen with [failValueWrite] has failed. */
    fun faultWasHit(): Boolean = writesUntilFault == 0

    private val tokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "fake_test_token"
        override fun refreshAccessToken(): String = "fake_test_token"
    }

    init {
        for (tab in tabs) {
            grid[tab] = ConcurrentHashMap()
        }
        // Seed standard header row for monthly tabs at row 3
        for (tab in tabs) {
            if (tab !in listOf("Config", "Subscriptions", "Trips")) {
                setCell(tab, 3, SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE + 1, "Note")
            }
        }
    }

    fun setCell(tab: String, row: Int, col: Int, value: Any?) {
        grid.computeIfAbsent(tab) { ConcurrentHashMap() }
            .computeIfAbsent(row) { ConcurrentHashMap() }[col] = value
    }

    fun getCell(tab: String, row: Int, colLetter: String): Any? {
        val col = colLetterToNumber(colLetter)
        return grid[tab]?.get(row)?.get(col)
    }

    fun getCell(tab: String, row: Int, col: Int): Any? {
        return grid[tab]?.get(row)?.get(col)
    }

    fun getRow(tab: String, row: Int): List<Any?> {
        val rowMap = grid[tab]?.get(row) ?: return emptyList()
        val maxCol = 35
        val list = mutableListOf<Any?>()
        for (c in 1..maxCol) {
            list.add(rowMap[c])
        }
        val lastNonNull = list.indexOfLast { it != null }
        return if (lastNonNull >= 0) list.subList(0, lastNonNull + 1) else emptyList()
    }

    fun getRows(tab: String, minRow: Int = 4, maxRow: Int = 1000): List<List<Any?>> {
        val tabMap = grid[tab] ?: return emptyList()
        val rowsPresent = tabMap.keys.filter { it in minRow..maxRow }.sorted()
        return rowsPresent.map { getRow(tab, it) }
    }

    fun getDebitRows(tab: String): List<List<Any?>> {
        val tabMap = grid[tab] ?: return emptyList()
        val rowsPresent = tabMap.keys.filter { it >= 4 }.sorted()
        return rowsPresent.mapNotNull { r ->
            val row = getRow(tab, r)
            if (row.isNotEmpty() && row[0] != null) {
                // Table 1 Debit is columns 0 to 11 (A-L)
                row.take(12)
            } else null
        }
    }

    fun getInflowRows(tab: String): List<List<Any?>> {
        val tabMap = grid[tab] ?: return emptyList()
        val rowsPresent = tabMap.keys.filter { it >= 4 }.sorted()
        return rowsPresent.mapNotNull { r ->
            val row = getRow(tab, r)
            if (row.size > SheetSchemaConstants.TABLE_2_START_COL_INDEX && row[SheetSchemaConstants.TABLE_2_START_COL_INDEX] != null) {
                val end = minOf(row.size, SheetSchemaConstants.TABLE_2_END_COL_INDEX + 1)
                row.subList(SheetSchemaConstants.TABLE_2_START_COL_INDEX, end)
            } else null
        }
    }

    fun getPayableRows(tab: String): List<List<Any?>> {
        val tabMap = grid[tab] ?: return emptyList()
        val rowsPresent = tabMap.keys.filter { it >= 4 }.sorted()
        return rowsPresent.mapNotNull { r ->
            val row = getRow(tab, r)
            if (row.size > SheetSchemaConstants.TABLE_3_START_COL_INDEX && row[SheetSchemaConstants.TABLE_3_START_COL_INDEX] != null) {
                val end = minOf(row.size, SheetSchemaConstants.TABLE_3_END_COL_INDEX + 1)
                row.subList(SheetSchemaConstants.TABLE_3_START_COL_INDEX, end)
            } else null
        }
    }

    fun client(gate: SheetsReadGate? = null): GoogleSheetsClient {
        val interceptor = Interceptor { chain -> respond(chain.request()) }
        val builder = OkHttpClient.Builder()
        gate?.let { builder.addInterceptor(RateLimitInterceptor(it)) }
        return GoogleSheetsClient(builder.addInterceptor(interceptor).build(), tokenProvider)
    }

    /** Answers one request, for a test that routes several workbooks through one client. */
    fun serve(request: Request): Response = respond(request)

    private fun bodyOf(request: Request): String {
        val b = request.body ?: return ""
        val buf = okio.Buffer()
        b.writeTo(buf)
        return buf.readUtf8()
    }

    private fun respond(request: Request): Response {
        val call = Call(request.method, request.url.toString(), bodyOf(request))
        calls.add(call)

        val (code, body) = handleRequest(call)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody(jsonMediaType))
            .build()
    }

    private fun handleRequest(call: Call): Pair<Int, String> {
        val method = call.method
        val url = call.url
        val body = call.body

        if (forceFindRowError && method == "GET" && (url.contains("AF") || url.contains("AE"))) {
            return 500 to """{"error":{"code":500,"message":"Simulated 500 read error"}}"""
        }

        if (failFormatting && method == "POST" && body.contains("\"repeatCell\"")) {
            failedFormattings++
            return 500 to """{"error":{"code":500,"message":"Simulated formatting error"}}"""
        }

        if (writesUntilFault > 0 && method == "POST" && url.contains("values:batchUpdate")) {
            writesUntilFault--
            if (writesUntilFault == 0) {
                if (faultAfterApplying) handleValuesBatchUpdate(body)
                return 500 to """{"error":{"code":500,"message":"Simulated write fault"}}"""
            }
        }

        if (forceValueWriteStatus != null && method == "POST" &&
            (url.contains("values:batchUpdate") || url.contains("values:batchClear"))
        ) {
            val status = forceValueWriteStatus!!
            return status to """{"error":{"code":$status,"message":"Simulated write error"}}"""
        }

        return when {
            method == "GET" && url.contains("values:batchGet") -> {
                handleBatchGet(url)
            }
            method == "GET" && url.contains("/values/") -> {
                handleSingleGet(url)
            }
            method == "GET" -> {
                handleSpreadsheetMetadata()
            }
            method == "POST" && url.contains("values:batchUpdate") -> {
                handleValuesBatchUpdate(body)
            }
            method == "POST" && url.contains("values:batchClear") -> {
                handleValuesBatchClear(body)
            }
            method == "PUT" && url.contains("/values/") -> {
                handleValuesUpdate(url, body)
            }
            method == "POST" && url.contains(":append") -> {
                handleValuesAppend(url, body)
            }
            method == "POST" && url.contains("batchUpdate") -> {
                handleBatchUpdate(body)
            }
            else -> {
                throw IllegalArgumentException("FakeSheetApplier unsupported request: $method $url")
            }
        }
    }

    private fun handleSpreadsheetMetadata(): Pair<Int, String> {
        val sheets = tabs.mapIndexed { idx, title ->
            """{"properties":{"sheetId":$idx,"title":"$title","gridProperties":{"rowCount":1000,"columnCount":35}}}"""
        }.joinToString(",")
        return 200 to """{"spreadsheetId":"test_sheet_id","sheets":[$sheets]}"""
    }

    private fun handleBatchGet(url: String): Pair<Int, String> {
        val ranges = extractRangesFromUrl(url)
        val valueRangesJson = ranges.map { r ->
            val parsed = parseA1Range(r)
            val rows = readRangeValues(parsed)
            val rowsJson = rows.joinToString(",") { row ->
                val cellsJson = row.joinToString(",") { cell -> formatCellJson(cell) }
                "[$cellsJson]"
            }
            """{"range":"${escapeJson(r)}","majorDimension":"ROWS","values":[$rowsJson]}"""
        }.joinToString(",")

        return 200 to """{"valueRanges":[$valueRangesJson]}"""
    }

    private fun handleSingleGet(url: String): Pair<Int, String> {
        val encodedRange = url.substringAfter("/values/").substringBefore("?")
        val range = URLDecoder.decode(encodedRange, "UTF-8")
        val parsed = parseA1Range(range)
        val rows = readRangeValues(parsed)
        val rowsJson = rows.joinToString(",") { row ->
            val cellsJson = row.joinToString(",") { cell -> formatCellJson(cell) }
            "[$cellsJson]"
        }
        return 200 to """{"range":"${escapeJson(range)}","majorDimension":"ROWS","values":[$rowsJson]}"""
    }

    private fun handleValuesBatchUpdate(body: String): Pair<Int, String> {
        val jsonElement = json.parseToJsonElement(body).jsonObject
        val data = jsonElement["data"]?.jsonArray ?: JsonArray(emptyList())
        var updatedCells = 0

        for (item in data) {
            val itemObj = item.jsonObject
            val rangeStr = itemObj["range"]?.jsonPrimitive?.content.orEmpty()
            val parsedRange = parseA1Range(rangeStr)
            val values = itemObj["values"]?.jsonArray ?: JsonArray(emptyList())

            for (r in 0 until values.size) {
                val rowArray = values[r].jsonArray
                val targetRow = parsedRange.startRow + r
                for (c in 0 until rowArray.size) {
                    val targetCol = parsedRange.startCol + c
                    val cellVal = jsonElementToAny(rowArray[c])
                    setCell(parsedRange.tab, targetRow, targetCol, cellVal)
                    updatedCells++
                }
            }
        }
        return 200 to """{"totalUpdatedCells":$updatedCells,"responses":[]}"""
    }

    private fun handleValuesBatchClear(body: String): Pair<Int, String> {
        val jsonElement = json.parseToJsonElement(body).jsonObject
        val ranges = jsonElement["ranges"]?.jsonArray ?: JsonArray(emptyList())
        val clearedRanges = mutableListOf<String>()

        for (rangeElem in ranges) {
            val rangeStr = rangeElem.jsonPrimitive.content
            val parsedRange = parseA1Range(rangeStr)
            val tabMap = grid[parsedRange.tab]
            if (tabMap != null) {
                for (r in parsedRange.startRow..parsedRange.endRow) {
                    val rowMap = tabMap[r]
                    if (rowMap != null) {
                        for (c in parsedRange.startCol..parsedRange.endCol) {
                            rowMap.remove(c)
                        }
                    }
                }
            }
            clearedRanges.add(rangeStr)
        }
        val rangesJson = clearedRanges.joinToString(",") { "\"${escapeJson(it)}\"" }
        return 200 to """{"spreadsheetId":"test_sheet_id","clearedRanges":[$rangesJson]}"""
    }

    private fun handleValuesUpdate(url: String, body: String): Pair<Int, String> {
        val encodedRange = url.substringAfter("/values/").substringBefore("?")
        val range = URLDecoder.decode(encodedRange, "UTF-8")
        val parsedRange = parseA1Range(range)

        val jsonElement = json.parseToJsonElement(body).jsonObject
        val values = jsonElement["values"]?.jsonArray ?: JsonArray(emptyList())
        var updatedCells = 0

        for (r in 0 until values.size) {
            val rowArray = values[r].jsonArray
            val targetRow = parsedRange.startRow + r
            for (c in 0 until rowArray.size) {
                val targetCol = parsedRange.startCol + c
                val cellVal = jsonElementToAny(rowArray[c])
                setCell(parsedRange.tab, targetRow, targetCol, cellVal)
                updatedCells++
            }
        }
        return 200 to """{"spreadsheetId":"test_sheet_id","updatedRange":"${escapeJson(range)}","updatedCells":$updatedCells}"""
    }

    private fun handleValuesAppend(url: String, body: String): Pair<Int, String> {
        val encodedRange = url.substringAfter("/values/").substringBefore(":append").substringBefore("?")
        val range = URLDecoder.decode(encodedRange, "UTF-8")
        val parsedRange = parseA1Range(range)

        val jsonElement = json.parseToJsonElement(body).jsonObject
        val values = jsonElement["values"]?.jsonArray ?: JsonArray(emptyList())

        val tabMap = grid.computeIfAbsent(parsedRange.tab) { ConcurrentHashMap() }
        val nextFreeRow = maxOf(4, (tabMap.keys.maxOrNull() ?: 3) + 1)
        var updatedCells = 0

        for (r in 0 until values.size) {
            val rowArray = values[r].jsonArray
            val targetRow = nextFreeRow + r
            for (c in 0 until rowArray.size) {
                val targetCol = parsedRange.startCol + c
                val cellVal = jsonElementToAny(rowArray[c])
                setCell(parsedRange.tab, targetRow, targetCol, cellVal)
                updatedCells++
            }
        }
        return 200 to """{"spreadsheetId":"test_sheet_id","updatedRange":"'${parsedRange.tab}'!A$nextFreeRow:AF$nextFreeRow","updatedCells":$updatedCells}"""
    }

    private fun handleBatchUpdate(body: String): Pair<Int, String> {
        val jsonElement = json.parseToJsonElement(body).jsonObject
        val requests = jsonElement["requests"]?.jsonArray ?: JsonArray(emptyList())

        for (reqElem in requests) {
            val req = reqElem.jsonObject
            for (key in req.keys) {
                when (key) {
                    "updateCells" -> applyUpdateCells(req["updateCells"]!!.jsonObject)
                    "appendCells" -> applyAppendCells(req["appendCells"]!!.jsonObject)
                    "addSheet" -> {
                        val title = req["addSheet"]?.jsonObject?.get("properties")?.jsonObject?.get("title")?.jsonPrimitive?.content
                        if (!title.isNullOrBlank() && !tabs.contains(title)) {
                            tabs.add(title)
                            grid[title] = ConcurrentHashMap()
                        }
                    }
                    "updateSheetProperties" -> {
                        // Safe metadata update
                    }
                    "repeatCell" -> {
                        // Formatting
                    }
                    else -> {
                        throw IllegalArgumentException("Unknown request type: $key")
                    }
                }
            }
        }
        return 200 to """{"totalUpdatedCells":0,"replies":[]}"""
    }

    private fun applyUpdateCells(updateCellsObj: JsonObject) {
        val start = updateCellsObj["start"]?.jsonObject
        val range = updateCellsObj["range"]?.jsonObject
        val sheetId = start?.get("sheetId")?.jsonPrimitive?.intOrNull
            ?: range?.get("sheetId")?.jsonPrimitive?.intOrNull ?: 0
        val tab = tabs.getOrNull(sheetId) ?: tabs.first()

        val startRow = (start?.get("rowIndex")?.jsonPrimitive?.intOrNull
            ?: range?.get("startRowIndex")?.jsonPrimitive?.intOrNull ?: 0) + 1
        val startCol = (start?.get("columnIndex")?.jsonPrimitive?.intOrNull
            ?: range?.get("startColumnIndex")?.jsonPrimitive?.intOrNull ?: 0) + 1

        val rows = updateCellsObj["rows"]?.jsonArray ?: JsonArray(emptyList())
        for (r in 0 until rows.size) {
            val rowObj = rows[r].jsonObject
            val values = rowObj["values"]?.jsonArray ?: JsonArray(emptyList())
            val targetRow = startRow + r
            for (c in 0 until values.size) {
                val targetCol = startCol + c
                val cellObj = values[c].jsonObject
                val userEntered = cellObj["userEnteredValue"]?.jsonObject
                val value = userEntered?.let { extractUserEnteredValue(it) }
                setCell(tab, targetRow, targetCol, value)
            }
        }
    }

    private fun applyAppendCells(appendCellsObj: JsonObject) {
        val sheetId = appendCellsObj["sheetId"]?.jsonPrimitive?.intOrNull ?: 0
        val tab = tabs.getOrNull(sheetId) ?: tabs.first()
        val tabMap = grid.computeIfAbsent(tab) { ConcurrentHashMap() }
        val nextFreeRow = maxOf(4, (tabMap.keys.maxOrNull() ?: 3) + 1)

        val rows = appendCellsObj["rows"]?.jsonArray ?: JsonArray(emptyList())
        for (r in 0 until rows.size) {
            val rowObj = rows[r].jsonObject
            val values = rowObj["values"]?.jsonArray ?: JsonArray(emptyList())
            val targetRow = nextFreeRow + r
            for (c in 0 until values.size) {
                val cellObj = values[c].jsonObject
                val userEntered = cellObj["userEnteredValue"]?.jsonObject
                val value = userEntered?.let { extractUserEnteredValue(it) }
                setCell(tab, targetRow, c + 1, value)
            }
        }
    }

    private fun extractUserEnteredValue(userEntered: JsonObject): Any? {
        val stringVal = userEntered["stringValue"]?.jsonPrimitive?.contentOrNull
        if (stringVal != null) return stringVal

        val numVal = userEntered["numberValue"]?.jsonPrimitive?.doubleOrNull
        if (numVal != null) return numVal

        val boolVal = userEntered["boolValue"]?.jsonPrimitive?.booleanOrNull
        if (boolVal != null) return boolVal

        val formulaVal = userEntered["formulaValue"]?.jsonPrimitive?.contentOrNull
        if (formulaVal != null) return formulaVal

        return null
    }

    private fun jsonElementToAny(elem: JsonElement): Any? = when (elem) {
        is JsonNull -> null
        is JsonPrimitive -> {
            elem.doubleOrNull ?: elem.booleanOrNull ?: elem.content
        }
        else -> elem.toString()
    }

    private fun readRangeValues(range: A1Range): List<List<Any?>> {
        val tabMap = grid[range.tab] ?: return emptyList()
        val maxAvailableRow = tabMap.keys.maxOrNull() ?: 3
        val effectiveEndRow = minOf(range.endRow, maxOf(range.startRow, maxAvailableRow))
        if (range.startRow > effectiveEndRow) return emptyList()

        val result = mutableListOf<List<Any?>>()
        for (r in range.startRow..effectiveEndRow) {
            val rowMap = tabMap[r]
            val rowValues = mutableListOf<Any?>()
            for (c in range.startCol..range.endCol) {
                rowValues.add(rowMap?.get(c))
            }
            result.add(rowValues)
        }
        val lastNonEmpty = result.indexOfLast { row -> row.any { it != null } }
        return if (lastNonEmpty >= 0) result.subList(0, lastNonEmpty + 1) else emptyList()
    }

    private fun extractRangesFromUrl(url: String): List<String> {
        val query = url.substringAfter("?", "")
        if (query.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val parts = pair.split("=")
            if (parts.size == 2 && parts[0] == "ranges") {
                result.add(URLDecoder.decode(parts[1], "UTF-8"))
            }
        }
        return result
    }

    private fun parseA1Range(rangeStr: String): A1Range {
        val clean = rangeStr.trim()
        val parts = clean.split("!")
        val tab = if (parts.size > 1) parts[0].removeSurrounding("'") else tabs.first()
        val cellPart = if (parts.size > 1) parts[1] else parts[0]

        val cellTokens = cellPart.split(":")
        val first = cellTokens[0]
        val firstColLetters = first.filter { it.isLetter() }
        val firstRowDigits = first.filter { it.isDigit() }
        val startCol = if (firstColLetters.isNotEmpty()) colLetterToNumber(firstColLetters) else 1
        val startRow = if (firstRowDigits.isNotEmpty()) firstRowDigits.toInt() else 1

        val endCol: Int
        val endRow: Int
        if (cellTokens.size > 1) {
            val second = cellTokens[1]
            val secondColLetters = second.filter { it.isLetter() }
            val secondRowDigits = second.filter { it.isDigit() }
            endCol = if (secondColLetters.isNotEmpty()) colLetterToNumber(secondColLetters) else startCol
            endRow = if (secondRowDigits.isNotEmpty()) secondRowDigits.toInt() else 1000
        } else {
            endCol = startCol
            endRow = startRow
        }
        return A1Range(tab, startCol, startRow, endCol, endRow)
    }

    private fun colLetterToNumber(col: String): Int {
        var result = 0
        for (char in col.uppercase()) {
            result = result * 26 + (char - 'A' + 1)
        }
        return result
    }

    private fun formatCellJson(cell: Any?): String = when (cell) {
        null -> "\"\""
        is Number -> cell.toString()
        is Boolean -> cell.toString()
        else -> "\"${escapeJson(cell.toString())}\""
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    data class A1Range(val tab: String, val startCol: Int, val startRow: Int, val endCol: Int, val endRow: Int)
}
