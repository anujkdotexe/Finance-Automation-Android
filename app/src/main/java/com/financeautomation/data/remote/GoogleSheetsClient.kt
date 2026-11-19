package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.DataResult
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.Subscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DEFAULT_SHEET_ZONE_ID: ZoneId = ZoneId.of("Asia/Kolkata")
private val ROW_INDEX_REGEX = Regex("""[A-Za-z]+(\d+)""")
private val MONTH_TAB_REGEX = Regex("""^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(\d{2})$""", RegexOption.IGNORE_CASE)

/**
 * Domain-specific exception types for Google Sheets v4 REST operations.
 */
open class GoogleSheetsException(message: String, cause: Throwable? = null) : IOException(message, cause)
class SheetNotFoundException(val spreadsheetId: String, message: String) : GoogleSheetsException(message)
class AuthExpiredException(message: String) : GoogleSheetsException(message)
class MalformedResponseException(message: String, cause: Throwable? = null) : GoogleSheetsException(message, cause)
class GoogleSheetsApiException(val statusCode: Int, message: String, val apiError: GoogleApiError? = null) : GoogleSheetsException(message)

/**
 * True when the same request is worth repeating later: server errors, 408 timeouts, 429 and a 403 whose reason says
 * the quota or rate limit was hit. A plain 403 (permission) is not retryable.
 */
fun GoogleSheetsApiException.isRetryable(): Boolean = when (statusCode) {
    in 500..599, 408, 429 -> true
    // The grid was too small for the write; the next attempt expands it first
    400 -> message?.contains("exceeds grid limits", ignoreCase = true) == true
    403 -> apiError?.reason?.lowercase() in API_RATE_LIMIT_REASONS
    else -> false
}

/** True when waiting is the right answer for the whole run, not only this item (limits, timeouts, unavailable). */
fun GoogleSheetsApiException.isRunWideBackoff(): Boolean = statusCode == 408 || statusCode == 429 || statusCode == 503 ||
    (statusCode == 403 && isRetryable())

internal fun createHttpException(
    code: Int,
    body: String,
    context: String,
    spreadsheetId: String = "",
    endpointId: String = GoogleEndpoint.SHEETS_OPERATION
): IOException {
    val apiError = GoogleApiError.parse(endpointId, code, body)
    return when (code) {
        401 -> AuthExpiredException("$context: HTTP 401 Unauthorized - OAuth access token expired or invalid. Details: ${apiError.message}")
        404 -> SheetNotFoundException(spreadsheetId, "$context: HTTP 404 Spreadsheet or Tab not found ($spreadsheetId). Details: ${apiError.message}")
        in 500..599 -> GoogleSheetsApiException(code, "$context: HTTP $code Google Sheets server error. Details: ${apiError.message}", apiError)
        else -> GoogleSheetsApiException(code, "$context: HTTP $code (${apiError.reason ?: apiError.status ?: "UNKNOWN"}) - ${apiError.message}", apiError)
    }
}

internal fun <T> Throwable.toDataResult(spreadsheetId: String = ""): DataResult<T> = when (this) {
    is SheetNotFoundException -> DataResult.SheetNotFound(this.spreadsheetId.ifBlank { spreadsheetId }, message ?: "Sheet not found")
    is AuthExpiredException -> DataResult.AuthExpired(message ?: "Auth expired")
    is MalformedResponseException -> DataResult.Malformed(message ?: "Malformed response")
    is SerializationException -> DataResult.Malformed(message ?: "JSON parse error")
    is GoogleSheetsApiException -> DataResult.General(message ?: "Google Sheets API error", this)
    is SocketTimeoutException -> DataResult.Network(this)
    is IOException -> DataResult.Network(this)
    else -> DataResult.General(message ?: "General error", this)
}

/**
 * Direct OkHttp client for Google Sheets v4 REST API.
 * Executes atomic RAW appends, in-place updates, and batch provisioning.
 */
open class GoogleSheetsClient(
    private val okHttpClient: OkHttpClient,
    private val tokenProvider: TokenProvider? = null,
    private val baseUrl: String = "https://sheets.googleapis.com/v4/spreadsheets"
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun newRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        tokenProvider?.getAccessToken()?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    /**
     * Appends a transaction row to [tabName] in [spreadsheetId].
     * Uses valueInputOption=RAW and insertDataOption=OVERWRITE.
     * Table-scoped: Routes Debits to A3:L, Inflows to N3:U, and Payables to W3:AC.
     * Never writes across spacer columns M and V.
     * Returns the 1-based sheet row index where data landed.
     */
    suspend fun appendTransaction(
        spreadsheetId: String,
        tabName: String,
        row: List<Any?>
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val (tableRange, slice) = when {
                row.size == 8 -> {
                    SheetSchemaConstants.TABLE_2_INFLOWS_RANGE to row
                }
                row.size == 7 -> {
                    SheetSchemaConstants.TABLE_3_PAYABLES_RANGE to row
                }
                row.size == 12 -> {
                    SheetSchemaConstants.TABLE_1_DEBITS_RANGE to row
                }
                row.size == 11 -> {
                    SheetSchemaConstants.TABLE_1_DEBITS_RANGE to row
                }
                row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT -> {
                    // Extract table-scoped slice to avoid writing across spacers M and V
                    val hasInflowUuid = row.getOrNull(SheetSchemaConstants.COL_INDEX_INFLOW_UUID)?.toString().orEmpty().isNotBlank()
                    val hasInflowAmount = row.getOrNull(19)?.toString().orEmpty().isNotBlank()
                    val hasPayableUuid = row.getOrNull(SheetSchemaConstants.COL_INDEX_PAYABLE_UUID)?.toString().orEmpty().isNotBlank()
                    val hasPayableAmount = row.getOrNull(26)?.toString().orEmpty().isNotBlank()

                    when {
                        hasInflowUuid || hasInflowAmount -> {
                            SheetSchemaConstants.TABLE_2_INFLOWS_RANGE to row.subList(SheetSchemaConstants.TABLE_2_START_COL_INDEX, SheetSchemaConstants.TABLE_2_END_COL_INDEX + 1)
                        }
                        hasPayableUuid || hasPayableAmount -> {
                            SheetSchemaConstants.TABLE_3_PAYABLES_RANGE to row.subList(SheetSchemaConstants.TABLE_3_START_COL_INDEX, SheetSchemaConstants.TABLE_3_END_COL_INDEX + 1)
                        }
                        else -> {
                            SheetSchemaConstants.TABLE_1_DEBITS_RANGE to row.subList(SheetSchemaConstants.TABLE_1_START_COL_INDEX, SheetSchemaConstants.TABLE_1_END_COL_INDEX + 1)
                        }
                    }
                }
                row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT_V1 -> {
                    val hasInflowUuid = row.getOrNull(SheetSchemaConstants.COL_INDEX_INFLOW_UUID_V1)?.toString().orEmpty().isNotBlank()
                    val hasInflowAmount = row.getOrNull(18)?.toString().orEmpty().isNotBlank()
                    val hasPayableUuid = row.getOrNull(SheetSchemaConstants.COL_INDEX_PAYABLE_UUID_V1)?.toString().orEmpty().isNotBlank()
                    val hasPayableAmount = row.getOrNull(25)?.toString().orEmpty().isNotBlank()

                    when {
                        hasInflowUuid || hasInflowAmount -> {
                            SheetSchemaConstants.TABLE_2_INFLOWS_RANGE_V1 to row.subList(SheetSchemaConstants.TABLE_2_START_COL_INDEX_V1, SheetSchemaConstants.TABLE_2_END_COL_INDEX_V1 + 1)
                        }
                        hasPayableUuid || hasPayableAmount -> {
                            SheetSchemaConstants.TABLE_3_PAYABLES_RANGE_V1 to row.subList(SheetSchemaConstants.TABLE_3_START_COL_INDEX_V1, SheetSchemaConstants.TABLE_3_END_COL_INDEX_V1 + 1)
                        }
                        else -> {
                            SheetSchemaConstants.TABLE_1_DEBITS_RANGE_V1 to row.subList(SheetSchemaConstants.TABLE_1_START_COL_INDEX, SheetSchemaConstants.TABLE_1_END_COL_INDEX_V1 + 1)
                        }
                    }
                }
                row.size < 12 -> {
                    SheetSchemaConstants.TABLE_1_DEBITS_RANGE to row
                }
                else -> {
                    SheetSchemaConstants.TABLE_1_DEBITS_RANGE to row.take(12)
                }
            }

            appendTableScoped(spreadsheetId, tabName, tableRange, slice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Appends a Table 1 Debit transaction strictly to range A3:L.
     */
    suspend fun appendDebit(
        spreadsheetId: String,
        tabName: String,
        row: List<Any?>,
        uuid: String? = null
    ): Result<Int> {
        val slice = if (row.size > 12) row.take(12) else row
        return appendTableScoped(
            spreadsheetId = spreadsheetId,
            tabName = tabName,
            tableRange = SheetSchemaConstants.TABLE_1_DEBITS_RANGE,
            row = slice,
            keyCol = "A",
            uuidCol = SheetSchemaConstants.COL_LETTER_DEBIT_UUID,
            uuid = uuid
        )
    }

    /**
     * Appends a Table 2 Inflow transaction strictly to range N3:U.
     */
    suspend fun appendInflow(
        spreadsheetId: String,
        tabName: String,
        row: List<Any?>,
        uuid: String? = null
    ): Result<Int> {
        val slice = when {
            row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT -> row.subList(SheetSchemaConstants.TABLE_2_START_COL_INDEX, SheetSchemaConstants.TABLE_2_END_COL_INDEX + 1)
            row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT_V1 -> row.subList(SheetSchemaConstants.TABLE_2_START_COL_INDEX_V1, SheetSchemaConstants.TABLE_2_END_COL_INDEX_V1 + 1)
            row.size > 8 -> row.take(8)
            else -> row
        }
        return appendTableScoped(
            spreadsheetId = spreadsheetId,
            tabName = tabName,
            tableRange = SheetSchemaConstants.TABLE_2_INFLOWS_RANGE,
            row = slice,
            keyCol = "N",
            uuidCol = SheetSchemaConstants.COL_LETTER_INFLOW_UUID,
            uuid = uuid
        )
    }

    /**
     * Appends a Table 3 Payable transaction strictly to range W3:AC.
     */
    suspend fun appendPayable(
        spreadsheetId: String,
        tabName: String,
        row: List<Any?>,
        uuid: String? = null
    ): Result<Int> {
        val slice = when {
            row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT -> row.subList(SheetSchemaConstants.TABLE_3_START_COL_INDEX, SheetSchemaConstants.TABLE_3_END_COL_INDEX + 1)
            row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT_V1 -> row.subList(SheetSchemaConstants.TABLE_3_START_COL_INDEX_V1, SheetSchemaConstants.TABLE_3_END_COL_INDEX_V1 + 1)
            row.size > 7 -> row.take(7)
            else -> row
        }
        return appendTableScoped(
            spreadsheetId = spreadsheetId,
            tabName = tabName,
            tableRange = SheetSchemaConstants.TABLE_3_PAYABLES_RANGE,
            row = slice,
            keyCol = "W",
            uuidCol = SheetSchemaConstants.COL_LETTER_PAYABLE_UUID,
            uuid = uuid
        )
    }

    /**
     * Computes the next free row for a table by scanning the key column and optional UUID column
     * from [minRow] downwards. Returns maxOf(minRow, lastOccupiedRow + 1).
     */
    suspend fun getNextFreeTableRow(
        spreadsheetId: String,
        tabName: String,
        keyCol: String,
        uuidCol: String? = null,
        minRow: Int = 4
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var lastOccupiedRow = minRow - 1

            // 1. Scan key column (e.g. date)
            val keyRange = "'$tabName'!$keyCol$minRow:$keyCol"
            val keyRes = getRangeValuesRaw(spreadsheetId, keyRange)
            if (keyRes is Result.Error) return@withContext Result.Error(keyRes.exception)
            val keyRows = (keyRes as Result.Success).data
            for (i in keyRows.indices.reversed()) {
                val cell = keyRows[i].firstOrNull()?.toString()?.trim()
                if (!cell.isNullOrEmpty()) {
                    lastOccupiedRow = maxOf(lastOccupiedRow, minRow + i)
                    break
                }
            }

            // 2. Scan UUID column if specified
            if (!uuidCol.isNullOrBlank()) {
                val uuidRange = "'$tabName'!$uuidCol$minRow:$uuidCol"
                val uuidRes = getRangeValues(spreadsheetId, uuidRange)
                if (uuidRes is Result.Success) {
                    val uuidRows = uuidRes.data
                    for (i in uuidRows.indices.reversed()) {
                        val cell = uuidRows[i].firstOrNull()?.toString()?.trim()
                        if (!cell.isNullOrEmpty()) {
                            lastOccupiedRow = maxOf(lastOccupiedRow, minRow + i)
                            break
                        }
                    }
                }
            }

            val nextRow = maxOf(minRow, lastOccupiedRow + 1)
            Result.Success(nextRow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Appends values strictly within [tableRange] (e.g. A3:L, N3:U, W3:AC) by computing the next free row
     * and writing with values.update (PUT) to the explicit row range (e.g. A{r}:L{r}, N{r}:U{r}, W{r}:AC{r}).
     * Defaults to valueInputOption=RAW to guarantee formula injection immunity.
     * Guarantees that empty secondary tables (Inflows/Payables) do NOT fall back into Column A.
     */
    suspend fun appendTableScoped(
        spreadsheetId: String,
        tabName: String,
        tableRange: String,
        row: List<Any?>,
        valueInputOption: String = "RAW",
        keyCol: String? = null,
        uuidCol: String? = null,
        uuid: String? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        require(valueInputOption == "RAW") { "valueInputOption must strictly be RAW, received: $valueInputOption" }
        try {
            val parts = tableRange.split(":")
            val rawStart = parts.getOrNull(0).orEmpty()
            val rawEnd = parts.getOrNull(1).orEmpty()
            val startCol = rawStart.filter { it.isLetter() }.ifEmpty { "A" }
            val endCol = rawEnd.filter { it.isLetter() }.ifEmpty { startCol }
            val startRowDigits = rawStart.filter { it.isDigit() }.toIntOrNull()
            val minRow = when {
                startRowDigits != null -> startRowDigits + 1
                tabName.equals("Config", ignoreCase = true) -> 2
                else -> 4
            }
            val resolvedKeyCol = keyCol ?: startCol
            val resolvedUuidCol = uuidCol ?: when (startCol) {
                "A" -> if (tabName.equals("Config", ignoreCase = true)) null else SheetSchemaConstants.COL_LETTER_DEBIT_UUID
                "M" -> SheetSchemaConstants.COL_LETTER_INFLOW_UUID
                "V" -> SheetSchemaConstants.COL_LETTER_PAYABLE_UUID
                else -> null
            }

            val nextRowRes = getNextFreeTableRow(spreadsheetId, tabName, resolvedKeyCol, resolvedUuidCol, minRow)
            if (nextRowRes is Result.Error) return@withContext Result.Error(nextRowRes.exception)
            val nextRow = (nextRowRes as Result.Success).data

            // If the grid cannot grow, say so now; writing past its end would fail with a misleading permanent 400
            val capacity = ensureGridCapacityBeforeAppend(spreadsheetId, tabName, threshold = 20, expandBy = 100, requiredRow = nextRow)
            if (capacity is Result.Error) return@withContext Result.Error(capacity.exception)

            val targetRange = "'$tabName'!$startCol$nextRow:$endCol$nextRow"

            // Item 2.4: Write table cells and UUID in one atomic values:batchUpdate request
            if (!uuid.isNullOrBlank() && resolvedUuidCol != null) {
                val uuidRange = "'$tabName'!$resolvedUuidCol$nextRow"
                val updatesMap = mapOf(
                    targetRange to listOf(row),
                    uuidRange to listOf(listOf(uuid))
                )
                val batchRes = batchUpdateValues(spreadsheetId, updatesMap, valueInputOption)
                if (batchRes is Result.Error) return@withContext Result.Error(batchRes.exception)
                return@withContext Result.Success(nextRow)
            }

            val encodedRange = URLEncoder.encode(targetRange, "UTF-8")
            val url = "$baseUrl/$spreadsheetId/values/$encodedRange?valueInputOption=$valueInputOption"

            val rowElements = JsonArray(row.map { it.toJsonElement() })
            val valuesArray = JsonArray(listOf(rowElements))

            val requestBodyJson = buildJsonObject {
                put("range", JsonPrimitive(targetRange))
                put("majorDimension", JsonPrimitive("ROWS"))
                put("values", valuesArray)
            }.toString()

            val request = newRequestBuilder(url)
                .put(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "Sheets appendTableScoped (values.update) failed", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_UPDATE)
                    )
                }

                val jsonResponse = try {
                    json.parseToJsonElement(bodyString).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse JSON response (code ${response.code})", e))
                }

                val updatedRange = jsonResponse["updatedRange"]?.jsonPrimitive?.content
                    ?: jsonResponse["updates"]?.jsonObject?.get("updatedRange")?.jsonPrimitive?.content

                if (updatedRange != null) {
                    val parsedRowIndex = parseRowIndexFromRange(updatedRange)
                    if (parsedRowIndex != null) {
                        Result.Success(parsedRowIndex)
                    } else {
                        Result.Error(MalformedResponseException("Could not parse row index from updatedRange: '$updatedRange'"))
                    }
                } else {
                    Result.Success(nextRow)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Checks if [tabName] in [spreadsheetId] is within [threshold] rows of full.
     * If so, calls appendDimension to add [expandBy] rows before appending.
     */
    suspend fun ensureGridCapacityBeforeAppend(
        spreadsheetId: String,
        tabName: String,
        threshold: Int = 20,
        expandBy: Int = 100,
        requiredRow: Int = -1
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/$spreadsheetId?fields=sheets(properties(sheetId%2Ctitle%2CgridProperties(rowCount)))"
            val request = newRequestBuilder(url).get().build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "ensureGridCapacity failed to fetch properties", spreadsheetId, GoogleEndpoint.SHEETS_GET)
                    )
                }

                val jsonResponse = try {
                    json.parseToJsonElement(bodyString).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse sheet properties (code ${response.code})", e))
                }

                val sheetsArray = jsonResponse["sheets"]?.jsonArray.orEmpty()
                val targetSheet = sheetsArray.firstOrNull { s ->
                    s.jsonObject["properties"]?.jsonObject?.get("title")?.jsonPrimitive?.content == tabName
                }?.jsonObject?.get("properties")?.jsonObject

                if (targetSheet != null) {
                    val sheetId = targetSheet["sheetId"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val totalRows = targetSheet["gridProperties"]?.jsonObject?.get("rowCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 1000

                    // The caller already scanned the sheet to find requiredRow, so do not read the column again
                    val currentDataRows = if (requiredRow > 0) 0 else {
                        val readResult = getRangeValuesRaw(spreadsheetId, "'$tabName'!A4:A")
                        if (readResult is Result.Success) readResult.data.size else 0
                    }
                    val lastRow = maxOf(currentDataRows + 3, requiredRow) // header occupies rows 1-3

                    if (lastRow >= totalRows - threshold) {
                        return@withContext appendDimension(spreadsheetId, sheetId, "ROWS", expandBy)
                    }
                }
                Result.Success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Updates a single cell in [tabName] at [cellCoordinate] (e.g. "AC4", "AD5", "AE6").
     * Uses valueInputOption=RAW by default.
     */
    open suspend fun updateCell(
        spreadsheetId: String,
        tabName: String,
        cellCoordinate: String,
        value: Any?,
        valueInputOption: String = "RAW"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        require(valueInputOption == "RAW") { "valueInputOption must strictly be RAW, received: $valueInputOption" }
        try {
            val range = "'$tabName'!$cellCoordinate"
            val encodedRange = URLEncoder.encode(range, "UTF-8")
            val url = "$baseUrl/$spreadsheetId/values/$encodedRange?valueInputOption=$valueInputOption"

            val cellElement = value.toJsonElement()
            val rowArray = JsonArray(listOf(cellElement))
            val valuesArray = JsonArray(listOf(rowArray))

            val requestBodyJson = buildJsonObject {
                put("range", JsonPrimitive(range))
                put("majorDimension", JsonPrimitive("ROWS"))
                put("values", valuesArray)
            }.toString()

            val request = newRequestBuilder(url)
                .put(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "updateCell failed on $cellCoordinate", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_UPDATE)
                    )
                }
                Result.Success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Reads a single cell string value at [cellCoordinate] in [tabName].
     */
    suspend fun getCellString(
        spreadsheetId: String,
        tabName: String,
        cellCoordinate: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val rangeResult = getRangeValues(spreadsheetId, "'$tabName'!$cellCoordinate")
            if (rangeResult is Result.Error) return@withContext Result.Error(rangeResult.exception)
            val rows = (rangeResult as Result.Success).data
            val value = rows.firstOrNull()?.firstOrNull()?.toString()?.trim()
            Result.Success(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Searches a specific column from row 4 downward for [uuid].
     * Returns 1-based row index if found, or null if not found.
     */
    suspend fun findRowByUuid(
        spreadsheetId: String,
        tabName: String,
        colLetter: String,
        uuid: String
    ): Result<Int?> = withContext(Dispatchers.IO) {
        try {
            if (uuid.isBlank()) return@withContext Result.Success(null)
            val range = "'$tabName'!${colLetter}4:$colLetter"
            val valuesResult = getRangeValues(spreadsheetId, range)
            if (valuesResult is Result.Error) {
                return@withContext Result.Error(valuesResult.exception)
            }
            val rows = (valuesResult as Result.Success).data
            for (i in rows.indices) {
                val cell = rows[i].firstOrNull()?.toString()?.trim()
                if (cell != null && cell.equals(uuid, ignoreCase = true)) {
                    return@withContext Result.Success(i + 4)
                }
            }
            Result.Success(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Searches for an orphan Table 1 debit row with matching date serial, time serial,
     * and amount whose column AD (or legacy AC) UUID is blank.
     * Note: Orphan adoption can legitimately match an iOS-written row (blank UUID) with the same date, second and amount.
     */
    suspend fun findOrphanDebitRow(
        spreadsheetId: String,
        tabName: String,
        dateSerial: Int,
        timeSerial: Double,
        amount: Double,
        account: String? = null,
        description: String? = null
    ): Result<Int?> = withContext(Dispatchers.IO) {
        try {
            val dataResult = getRangeValuesRaw(spreadsheetId, "'$tabName'!A4:G")
            if (dataResult is Result.Error) return@withContext Result.Error(dataResult.exception)
            var uuidResult = getRangeValues(spreadsheetId, "'$tabName'!${SheetSchemaConstants.COL_LETTER_DEBIT_UUID}4:${SheetSchemaConstants.COL_LETTER_DEBIT_UUID}")
            if (uuidResult is Result.Error) {
                uuidResult = getRangeValues(spreadsheetId, "'$tabName'!${SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1}4:${SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1}")
            }
            if (uuidResult is Result.Error) return@withContext Result.Error(uuidResult.exception)

            val dataRows = (dataResult as Result.Success).data
            val uuidRows = (uuidResult as Result.Success).data

            val targetSeconds = Math.round(timeSerial * 86400.0)

            for (i in dataRows.indices) {
                val row = dataRows[i]
                if (row.isEmpty()) continue
                val rowDate = row.getOrNull(0)?.toString()?.toDoubleOrNull()?.toInt()
                val rowTime = row.getOrNull(1)?.toString()?.toDoubleOrNull()
                val rowAmount = row.getOrNull(6)?.toString()?.replace(",", "")?.toDoubleOrNull()
                val existingUuid = uuidRows.getOrNull(i)?.firstOrNull()?.toString()?.trim().orEmpty()

                if (existingUuid.isBlank() && rowDate != null && rowTime != null && rowAmount != null) {
                    val dateMatches = rowDate == dateSerial
                    val amountMatches = Math.abs(rowAmount - amount) < 0.01
                    val rowSeconds = Math.round(rowTime * 86400.0)
                    val timeMatches = targetSeconds == rowSeconds
                    // A row typed by hand can share date, time and amount; account and description tell them apart
                    val accountMatches = account == null ||
                        row.getOrNull(2)?.toString().orEmpty().trim().equals(account.trim(), ignoreCase = true)
                    val descriptionMatches = description == null ||
                        row.getOrNull(3)?.toString().orEmpty().trim().equals(description.trim(), ignoreCase = true)
                    if (dateMatches && amountMatches && timeMatches && accountMatches && descriptionMatches) {
                        return@withContext Result.Success(i + 4)
                    }
                }
            }
            Result.Success(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Checks if [tabName] exists in [spreadsheetId]; if missing, provisions it.
     * Safe to repeat: if tab already exists, checks and repairs schema to V2 if needed,
     * returning Result.Success(false). If tab was newly provisioned, returns Result.Success(true).
     */
    suspend fun ensureMonthTabExists(
        spreadsheetId: String,
        tabName: String,
        accounts: List<String> = emptyList(),
        categories: List<String> = emptyList()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val tabsResult = getSheetTabsWithIds(spreadsheetId)
        if (tabsResult is Result.Error) return@withContext Result.Error(tabsResult.exception)
        val tabsMap = (tabsResult as Result.Success).data
        if (tabsMap.containsKey(tabName)) {
            return@withContext Result.Success(false)
        }
        val provResult = provisionMonthTab(spreadsheetId, tabName, accounts, categories)
        if (provResult is Result.Error) {
            // Check if another concurrent call created it
            val recheck = getSheetTabsWithIds(spreadsheetId)
            if (recheck is Result.Success && recheck.data.containsKey(tabName)) {
                return@withContext Result.Success(false)
            }
            return@withContext Result.Error(provResult.exception)
        }
        Result.Success(true)
    }

    /**
     * Checks if [tabName] has Column L Note header. If not, performs atomic schema repair
     * via insertDimension at index 11, writes L3 = "Note", updates merges and column properties,
     * verifies the upgraded structure, and records a counted diagnostics code.
     * Returns Result.Success(true) if repaired, Result.Success(false) if already up to date.
     */
    open suspend fun repairMonthTabSchema(
        spreadsheetId: String,
        tabName: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 1. Check header cell L3 first: skip if already "Note"
            val l3Res = getRangeValuesRaw(spreadsheetId, "'$tabName'!L3:L3")
            if (l3Res is Result.Success) {
                val headerVal = l3Res.data.firstOrNull()?.firstOrNull()?.toString()?.trim()
                if (headerVal.equals("Note", ignoreCase = true)) {
                    return@withContext Result.Success(false)
                }
            }

            // 2. Resolve sheetId
            val tabsMapRes = getSheetTabsWithIds(spreadsheetId)
            if (tabsMapRes is Result.Error) return@withContext Result.Error(tabsMapRes.exception)
            val sheetId = (tabsMapRes as Result.Success).data[tabName]
                ?: return@withContext Result.Error(SheetNotFoundException(spreadsheetId, "Tab '$tabName' not found in spreadsheet $spreadsheetId"))

            val requests = SheetProvisioner.buildRepairMonthTabSchemaRequests(sheetId, tabTitle = tabName)

            // Validate safety: must not shrink grid, write user values >= row 3, or use destructive operations
            val safetyViolations = SheetRepairSafetyValidator.validateRepairRequests(
                requests = requests,
                isDataTab = true
            )
            if (safetyViolations.isNotEmpty()) {
                return@withContext Result.Error(
                    IllegalStateException("Month tab schema repair safety violation: $safetyViolations")
                )
            }

            // Execute single atomic batchUpdate
            val batchRes = executeBatchUpdate(spreadsheetId, requests)
            if (batchRes is Result.Error) return@withContext Result.Error(batchRes.exception)

            // 3. Verification: Read back L3:AF3 to verify upgrade
            val verifyRes = getRangeValues(spreadsheetId, "'$tabName'!L3:AF3")
            if (verifyRes is Result.Error) {
                return@withContext Result.Error(
                    IllegalStateException("Post-repair verification read failed: ${verifyRes.exception.message}", verifyRes.exception)
                )
            }
            val headers = (verifyRes as Result.Success).data.firstOrNull() ?: emptyList()
            val l3 = headers.getOrNull(0)?.toString()?.trim().orEmpty()
            val m3 = headers.getOrNull(1)?.toString()?.trim().orEmpty()
            val ad3 = headers.getOrNull(18)?.toString()?.trim().orEmpty()
            val ae3 = headers.getOrNull(19)?.toString()?.trim().orEmpty()
            val af3 = headers.getOrNull(20)?.toString()?.trim().orEmpty()

            val isValid = l3.equals("Note", ignoreCase = true) &&
                    (m3.isEmpty() || m3.equals("Spacer 1", ignoreCase = true)) &&
                    (ad3.isEmpty() || ad3.contains("Debit", ignoreCase = true)) &&
                    (ae3.isEmpty() || ae3.contains("Inflow", ignoreCase = true)) &&
                    (af3.isEmpty() || af3.contains("Payable", ignoreCase = true))

            if (!isValid) {
                return@withContext Result.Error(
                    IllegalStateException("Month tab schema repair verification failed: L3='$l3', M3='$m3', AD3='$ad3', AE3='$ae3', AF3='$af3'")
                )
            }

            Diagnostics.record("GoogleSheetsClient", "MONTH_TAB_REPAIRED_V2")
            Result.Success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Updates original debit row status and remaining balance when a friend settles / repays debt.
     * Decrements Column J (Owed To Me) by [paidAmount]: remaining = max(0.0, currentOwed - paidAmount).
     * If remaining == 0.0, marks Column K (Status) = "Settled".
     * Uses valueInputOption=RAW.
     */
    suspend fun updateLinkedDebitSettlement(
        spreadsheetId: String,
        tabName: String,
        rowIndex: Int,
        paidAmount: Double,
        currentOwed: Double? = null
    ): Result<Double> = withContext(Dispatchers.IO) {
        try {
            require(rowIndex >= 4) { "Row index must be >= 4 (header occupies rows 1-3)" }
            require(paidAmount >= 0.0) { "Paid amount cannot be negative" }

            val activeOwed: Double = if (currentOwed != null) {
                currentOwed
            } else {
                val rangeJ = "'$tabName'!J$rowIndex"
                val readResult = getRangeValues(spreadsheetId, rangeJ)
                if (readResult is Result.Error) {
                    return@withContext Result.Error(readResult.exception)
                }
                val rows = (readResult as Result.Success).data
                val cellVal = rows.firstOrNull()?.firstOrNull()
                cellVal?.toString()?.toDoubleOrNull() ?: 0.0
            }

            val remaining = BigDecimal.valueOf(maxOf(0.0, activeOwed - paidAmount))
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()

            val newStatus = if (remaining == 0.0) {
                SheetSchemaConstants.STATUS_SETTLED
            } else {
                SheetSchemaConstants.STATUS_PENDING
            }

            val updateRange = "'$tabName'!J$rowIndex:K$rowIndex"
            val encodedRange = URLEncoder.encode(updateRange, "UTF-8")
            val url = "$baseUrl/$spreadsheetId/values/$encodedRange?valueInputOption=RAW"

            val valuesArray = JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive(remaining), JsonPrimitive(newStatus)))
            ))

            val requestBodyJson = buildJsonObject {
                put("range", JsonPrimitive(updateRange))
                put("majorDimension", JsonPrimitive("ROWS"))
                put("values", valuesArray)
            }.toString()

            val request = newRequestBuilder(url)
                .put(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "updateLinkedDebitSettlement failed", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_BATCH_UPDATE)
                    )
                }
                Result.Success(remaining)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Reads arbitrary cell or range values from [spreadsheetId] at [range] with
     * unformatted raw values and serial date/times for exact numerical comparisons.
     */
    suspend fun getRangeValuesRaw(
        spreadsheetId: String,
        range: String
    ): Result<List<List<Any?>>> = withContext(Dispatchers.IO) {
        try {
            val encodedRange = URLEncoder.encode(range, "UTF-8")
            val url = "$baseUrl/$spreadsheetId/values/$encodedRange?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER"
            val request = newRequestBuilder(url).get().build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "getRangeValuesRaw failed", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_GET)
                    )
                }

                val jsonResponse = try {
                    json.parseToJsonElement(bodyString).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse raw range values (code ${response.code})", e))
                }

                val valuesArray = jsonResponse["values"]?.jsonArray
                val resultList = valuesArray?.take(MAX_SHEET_ROWS_PER_CALL)?.map { rowElem ->
                    rowElem.jsonArray.map { cellElem ->
                        when (cellElem) {
                            is JsonPrimitive -> cellElem.content.take(MAX_CELL_STRING_LENGTH)
                            is JsonNull -> null
                            else -> cellElem.toString().take(MAX_CELL_STRING_LENGTH)
                        }
                    }
                } ?: emptyList()

                Result.Success(resultList)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Reads arbitrary cell or range values from [spreadsheetId] at [range].
     */
    suspend fun getRangeValues(
        spreadsheetId: String,
        range: String
    ): Result<List<List<Any?>>> = withContext(Dispatchers.IO) {
        try {
            val encodedRange = URLEncoder.encode(range, "UTF-8")
            val url = "$baseUrl/$spreadsheetId/values/$encodedRange"
            val request = newRequestBuilder(url).get().build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "getRangeValues failed", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_GET)
                    )
                }

                val jsonResponse = try {
                    json.parseToJsonElement(bodyString).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse raw range values (code ${response.code})", e))
                }

                val valuesArray = jsonResponse["values"]?.jsonArray
                val resultList = valuesArray?.take(MAX_SHEET_ROWS_PER_CALL)?.map { rowElem ->
                    rowElem.jsonArray.map { cellElem ->
                        when (cellElem) {
                            is JsonPrimitive -> cellElem.content.take(MAX_CELL_STRING_LENGTH)
                            is JsonNull -> null
                            else -> cellElem.toString().take(MAX_CELL_STRING_LENGTH)
                        }
                    }
                } ?: emptyList()

                Result.Success(resultList)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Reads multiple ranges in a single batchGet request from [spreadsheetId].
     * Uses valueRenderOption=UNFORMATTED_VALUE and dateTimeRenderOption=SERIAL_NUMBER by default
     * for exact numeric and temporal computations in accounting engines.
     */
    suspend fun batchGetValues(
        spreadsheetId: String,
        ranges: List<String>,
        valueRenderOption: String = "UNFORMATTED_VALUE",
        dateTimeRenderOption: String = "SERIAL_NUMBER"
    ): Result<Map<String, List<List<Any?>>>> = withContext(Dispatchers.IO) {
        try {
            if (ranges.isEmpty()) return@withContext Result.Success(emptyMap())

            val encodedRanges = ranges.joinToString("&") { "ranges=" + URLEncoder.encode(it, "UTF-8") }
            val url = "$baseUrl/$spreadsheetId/values:batchGet?$encodedRanges&valueRenderOption=$valueRenderOption&dateTimeRenderOption=$dateTimeRenderOption"

            val request = newRequestBuilder(url).get().build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "batchGetValues failed", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_BATCH_GET)
                    )
                }

                val jsonResponse = try {
                    json.parseToJsonElement(bodyString).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse batchGetValues response JSON (code ${response.code})", e))
                }

                val valueRanges = jsonResponse["valueRanges"]?.jsonArray.orEmpty()
                val resultMap = mutableMapOf<String, List<List<Any?>>>()

                for (vrElem in valueRanges) {
                    val vrObj = vrElem.jsonObject
                    val rangeName = vrObj["range"]?.jsonPrimitive?.content.orEmpty()
                    val valuesArray = vrObj["values"]?.jsonArray
                    val rowList = valuesArray?.take(MAX_SHEET_ROWS_PER_CALL)?.map { rowElem ->
                        rowElem.jsonArray.map { cellElem ->
                            when (cellElem) {
                                is JsonPrimitive -> cellElem.content.take(MAX_CELL_STRING_LENGTH)
                                is JsonNull -> null
                                else -> cellElem.toString().take(MAX_CELL_STRING_LENGTH)
                            }
                        }
                    } ?: emptyList()

                    resultMap[rangeName] = rowList
                }

                Result.Success(resultMap)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Executes batch updates across several ranges in [spreadsheetId] using valueInputOption=RAW.
     */
    suspend fun batchUpdateValues(
        spreadsheetId: String,
        updates: Map<String, List<List<Any?>>>,
        valueInputOption: String = "RAW"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        require(valueInputOption == "RAW") { "valueInputOption must strictly be RAW, received: $valueInputOption" }
        try {
            if (updates.isEmpty()) return@withContext Result.Success(Unit)

            val url = "$baseUrl/$spreadsheetId/values:batchUpdate"

            val dataArray = JsonArray(updates.map { (range, rows) ->
                buildJsonObject {
                    put("range", JsonPrimitive(range))
                    put("majorDimension", JsonPrimitive("ROWS"))
                    put("values", JsonArray(rows.map { row ->
                        JsonArray(row.map { it.toJsonElement() })
                    }))
                }
            })

            val requestBodyJson = buildJsonObject {
                put("valueInputOption", JsonPrimitive(valueInputOption))
                put("data", dataArray)
            }.toString()

            val request = newRequestBuilder(url)
                .post(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "batchUpdateValues failed", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_BATCH_UPDATE)
                    )
                }
                Result.Success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Updates an existing row in [tabName] at [rowIndex].
     * Uses valueInputOption=RAW for in-place modifications and voiding.
     */
    suspend fun updateRow(
        spreadsheetId: String,
        tabName: String,
        rowIndex: Int,
        row: List<Any?>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endColLetter = SheetSchemaConstants.columnLetter((row.size - 1).coerceIn(0, 30))
            val range = "'$tabName'!A$rowIndex:$endColLetter$rowIndex"
            val encodedRange = URLEncoder.encode(range, "UTF-8")
            val url = "$baseUrl/$spreadsheetId/values/$encodedRange?valueInputOption=RAW"

            val rowElements = JsonArray(row.map { it.toJsonElement() })
            val valuesArray = JsonArray(listOf(rowElements))

            val requestBodyJson = buildJsonObject {
                put("range", JsonPrimitive(range))
                put("majorDimension", JsonPrimitive("ROWS"))
                put("values", valuesArray)
            }.toString()

            val request = newRequestBuilder(url)
                .put(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "Sheets updateRow failed", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_UPDATE)
                    )
                }
                Result.Success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * DataResult variant for appendTransaction returning fine-grained sealed domain result types.
     */
    suspend fun appendTransactionDataResult(
        spreadsheetId: String,
        tabName: String,
        row: List<Any?>
    ): DataResult<Int> = when (val res = appendTransaction(spreadsheetId, tabName, row)) {
        is Result.Success -> DataResult.Success(res.data)
        is Result.Error -> res.exception.toDataResult(spreadsheetId)
        is Result.Loading -> DataResult.General("Loading")
    }

    /**
     * DataResult variant for getSheetMetadata returning fine-grained sealed domain result types.
     */
    suspend fun getSheetMetadataDataResult(
        spreadsheetId: String
    ): DataResult<List<String>> = when (val res = getSheetMetadata(spreadsheetId)) {
        is Result.Success -> DataResult.Success(res.data)
        is Result.Error -> res.exception.toDataResult(spreadsheetId)
        is Result.Loading -> DataResult.General("Loading")
    }

    /**
     * Executes a batchUpdate request against [spreadsheetId] and returns raw JSON response body.
     */
    private suspend fun executeBatchUpdate(
        spreadsheetId: String,
        requests: List<Map<String, Any?>>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/$spreadsheetId:batchUpdate"
            val jsonRequests = JsonArray(requests.map { it.toJsonObject() })
            val requestBodyJson = buildJsonObject {
                put("requests", jsonRequests)
            }.toString()

            val request = newRequestBuilder(url)
                .post(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "Sheets batchUpdate failed", spreadsheetId, GoogleEndpoint.SHEETS_BATCH_UPDATE)
                    )
                }
                Result.Success(bodyString)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Executes a batchUpdate request against [spreadsheetId] for formatting, styling, and schema provisioning.
     */
    suspend fun batchUpdate(
        spreadsheetId: String,
        requests: List<Map<String, Any?>>
    ): Result<Unit> = when (val res = executeBatchUpdate(spreadsheetId, requests)) {
        is Result.Success -> Result.Success(Unit)
        is Result.Error -> Result.Error(res.exception)
        is Result.Loading -> Result.Loading
    }

    /**
     * Appends additional rows or columns to [sheetId] using appendDimension batchUpdate request.
     * Used when the table grid is within 20 rows of full to ensure continuous append capability.
     */
    suspend fun appendDimension(
        spreadsheetId: String,
        sheetId: Int,
        dimension: String = "ROWS",
        length: Int = 100
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val request = mapOf(
            "appendDimension" to mapOf(
                "sheetId" to sheetId,
                "dimension" to dimension,
                "length" to length
            )
        )
        batchUpdate(spreadsheetId, listOf(request))
    }

    /**
     * Provisions a monthly ledger tab in [spreadsheetId] named [tabName].
     * Creates the tab if missing, extracts the sheetId, and formats headers,
     * banners, column styles, frozen rows, validations, and conditional formatting via SheetProvisioner.
     */
    open suspend fun provisionMonthTab(
        spreadsheetId: String,
        tabName: String,
        accounts: List<String> = emptyList(),
        categories: List<String> = emptyList()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val detailedRes = getSheetTabsDetailed(spreadsheetId)
            if (detailedRes is Result.Error) {
                return@withContext Result.Error(detailedRes.exception)
            }

            val detailedMap = (detailedRes as Result.Success).data
            val existingTabInfo = detailedMap[tabName]
            val existingSheetId = existingTabInfo?.sheetId

            val targetSheetId = if (existingSheetId != null) {
                existingSheetId
            } else {
                // Tab does not exist; create it via addSheet request
                val addSheetRequest = listOf(
                    mapOf(
                        "addSheet" to mapOf(
                            "properties" to mapOf(
                                "title" to tabName,
                                "gridProperties" to mapOf(
                                    "rowCount" to 1000,
                                    "columnCount" to SheetSchemaConstants.TOTAL_COLUMN_COUNT
                                )
                            )
                        )
                    )
                )

                val addSheetResult = executeBatchUpdate(spreadsheetId, addSheetRequest)
                if (addSheetResult is Result.Error) {
                    return@withContext Result.Error(addSheetResult.exception)
                }

                val responseBody = (addSheetResult as Result.Success).data
                val jsonResponse = try {
                    json.parseToJsonElement(responseBody).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse addSheet response JSON", e))
                }
                val replies = jsonResponse["replies"]?.jsonArray
                val firstReply = replies?.firstOrNull()?.jsonObject
                val addSheetReply = firstReply?.get("addSheet")?.jsonObject
                val newSheetId = addSheetReply?.get("properties")?.jsonObject?.get("sheetId")?.jsonPrimitive?.content?.toIntOrNull()

                if (newSheetId == null) {
                    return@withContext Result.Error(
                        MalformedResponseException("Failed to extract sheetId from addSheet reply")
                    )
                }
                newSheetId
            }

            val curRows = existingTabInfo?.rowCount ?: 1000
            val curCols = existingTabInfo?.columnCount ?: SheetSchemaConstants.TOTAL_COLUMN_COUNT
            val targetRows = maxOf(curRows, 1000)
            val targetCols = maxOf(curCols, SheetSchemaConstants.TOTAL_COLUMN_COUNT)
            val existingRules = existingTabInfo?.existingRuleFormulas ?: emptySet()

            val provisionRequests = SheetProvisioner.buildProvisionRequests(
                sheetId = targetSheetId,
                tabTitle = tabName,
                accounts = accounts,
                categories = categories,
                currentRowCount = targetRows,
                currentColumnCount = targetCols,
                existingRuleFormulas = existingRules
            )

            // Validate safety: must not shrink grid, write to row index >= 3, or use destructive operations
            val safetyViolations = SheetRepairSafetyValidator.validateRepairRequests(
                requests = provisionRequests,
                isDataTab = true,
                currentGridRows = curRows,
                currentGridCols = curCols
            )
            if (safetyViolations.isNotEmpty()) {
                return@withContext Result.Error(
                    IllegalStateException("Month tab repair safety violation: $safetyViolations")
                )
            }

            batchUpdate(spreadsheetId, provisionRequests)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Provisions the complete master workbook:
     * 1. Dashboard: Title banner, last updated formula, 6 KPI cards with living burn formulas,
     *    monthly summary table, category spend table, and active settlements query table.
     * 2. Active month tab (e.g. Nov26): Programmatically created with 31-column master ledger schema from code.
     */
    open suspend fun provisionMasterWorkbook(
        spreadsheetId: String,
        accounts: List<String> = emptyList(),
        categories: List<String> = emptyList()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tabsResult = getSheetTabsWithIds(spreadsheetId)
            if (tabsResult is Result.Error) {
                com.financeautomation.core.utils.Diagnostics.record("SheetsProvision", "Failed to query tabs: ${tabsResult.exception.message}")
                return@withContext Result.Error(tabsResult.exception)
            }
            var tabsMap = (tabsResult as Result.Success).data.toMutableMap()
            com.financeautomation.core.utils.Diagnostics.record("SheetsProvision", "Initial tabs: ${tabsMap.keys.toList()}")

            // Read detailed metadata to inspect existing grid sizes and conditional format rules
            val detailedTabsResult = getSheetTabsDetailed(spreadsheetId)
            val detailedTabs = if (detailedTabsResult is Result.Success) detailedTabsResult.data else emptyMap()

            // 0. Cosmetic properties (en_GB locale and Asia/Kolkata timezone) in their OWN isolated batch.
            // If this fails, record in Diagnostics and proceed; cosmetic properties must never block provisioning.
            try {
                val localeRequest = ChartManager.buildLocaleAndTimeZoneRequest("en_GB", "Asia/Kolkata")
                val localeRes = executeBatchUpdate(spreadsheetId, listOf(localeRequest))
                if (localeRes is Result.Error) {
                    com.financeautomation.core.utils.Diagnostics.record("SheetsLocale", "Locale batch skipped: ${localeRes.exception.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.financeautomation.core.utils.Diagnostics.record("SheetsLocale", "Locale batch error: ${e.javaClass.simpleName}")
            }

            val setupRequests = mutableListOf<Map<String, Any?>>()

            // 1. Rename Sheet1 to Dashboard if Sheet1 exists, or add Dashboard
            if (tabsMap.containsKey("Sheet1") && !tabsMap.containsKey("Dashboard")) {
                val sheet1Id = tabsMap["Sheet1"] ?: 0
                setupRequests.add(
                    mapOf(
                        "updateSheetProperties" to mapOf(
                            "properties" to mapOf(
                                "sheetId" to sheet1Id,
                                "title" to "Dashboard"
                            ),
                            "fields" to "title"
                        )
                    )
                )
            } else if (!tabsMap.containsKey("Dashboard")) {
                setupRequests.add(
                    mapOf(
                        "addSheet" to mapOf(
                            "properties" to mapOf(
                                "title" to "Dashboard",
                                "gridProperties" to mapOf("rowCount" to 1000, "columnCount" to 26)
                            )
                        )
                    )
                )
            }

            // 2. Ensure current month tab exists with at least 31 columns (never shrink existing rows or columns)
            val currentMonthTab = ZonedDateTime.now(DEFAULT_SHEET_ZONE_ID)
                .format(DateTimeFormatter.ofPattern("MMMyy", Locale.US))

            if (!tabsMap.containsKey(currentMonthTab)) {
                setupRequests.add(
                    mapOf(
                        "addSheet" to mapOf(
                            "properties" to mapOf(
                                "title" to currentMonthTab,
                                "gridProperties" to mapOf(
                                    "rowCount" to 1000,
                                    "columnCount" to SheetSchemaConstants.TOTAL_COLUMN_COUNT
                                )
                            )
                        )
                    )
                )
            } else {
                val monthSheetId = tabsMap[currentMonthTab]
                val monthInfo = detailedTabs[currentMonthTab]
                val curRows = monthInfo?.rowCount ?: 1000
                val curCols = monthInfo?.columnCount ?: SheetSchemaConstants.TOTAL_COLUMN_COUNT
                val targetRows = maxOf(curRows, 1000)
                val targetCols = maxOf(curCols, SheetSchemaConstants.TOTAL_COLUMN_COUNT)
                if (monthSheetId != null && (targetRows > curRows || targetCols > curCols)) {
                    setupRequests.add(
                        mapOf(
                            "updateSheetProperties" to mapOf(
                                "properties" to mapOf(
                                    "sheetId" to monthSheetId,
                                    "gridProperties" to mapOf(
                                        "rowCount" to targetRows,
                                        "columnCount" to targetCols
                                    )
                                ),
                                "fields" to "gridProperties(rowCount,columnCount)"
                            )
                        )
                    )
                }
            }

            if (setupRequests.isNotEmpty()) {
                val setupRes = executeBatchUpdate(spreadsheetId, setupRequests)
                if (setupRes is Result.Error) {
                    return@withContext Result.Error(setupRes.exception)
                }
                val refreshResult = getSheetTabsWithIds(spreadsheetId)
                if (refreshResult is Result.Success) {
                    tabsMap = refreshResult.data.toMutableMap()
                }
            }

            // 4. Format Dashboard sheet
            val dashboardId = tabsMap["Dashboard"]
            if (dashboardId != null) {
                // Deduplicate charts: check if charts with same titles exist and delete them before adding
                val existingChartsResult = ChartManager.getExistingChartsOnDashboard(
                    spreadsheetId = spreadsheetId,
                    dashboardSheetId = dashboardId,
                    okHttpClient = okHttpClient,
                    baseUrl = baseUrl,
                    tokenProvider = tokenProvider
                )
                val deleteRequests = if (existingChartsResult is Result.Success) {
                    ChartManager.buildDeleteChartRequests(
                        existingCharts = existingChartsResult.data,
                        titlesToDelete = setOf(
                            ChartManager.COLUMN_CHART_TITLE,
                            ChartManager.DOUGHNUT_CHART_TITLE
                        )
                    )
                } else {
                    emptyList()
                }

                if (deleteRequests.isNotEmpty()) {
                    val delRes = batchUpdate(spreadsheetId, deleteRequests)
                    if (delRes is Result.Error) {
                        return@withContext Result.Error(delRes.exception)
                    }
                }

                val dashInfo = detailedTabs["Dashboard"]
                val dashCurRows = dashInfo?.rowCount ?: 1000
                val dashCurCols = dashInfo?.columnCount ?: SheetSchemaConstants.TOTAL_COLUMN_COUNT
                val dashTargetRows = maxOf(dashCurRows, 1000)
                val dashTargetCols = maxOf(dashCurCols, SheetSchemaConstants.TOTAL_COLUMN_COUNT)

                val dashboardRequests = SheetProvisioner.buildDashboardRequests(
                    dashboardSheetId = dashboardId,
                    categories = categories,
                    currentRowCount = dashTargetRows,
                    currentColumnCount = dashTargetCols
                )
                val dashRes = batchUpdate(spreadsheetId, dashboardRequests)
                if (dashRes is Result.Error) {
                    return@withContext Result.Error(dashRes.exception)
                }
            }

            // 4. Format current month sheet (safe formatting: rows 1-3 only, skip existing rules, never shrink)
            val currentMonthId = tabsMap[currentMonthTab]
            if (currentMonthId != null) {
                val monthInfo = detailedTabs[currentMonthTab]
                val curRows = monthInfo?.rowCount ?: 1000
                val curCols = monthInfo?.columnCount ?: SheetSchemaConstants.TOTAL_COLUMN_COUNT
                val targetRows = maxOf(curRows, 1000)
                val targetCols = maxOf(curCols, SheetSchemaConstants.TOTAL_COLUMN_COUNT)
                val existingRules = monthInfo?.existingRuleFormulas ?: emptySet()

                val currentMonthRequests = SheetProvisioner.buildProvisionRequests(
                    sheetId = currentMonthId,
                    tabTitle = currentMonthTab,
                    accounts = accounts,
                    categories = categories,
                    currentRowCount = targetRows,
                    currentColumnCount = targetCols,
                    existingRuleFormulas = existingRules
                )

                val safetyViolations = SheetRepairSafetyValidator.validateRepairRequests(
                    requests = currentMonthRequests,
                    isDataTab = true,
                    currentGridRows = curRows,
                    currentGridCols = curCols
                )
                if (safetyViolations.isNotEmpty()) {
                    return@withContext Result.Error(
                        IllegalStateException("Month tab repair safety violation: $safetyViolations")
                    )
                }

                val monthRes = batchUpdate(spreadsheetId, currentMonthRequests)
                if (monthRes is Result.Error) {
                    return@withContext Result.Error(monthRes.exception)
                }
            }

            // 5. Ensure Subscriptions tab exists
            try {
                val subRes = ensureSubscriptionsTabExists(spreadsheetId, accounts)
                if (subRes is Result.Error) {
                    com.financeautomation.core.utils.Diagnostics.record("SheetsProvision", "Subscriptions tab skipped: ${subRes.exception.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.financeautomation.core.utils.Diagnostics.record("SheetsProvision", "Subscriptions tab error: ${e.javaClass.simpleName}")
            }

            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Detailed metadata for a sheet tab, including grid dimensions and existing conditional format formulas.
     */
    data class SheetTabInfo(
        val sheetId: Int,
        val title: String,
        val rowCount: Int = 1000,
        val columnCount: Int = SheetSchemaConstants.TOTAL_COLUMN_COUNT,
        val existingRuleFormulas: Set<String> = emptySet()
    )

    /**
     * Retrieves detailed metadata for all sheets in [spreadsheetId], including gridProperties
     * (rowCount, columnCount) and existing conditional format formulas.
     */
    open suspend fun getSheetTabsDetailed(spreadsheetId: String): Result<Map<String, SheetTabInfo>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/$spreadsheetId?fields=sheets(properties(sheetId%2Ctitle%2CgridProperties(rowCount%2CcolumnCount))%2CconditionalFormats(booleanRule(condition(values(userEnteredValue)))))"
            val request = newRequestBuilder(url).get().build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "Sheets getSheetTabsDetailed failed", spreadsheetId, GoogleEndpoint.SHEETS_GET)
                    )
                }

                val jsonResponse = try {
                    json.parseToJsonElement(bodyString).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse getSheetTabsDetailed response JSON (code ${response.code})", e))
                }
                val sheetsArray = jsonResponse["sheets"]?.jsonArray.orEmpty()
                val map = mutableMapOf<String, SheetTabInfo>()
                sheetsArray.forEach { sheetElem ->
                    val sheetObj = sheetElem.jsonObject
                    val props = sheetObj["properties"]?.jsonObject
                    val title = props?.get("title")?.jsonPrimitive?.content
                    val id = props?.get("sheetId")?.jsonPrimitive?.content?.toIntOrNull()
                    if (title != null && id != null) {
                        val gridProps = props["gridProperties"]?.jsonObject
                        val rowCount = gridProps?.get("rowCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 1000
                        val colCount = gridProps?.get("columnCount")?.jsonPrimitive?.content?.toIntOrNull() ?: SheetSchemaConstants.TOTAL_COLUMN_COUNT

                        val formulas = mutableSetOf<String>()
                        val cFormats = sheetObj["conditionalFormats"]?.jsonArray.orEmpty()
                        for (cf in cFormats) {
                            val boolRule = cf.jsonObject["booleanRule"]?.jsonObject
                            val cond = boolRule?.get("condition")?.jsonObject
                            val values = cond?.get("values")?.jsonArray.orEmpty()
                            for (v in values) {
                                val uev = v.jsonObject["userEnteredValue"]?.jsonPrimitive?.content
                                if (uev != null) formulas.add(uev)
                            }
                        }

                        map[title] = SheetTabInfo(
                            sheetId = id,
                            title = title,
                            rowCount = rowCount,
                            columnCount = colCount,
                            existingRuleFormulas = formulas
                        )
                    }
                }
                Result.Success(map)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Checks if any month tab in [spreadsheetId] contains data rows (any values in row 4 or below).
     */
    open suspend fun hasDataRows(spreadsheetId: String, tabTitles: List<String>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val monthRegex = Regex("^[A-Z][a-z]{2}\\d{2}$")
            val monthTabs = tabTitles.filter { it.matches(monthRegex) }
            if (monthTabs.isEmpty()) {
                return@withContext Result.Success(false)
            }
            val ranges = monthTabs.map { "'$it'!A4:A5" }
            return@withContext when (val batchRes = batchGetValues(spreadsheetId, ranges)) {
                is Result.Success -> {
                    val hasData = batchRes.data.values.any { rows -> rows.isNotEmpty() }
                    Result.Success(hasData)
                }
                is Result.Error -> Result.Error(batchRes.exception)
                else -> Result.Error(Exception("Unexpected batchGetValues state"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Ensures the visible Subscriptions tab exists in [spreadsheetId]; if missing, provisions it.
     */
    open suspend fun ensureSubscriptionsTabExists(
        spreadsheetId: String,
        accounts: List<String> = emptyList(),
        year: Int? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val tabsResult = getSheetTabsWithIds(spreadsheetId)
        if (tabsResult is Result.Error) return@withContext Result.Error(tabsResult.exception)
        val tabsMap = (tabsResult as Result.Success).data
        if (tabsMap.containsKey(SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME)) {
            return@withContext Result.Success(false)
        }

        val resolvedYear = year ?: DashboardFormulaBuilder.extractWorkbookYear(tabsMap.keys)

        val addReq = mapOf(
            "addSheet" to mapOf(
                "properties" to mapOf(
                    "title" to SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME,
                    "gridProperties" to mapOf("rowCount" to 500, "columnCount" to SheetSchemaConstants.SUBSCRIPTIONS_COLUMN_COUNT)
                )
            )
        )
        val addRes = executeBatchUpdate(spreadsheetId, listOf(addReq))
        if (addRes is Result.Error) return@withContext Result.Error(addRes.exception)

        val recheck = getSheetTabsWithIds(spreadsheetId)
        val newSheetId = if (recheck is Result.Success) {
            recheck.data[SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME]
        } else null
        val targetSheetId = newSheetId ?: 0

        val provRequests = SheetProvisioner.buildSubscriptionsRequests(targetSheetId, accounts, year = resolvedYear)
        val provRes = executeBatchUpdate(spreadsheetId, provRequests)
        if (provRes is Result.Error) return@withContext Result.Error(provRes.exception)

        Result.Success(true)
    }

    /**
     * Reads all rows from the Subscriptions tab ('Subscriptions'!A4:L).
     */
    open suspend fun readSubscriptionsTab(
        spreadsheetId: String
    ): Result<List<SheetSubscriptionRow>> = withContext(Dispatchers.IO) {
        try {
            val range = "'${SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME}'!${SheetSchemaConstants.SUBSCRIPTIONS_RANGE}"
            val res = getRangeValuesRaw(spreadsheetId, range)
            if (res is Result.Error) return@withContext Result.Error(res.exception)

            val rawRows = (res as Result.Success).data
            val result = mutableListOf<SheetSubscriptionRow>()
            for (i in rawRows.indices) {
                val row = rawRows[i]
                if (row.isEmpty()) continue
                val name = row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_NAME)?.toString()?.trim().orEmpty()
                val id = (row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_ID) ?: if (row.size == 10) row.getOrNull(9) else null)?.toString()?.trim()?.takeIf { it.isNotBlank() }
                if (name.isBlank() && id == null) continue

                val amount = row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_AMOUNT)?.toString()?.replace(",", "")?.toDoubleOrNull()
                val frequency = row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_FREQUENCY)?.toString()?.trim().orEmpty()
                val dueDate = row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_DUE_DATE)?.toString()?.trim().orEmpty()
                val account = row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_ACCOUNT)?.toString()?.trim().orEmpty()
                val active = row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_ACTIVE)?.toString()?.trim().equals("TRUE", ignoreCase = true)
                val note = row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_NOTE)?.toString()?.trim().orEmpty()
                val keywords = row.getOrNull(SheetSchemaConstants.COL_INDEX_SUB_KEYWORDS)?.toString()?.trim().orEmpty()

                result.add(
                    SheetSubscriptionRow(
                        rowNumber = i + SheetSchemaConstants.SUBSCRIPTIONS_DATA_START_ROW_INDEX + 1,
                        name = name,
                        amount = amount,
                        frequency = frequency,
                        dueDateString = dueDate,
                        account = account,
                        active = active,
                        note = note,
                        keywords = keywords,
                        id = id
                    )
                )
            }
            Result.Success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Writes all subscriptions into the Subscriptions tab ('Subscriptions'!A4:L).
     */
    open suspend fun writeSubscriptionsTab(
        spreadsheetId: String,
        subscriptions: List<Subscription>,
        accounts: List<String> = emptyList(),
        year: Int? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tabsResult = getSheetTabsWithIds(spreadsheetId)
            if (tabsResult is Result.Error) return@withContext Result.Error(tabsResult.exception)
            val tabsMap = (tabsResult as Result.Success).data

            val resolvedYear = year ?: DashboardFormulaBuilder.extractWorkbookYear(tabsMap.keys)
            ensureSubscriptionsTabExists(spreadsheetId, accounts, resolvedYear)

            val sheetId = tabsMap[SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME]
                ?: return@withContext Result.Error(SheetNotFoundException(spreadsheetId, "Subscriptions sheetId missing"))

            val rows = subscriptions.mapIndexed { idx, sub ->
                SheetProvisioner.buildSubscriptionRowValues(
                    sub = sub,
                    row1BasedIndex = idx + SheetSchemaConstants.SUBSCRIPTIONS_DATA_START_ROW_INDEX + 1,
                    year = resolvedYear
                )
            }

            if (rows.isEmpty()) {
                return@withContext Result.Success(Unit)
            }

            val rowValues = rows.map { rowCells ->
                mapOf(
                    "values" to rowCells.map { cell ->
                        when (cell) {
                            null -> mapOf("userEnteredValue" to mapOf("stringValue" to ""))
                            is Number -> mapOf("userEnteredValue" to mapOf("numberValue" to cell.toDouble()))
                            is Boolean -> mapOf("userEnteredValue" to mapOf("boolValue" to cell))
                            is String -> {
                                if (cell.startsWith("=")) {
                                    mapOf("userEnteredValue" to mapOf("formulaValue" to cell))
                                } else {
                                    mapOf("userEnteredValue" to mapOf("stringValue" to cell))
                                }
                            }
                            else -> mapOf("userEnteredValue" to mapOf("stringValue" to cell.toString()))
                        }
                    }
                )
            }

            val req = mapOf(
                "updateCells" to mapOf(
                    "start" to mapOf(
                        "sheetId" to sheetId,
                        "rowIndex" to SheetSchemaConstants.SUBSCRIPTIONS_DATA_START_ROW_INDEX,
                        "columnIndex" to 0
                    ),
                    "rows" to rowValues,
                    "fields" to "userEnteredValue"
                )
            )

            batchUpdate(spreadsheetId, listOf(req))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Retrieves titles and sheet IDs of all sheet tabs in [spreadsheetId].
     */
    open suspend fun getSheetTabsWithIds(spreadsheetId: String): Result<Map<String, Int>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/$spreadsheetId?fields=sheets(properties(sheetId%2Ctitle))"
            val request = newRequestBuilder(url).get().build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "Sheets getSheetTabsWithIds failed", spreadsheetId, GoogleEndpoint.SHEETS_GET)
                    )
                }

                val jsonResponse = try {
                    json.parseToJsonElement(bodyString).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse getSheetTabsWithIds response JSON (code ${response.code})", e))
                }
                val sheetsArray = jsonResponse["sheets"]?.jsonArray.orEmpty()
                val map = mutableMapOf<String, Int>()
                sheetsArray.forEach { sheet ->
                    val props = sheet.jsonObject["properties"]?.jsonObject
                    val title = props?.get("title")?.jsonPrimitive?.content
                    val id = props?.get("sheetId")?.jsonPrimitive?.content?.toIntOrNull()
                    if (title != null && id != null) {
                        map[title] = id
                    }
                }
                Result.Success(map)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Retrieves titles of all sheet tabs in [spreadsheetId].
     */
    suspend fun getSheetMetadata(spreadsheetId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/$spreadsheetId?fields=sheets(properties(sheetId%2Ctitle))"
            val request = newRequestBuilder(url).get().build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "Sheets getSheetMetadata failed", spreadsheetId, GoogleEndpoint.SHEETS_GET)
                    )
                }

                val jsonResponse = try {
                    json.parseToJsonElement(bodyString).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.Error(MalformedResponseException("Failed to parse getSheetMetadata response JSON (code ${response.code})", e))
                }
                val sheetsArray = jsonResponse["sheets"]?.jsonArray.orEmpty()
                val titles = sheetsArray.mapNotNull { sheet ->
                    sheet.jsonObject["properties"]?.jsonObject?.get("title")?.jsonPrimitive?.content
                }
                Result.Success(titles)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Reads a complete snapshot of all historical and active month tabs matching MmmYY
     * across [spreadsheetId], sorted oldest first by year then month.
     * Table 1 rows (A:L + AD) and Table 3 rows (W:AC + AF) are returned with their sheet row indices.
     */
    /** Reads the month tabs; [tabFilter] limits which ones (null reads all), so routine syncs can skip old months. */
    suspend fun readLedgerSnapshot(
        spreadsheetId: String,
        tabFilter: ((String) -> Boolean)? = null
    ): Result<LedgerWorkbookSnapshot> = withContext(Dispatchers.IO) {
        try {
            val tabsResult = getSheetTabsWithIds(spreadsheetId)
            if (tabsResult is Result.Error) return@withContext Result.Error(tabsResult.exception)
            val tabsMap = (tabsResult as Result.Success).data

            // Filter month tabs matching MmmYY (e.g. Nov26, Dec26, Jan27)
            val matchingTabs = tabsMap.keys.filter { MONTH_TAB_REGEX.matches(it) && (tabFilter?.invoke(it) ?: true) }
            if (matchingTabs.isEmpty()) {
                return@withContext Result.Success(LedgerWorkbookSnapshot(spreadsheetId, emptyList()))
            }

            // Sort oldest first by year then month
            val sortedTabs = matchingTabs.sortedBy { tabName ->
                val match = MONTH_TAB_REGEX.find(tabName)
                if (match != null) {
                    val mmm = match.groupValues[1].lowercase(Locale.US)
                    val yy = match.groupValues[2].toIntOrNull() ?: 0
                    val monthIdx = when (mmm) {
                        "jan" -> 1
                        "feb" -> 2
                        "mar" -> 3
                        "apr" -> 4
                        "may" -> 5
                        "jun" -> 6
                        "jul" -> 7
                        "aug" -> 8
                        "sep" -> 9
                        "oct" -> 10
                        "nov" -> 11
                        "dec" -> 12
                        else -> 0
                    }
                    val fullYear = 2000 + yy
                    fullYear * 12 + monthIdx
                } else {
                    0
                }
            }

            // Construct batch ranges for all sorted month tabs:
            // Table 1: 'Tab'!A4:L and 'Tab'!AD4:AD
            // Table 2: 'Tab'!N4:U and 'Tab'!AE4:AE
            // Table 3: 'Tab'!W4:AC and 'Tab'!AF4:AF
            val batchRanges = mutableListOf<String>()
            sortedTabs.forEach { tab ->
                batchRanges.add("'$tab'!A4:L")
                batchRanges.add("'$tab'!AD4:AD")
                batchRanges.add("'$tab'!N4:U")
                batchRanges.add("'$tab'!AE4:AE")
                batchRanges.add("'$tab'!W4:AC")
                batchRanges.add("'$tab'!AF4:AF")
            }

            val batchResult = batchGetValues(
                spreadsheetId = spreadsheetId,
                ranges = batchRanges,
                valueRenderOption = "UNFORMATTED_VALUE",
                dateTimeRenderOption = "SERIAL_NUMBER"
            )
            if (batchResult is Result.Error) return@withContext Result.Error(batchResult.exception)
            val valuesMap = (batchResult as Result.Success).data

            fun getValuesForRange(tab: String, colRange: String): List<List<Any?>> {
                return valuesMap["'$tab'!$colRange"]
                    ?: valuesMap["$tab!$colRange"]
                    ?: valuesMap.entries.firstOrNull { (k, _) ->
                        k.contains(tab, ignoreCase = true) && k.contains(colRange.substringBefore(":"), ignoreCase = true)
                    }?.value
                    ?: emptyList()
            }

            val monthSnapshots = mutableListOf<MonthTabSnapshot>()

            for (tab in sortedTabs) {
                val sheetId = tabsMap[tab] ?: 0
                val debitsData = getValuesForRange(tab, "A4:L").ifEmpty { getValuesForRange(tab, "A4:K") }
                val debitsUuids = getValuesForRange(tab, "AD4:AD").ifEmpty { getValuesForRange(tab, "AC4:AC") }
                val inflowsData = getValuesForRange(tab, "N4:U").ifEmpty { getValuesForRange(tab, "M4:T") }
                val inflowsUuids = getValuesForRange(tab, "AE4:AE").ifEmpty { getValuesForRange(tab, "AD4:AD") }
                val payablesData = getValuesForRange(tab, "W4:AC").ifEmpty { getValuesForRange(tab, "V4:AB") }
                val payablesUuids = getValuesForRange(tab, "AF4:AF").ifEmpty { getValuesForRange(tab, "AE4:AE") }

                val debitsList = mutableListOf<LedgerDebitRow>()
                for (i in debitsData.indices) {
                    val row = debitsData[i]
                    if (row.isEmpty()) continue
                    val dateSerial = row.getOrNull(0)?.toString()?.toDoubleOrNull()?.toInt()
                    val timeSerial = row.getOrNull(1)?.toString()?.toDoubleOrNull()
                    val account = row.getOrNull(2)?.toString().orEmpty()
                    val description = row.getOrNull(3)?.toString().orEmpty()
                    val tripTag = row.getOrNull(4)?.toString()?.takeIf { it.isNotBlank() }
                    val category = row.getOrNull(5)?.toString().orEmpty()
                    val totalAmount = row.getOrNull(6)?.toString()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                    val companions = row.getOrNull(7)?.toString().orEmpty()
                    val myShare = row.getOrNull(8)?.toString()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                    val owedToMe = row.getOrNull(9)?.toString()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                    val status = row.getOrNull(10)?.toString().orEmpty()
                    val note = row.getOrNull(11)?.toString().orEmpty()
                    val uuid = debitsUuids.getOrNull(i)?.firstOrNull()?.toString()?.trim()?.takeIf { it.isNotBlank() }

                    val isBlankRow = dateSerial == null && description.isBlank() && totalAmount == 0.0
                    if (!isBlankRow) {
                        debitsList.add(
                            LedgerDebitRow(
                                rowNumber = i + 4,
                                dateSerial = dateSerial,
                                timeSerial = timeSerial,
                                account = account,
                                description = description,
                                tripTag = tripTag,
                                category = category,
                                totalAmount = totalAmount,
                                companions = companions,
                                myShare = myShare,
                                owedToMe = owedToMe,
                                status = status,
                                uuid = uuid,
                                note = note
                            )
                        )
                    }
                }

                val inflowsList = mutableListOf<LedgerInflowRow>()
                for (i in inflowsData.indices) {
                    val row = inflowsData[i]
                    if (row.isEmpty()) continue
                    val dateSerial = row.getOrNull(0)?.toString()?.toDoubleOrNull()?.toInt()
                    val timeSerial = row.getOrNull(1)?.toString()?.toDoubleOrNull()
                    val account = row.getOrNull(2)?.toString().orEmpty()
                    val inflowType = row.getOrNull(3)?.toString().orEmpty()
                    val sender = row.getOrNull(4)?.toString().orEmpty()
                    val reason = row.getOrNull(5)?.toString().orEmpty()
                    val amountReceived = row.getOrNull(6)?.toString()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                    val linkedDebits = row.getOrNull(7)?.toString().orEmpty()
                    val uuid = inflowsUuids.getOrNull(i)?.firstOrNull()?.toString()?.trim()?.takeIf { it.isNotBlank() }

                    val isBlankRow = dateSerial == null && reason.isBlank() && amountReceived == 0.0
                    if (!isBlankRow) {
                        inflowsList.add(
                            LedgerInflowRow(
                                rowNumber = i + 4,
                                dateSerial = dateSerial,
                                timeSerial = timeSerial,
                                account = account,
                                inflowType = inflowType,
                                sender = sender,
                                reason = reason,
                                amountReceived = amountReceived,
                                linkedDebits = linkedDebits,
                                uuid = uuid
                            )
                        )
                    }
                }

                val payablesList = mutableListOf<LedgerPayableRow>()
                for (i in payablesData.indices) {
                    val row = payablesData[i]
                    if (row.isEmpty()) continue
                    val dateIncurred = row.getOrNull(0)?.toString()?.toDoubleOrNull()?.toInt()
                    val timeSerial = row.getOrNull(1)?.toString()?.toDoubleOrNull()
                    val paidBy = row.getOrNull(2)?.toString().orEmpty()
                    val whatFor = row.getOrNull(3)?.toString().orEmpty()
                    val amountIOwe = row.getOrNull(4)?.toString()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                    val dateRepaid = row.getOrNull(5)?.toString()?.toDoubleOrNull()?.toInt()
                    val repStatus = row.getOrNull(6)?.toString().orEmpty()
                    val uuid = payablesUuids.getOrNull(i)?.firstOrNull()?.toString()?.trim()?.takeIf { it.isNotBlank() }

                    val isBlankRow = dateIncurred == null && whatFor.isBlank() && amountIOwe == 0.0
                    if (!isBlankRow) {
                        payablesList.add(
                            LedgerPayableRow(
                                rowNumber = i + 4,
                                dateIncurredSerial = dateIncurred,
                                timeSerial = timeSerial,
                                paidBy = paidBy,
                                whatFor = whatFor,
                                amountIOwe = amountIOwe,
                                dateRepaidSerial = dateRepaid,
                                repaymentStatus = repStatus,
                                uuid = uuid
                            )
                        )
                    }
                }

                monthSnapshots.add(
                    MonthTabSnapshot(
                        tabName = tab,
                        sheetId = sheetId,
                        debits = debitsList,
                        payables = payablesList,
                        inflows = inflowsList
                    )
                )
            }

            Result.Success(LedgerWorkbookSnapshot(spreadsheetId, monthSnapshots))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun parseRowIndexFromRange(range: String): Int? {
        val cellPart = range.substringAfter("!")
        val match = ROW_INDEX_REGEX.find(cellPart)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        const val MAX_SHEET_ROWS_PER_CALL = 5000
        const val MAX_CELL_STRING_LENGTH = 5000

        fun Any?.toJsonElement(): JsonElement = when (this) {
            null -> JsonNull
            is Number -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            is String -> JsonPrimitive(this)
            is JsonElement -> this
            is Map<*, *> -> buildJsonObject {
                for ((k, v) in this@toJsonElement) {
                    put(k.toString(), v.toJsonElement())
                }
            }
            is List<*> -> JsonArray(this.map { it.toJsonElement() })
            else -> JsonPrimitive(this.toString())
        }

        fun Map<String, Any?>.toJsonObject(): JsonObject = buildJsonObject {
            for ((key, value) in this@toJsonObject) {
                put(key, value.toJsonElement())
            }
        }
    }
}
