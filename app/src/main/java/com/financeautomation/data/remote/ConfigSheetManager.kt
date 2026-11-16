package com.financeautomation.data.remote

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion as DomainCompanion
import com.financeautomation.domain.model.TripTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Thrown when the hidden Config tab is absent from the spreadsheet or contains no data rows.
 */
class EmptyOrMissingConfigTabException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Encapsulates full configuration state snapshot for cloud mirroring and restore.
 * Strictly contains ZERO financial data (zero amounts, zero merchant descriptions, zero timestamps).
 */
data class AppConfigSnapshot(
    val accounts: List<Account>,
    val categories: List<Category>,
    val companions: List<DomainCompanion>,
    val tripTags: List<TripTag>,
    val preferences: Map<String, String>
)

/**
 * Manages the hidden 'Config' tab in Google Sheets (range A1:P50).
 * Mirrors application configuration to cloud and restores it on fresh install.
 */
class ConfigSheetManager(
    private val googleSheetsClient: GoogleSheetsClient,
    private val okHttpClient: OkHttpClient,
    private val tokenProvider: TokenProvider? = null,
    private val baseUrl: String = "https://sheets.googleapis.com/v4/spreadsheets",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
     * Ensures the 'Config' tab exists with properties.hidden = true.
     */
    suspend fun ensureConfigTabExists(spreadsheetId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val metadataResult = googleSheetsClient.getSheetMetadata(spreadsheetId)
            if (metadataResult is Result.Error) {
                return@withContext Result.Error(metadataResult.exception)
            }

            val existingTabs = (metadataResult as Result.Success).data
            if (!existingTabs.contains(CONFIG_TAB_NAME)) {
                val addSheetRequest = listOf(
                    mapOf(
                        "addSheet" to mapOf(
                            "properties" to mapOf(
                                "title" to CONFIG_TAB_NAME,
                                "hidden" to true,
                                "gridProperties" to mapOf(
                                    "rowCount" to 50,
                                    "columnCount" to 16
                                )
                            )
                        )
                    )
                )
                val batchRes = googleSheetsClient.batchUpdate(spreadsheetId, addSheetRequest)
                if (batchRes is Result.Error) {
                    return@withContext Result.Error(batchRes.exception)
                }
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Mirrors the entire configuration snapshot into 'Config'!A1:P50.
     * Contains ZERO financial data.
     */
    suspend fun mirrorConfig(
        spreadsheetId: String,
        snapshot: AppConfigSnapshot
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val ensureRes = ensureConfigTabExists(spreadsheetId)
            if (ensureRes is Result.Error) {
                return@withContext Result.Error(ensureRes.exception)
            }

            val rows = buildConfigGrid(snapshot)

            val range = "'$CONFIG_TAB_NAME'!A1:P50"
            val encodedRange = URLEncoder.encode(range, "UTF-8")
            val url = "$baseUrl/$spreadsheetId/values/$encodedRange?valueInputOption=RAW"

            val jsonRows = JsonArray(
                rows.map { row ->
                    JsonArray(row.map { cell ->
                        if (cell == null) JsonNull else JsonPrimitive(cell.toString())
                    })
                }
            )

            val requestBodyJson = buildJsonObject {
                put("range", JsonPrimitive(range))
                put("majorDimension", JsonPrimitive("ROWS"))
                put("values", jsonRows)
            }.toString()

            val request = newRequestBuilder(url)
                .put(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        createHttpException(response.code, bodyString, "mirrorConfig failed", spreadsheetId, GoogleEndpoint.SHEETS_VALUES_UPDATE)
                    )
                }
                Result.Success(Unit)
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Reads 'Config'!A1:P50 and reconstructs the configuration snapshot.
     */
    suspend fun readConfig(
        spreadsheetId: String
    ): Result<AppConfigSnapshot> = withContext(ioDispatcher) {
        try {
            val rangeResult = googleSheetsClient.getRangeValues(spreadsheetId, "'$CONFIG_TAB_NAME'!A1:P50")
            if (rangeResult is Result.Error) {
                val err = rangeResult.exception
                val msg = err.message.orEmpty()
                val isTabMissing = err is SheetNotFoundException ||
                    (err is GoogleSheetsApiException && err.statusCode == 400 && (msg.contains("unable to parse range", ignoreCase = true) || msg.contains("range not found", ignoreCase = true)))
                if (isTabMissing) {
                    return@withContext Result.Error(EmptyOrMissingConfigTabException("Config tab not present in spreadsheet", err))
                }
                return@withContext Result.Error(err)
            }

            val rawGrid = (rangeResult as Result.Success).data
            if (rawGrid.isEmpty() || rawGrid.size < 2) {
                return@withContext Result.Error(
                    EmptyOrMissingConfigTabException("Config tab is empty or missing headers.")
                )
            }

            val snapshot = parseConfigGrid(rawGrid)
            Result.Success(snapshot)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Builds the 50x16 2D matrix representing the configuration table.
     * Row 0: Headers
     * Rows 1..49: Entries
     */
    fun buildConfigGrid(snapshot: AppConfigSnapshot): List<List<Any?>> {
        val grid = mutableListOf<List<Any?>>()

        // Row 1: Headers
        val headers = listOf(
            "AccountName", "AccountLastFour", "AccountIsDefault", "AccountIsArchived", "AccountOrder",
            "CategoryName", "CategoryExcludedFromBurn", "CategoryIsDefault", "CategoryIsArchived", "CategoryOrder",
            "CompanionName", "CompanionOrder",
            "TripTagName", "TripTagIsActive",
            "PrefKey", "PrefValue"
        )
        grid.add(headers)

        val prefList = snapshot.preferences.toList()

        for (i in 0 until 49) {
            val row = MutableList<Any?>(16) { "" }

            // Accounts: Cols 0..4
            if (i < snapshot.accounts.size) {
                val acc = snapshot.accounts[i]
                row[0] = acc.name
                row[1] = acc.lastFourDigits.orEmpty()
                row[2] = acc.isDefault.toString()
                row[3] = acc.isArchived.toString()
                row[4] = acc.displayOrder.toString()
            }

            // Categories: Cols 5..9
            if (i < snapshot.categories.size) {
                val cat = snapshot.categories[i]
                row[5] = cat.name
                row[6] = cat.isExcludedFromLivingBurn.toString()
                row[7] = cat.isDefault.toString()
                row[8] = cat.isArchived.toString()
                row[9] = cat.displayOrder.toString()
            }

            // Companions: Cols 10..11
            if (i < snapshot.companions.size) {
                val comp = snapshot.companions[i]
                row[10] = comp.name
                row[11] = i.toString()
            }

            // TripTags: Cols 12..13
            if (i < snapshot.tripTags.size) {
                val tag = snapshot.tripTags[i]
                row[12] = tag.name
                row[13] = tag.isActive.toString()
            }

            // Preferences: Cols 14..15
            if (i < prefList.size) {
                val (k, v) = prefList[i]
                row[14] = k
                row[15] = v
            }

            grid.add(row)
        }

        return grid
    }

    /**
     * Parses the 50x16 2D matrix into domain objects.
     */
    fun parseConfigGrid(grid: List<List<Any?>>): AppConfigSnapshot {
        val accounts = mutableListOf<Account>()
        val categories = mutableListOf<Category>()
        val companions = mutableListOf<DomainCompanion>()
        val tripTags = mutableListOf<TripTag>()
        val preferences = mutableMapOf<String, String>()

        for (rowIndex in 1 until grid.size) {
            val row = grid[rowIndex]

            // Account (Cols 0..4)
            val accName = row.getOrNull(0)?.toString()?.trim().orEmpty()
            if (accName.isNotBlank() && !accName.equals("AccountName", ignoreCase = true)) {
                val lastFour = row.getOrNull(1)?.toString()?.trim()?.ifEmpty { null }
                val isDefault = row.getOrNull(2)?.toString()?.trim()?.toBooleanStrictOrNull() ?: false
                val isArchived = row.getOrNull(3)?.toString()?.trim()?.toBooleanStrictOrNull() ?: false
                val order = row.getOrNull(4)?.toString()?.trim()?.toIntOrNull() ?: (rowIndex - 1)
                accounts.add(
                    Account(
                        id = "acc_${rowIndex}",
                        name = accName,
                        lastFourDigits = lastFour,
                        isDefault = isDefault,
                        isArchived = isArchived,
                        displayOrder = order
                    )
                )
            }

            // Category (Cols 5..9)
            val catName = row.getOrNull(5)?.toString()?.trim().orEmpty()
            if (catName.isNotBlank() && !catName.equals("CategoryName", ignoreCase = true)) {
                val isBurnExcluded = row.getOrNull(6)?.toString()?.trim()?.toBooleanStrictOrNull() ?: false
                val isDefault = row.getOrNull(7)?.toString()?.trim()?.toBooleanStrictOrNull() ?: false
                val isArchived = row.getOrNull(8)?.toString()?.trim()?.toBooleanStrictOrNull() ?: false
                val order = row.getOrNull(9)?.toString()?.trim()?.toIntOrNull() ?: (rowIndex - 1)
                categories.add(
                    Category(
                        id = "cat_${rowIndex}",
                        name = catName,
                        isDefault = isDefault,
                        isExcludedFromLivingBurn = isBurnExcluded,
                        isArchived = isArchived,
                        displayOrder = order
                    )
                )
            }

            // Companion (Cols 10..11)
            val compName = row.getOrNull(10)?.toString()?.trim().orEmpty()
            if (compName.isNotBlank() && !compName.equals("CompanionName", ignoreCase = true)) {
                companions.add(
                    DomainCompanion(
                        id = "comp_${rowIndex}",
                        name = compName
                    )
                )
            }

            // TripTag (Cols 12..13)
            val tagName = row.getOrNull(12)?.toString()?.trim().orEmpty()
            if (tagName.isNotBlank() && !tagName.equals("TripTagName", ignoreCase = true)) {
                val isActive = row.getOrNull(13)?.toString()?.trim()?.toBooleanStrictOrNull() ?: false
                tripTags.add(
                    TripTag(
                        id = "tag_${rowIndex}",
                        name = tagName,
                        isActive = isActive
                    )
                )
            }

            // Preferences (Cols 14..15)
            val prefKey = row.getOrNull(14)?.toString()?.trim().orEmpty()
            val prefValue = row.getOrNull(15)?.toString()?.trim().orEmpty()
            if (prefKey.isNotBlank() && !prefKey.equals("PrefKey", ignoreCase = true)) {
                preferences[prefKey] = prefValue
            }
        }

        return AppConfigSnapshot(
            accounts = accounts,
            categories = categories,
            companions = companions,
            tripTags = tripTags,
            preferences = preferences
        )
    }

    companion object {
        const val CONFIG_TAB_NAME: String = "Config"
    }
}
