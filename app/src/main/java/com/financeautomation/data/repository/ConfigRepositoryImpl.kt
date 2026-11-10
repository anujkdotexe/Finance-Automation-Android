package com.financeautomation.data.repository

import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.safeCall
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.domain.model.ConfigRow
import com.financeautomation.domain.repository.ConfigRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Implementation of [ConfigRepository] reading and writing to the hidden Google Sheets Config tab.
 * Schema v1:
 * Row 1: Type | Key | Value1 | Value2 | Value3 | Value4 | UpdatedAt | UpdatedBy | Deleted
 * Row 2: meta | schemaVersion | 1
 * Row 3+: Items (account, category, person, trip, pref)
 */
class ConfigRepositoryImpl(
    private val googleSheetsClient: GoogleSheetsClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ConfigRepository {

    override suspend fun readAll(spreadsheetId: String): Result<List<ConfigRow>> = withContext(ioDispatcher) {
        safeCall {
            ensureConfigTabInitialized(spreadsheetId)

            val rangeResult = googleSheetsClient.getRangeValuesRaw(spreadsheetId, "'${ConfigConstants.CONFIG_TAB_NAME}'!A1:I")
            if (rangeResult is Result.Error) {
                throw rangeResult.exception
            }

            val rows = (rangeResult as Result.Success).data
            if (rows.isEmpty() || rows.size < 2) {
                return@safeCall emptyList<ConfigRow>()
            }

            val configRows = mutableListOf<ConfigRow>()

            // Row index is 1-based; row 1 is header, row 2 is meta, data rows start at row 3 (index 2)
            for (i in 1 until rows.size) {
                val rowCells = rows[i]
                if (rowCells.isEmpty()) continue

                val type = rowCells.getOrNull(0)?.toString()?.trim().orEmpty()
                val key = rowCells.getOrNull(1)?.toString()?.trim().orEmpty()
                if (type.isBlank() || key.isBlank()) continue

                val v1 = rowCells.getOrNull(2)?.toString().orEmpty()
                val v2 = rowCells.getOrNull(3)?.toString().orEmpty()
                val v3 = rowCells.getOrNull(4)?.toString().orEmpty()
                val v4 = rowCells.getOrNull(5)?.toString().orEmpty()
                val updatedStr = rowCells.getOrNull(6)?.toString().orEmpty()
                val updatedBy = rowCells.getOrNull(7)?.toString().orEmpty()
                val deletedStr = rowCells.getOrNull(8)?.toString()?.trim().orEmpty()

                val updatedAt = try {
                    if (updatedStr.isNotBlank()) Instant.parse(updatedStr) else Instant.EPOCH
                } catch (e: DateTimeParseException) {
                    Diagnostics.record("ConfigRepositoryImpl", "parseUpdatedAt: ${e.javaClass.simpleName}")
                    Instant.EPOCH
                }

                val isDeleted = deletedStr.equals("true", ignoreCase = true)

                configRows.add(
                    ConfigRow(
                        type = type,
                        key = key,
                        values = listOf(v1, v2, v3, v4),
                        updatedAt = updatedAt,
                        updatedBy = updatedBy,
                        deleted = isDeleted
                    )
                )
            }

            configRows
        }
    }

    override suspend fun upsert(spreadsheetId: String, rows: List<ConfigRow>): Result<Unit> = withContext(ioDispatcher) {
        safeCall {
            if (rows.isEmpty()) return@safeCall

            ensureConfigTabInitialized(spreadsheetId)

            // Read existing rows to map composite keys to row indices
            val rangeResult = googleSheetsClient.getRangeValuesRaw(spreadsheetId, "'${ConfigConstants.CONFIG_TAB_NAME}'!A1:B")
            val existingCells = if (rangeResult is Result.Success) rangeResult.data else emptyList()

            val keyToRowMap = mutableMapOf<String, Int>()
            for (i in existingCells.indices) {
                val row = existingCells[i]
                val type = row.getOrNull(0)?.toString()?.trim()?.lowercase().orEmpty()
                val key = row.getOrNull(1)?.toString()?.trim()?.lowercase().orEmpty()
                if (type.isNotBlank() && key.isNotBlank()) {
                    keyToRowMap["$type|$key"] = i + 1 // 1-based row number
                }
            }

            val updatesMap = mutableMapOf<String, List<List<Any?>>>()
            val newRowsToAppend = mutableListOf<List<Any?>>()

            for (row in rows) {
                val compositeKey = row.compositeKey
                val existingRowIndex = keyToRowMap[compositeKey]
                if (existingRowIndex != null) {
                    // Update in-place
                    updatesMap["'${ConfigConstants.CONFIG_TAB_NAME}'!A$existingRowIndex:I$existingRowIndex"] = listOf(row.toRowCells())
                } else {
                    // New row to append
                    newRowsToAppend.add(row.toRowCells())
                }
            }

            if (updatesMap.isNotEmpty()) {
                val updateRes = googleSheetsClient.batchUpdateValues(spreadsheetId, updatesMap, valueInputOption = "RAW")
                if (updateRes is Result.Error) throw updateRes.exception
            }

            for (appendCells in newRowsToAppend) {
                val appendRes = googleSheetsClient.appendTableScoped(
                    spreadsheetId = spreadsheetId,
                    tabName = ConfigConstants.CONFIG_TAB_NAME,
                    tableRange = "A:I",
                    row = appendCells,
                    valueInputOption = "RAW"
                )
                if (appendRes is Result.Error) throw appendRes.exception
            }
        }
    }

    override suspend fun tombstone(
        spreadsheetId: String,
        type: String,
        key: String,
        deviceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        safeCall {
            val tombstoneRow = ConfigRow(
                type = type,
                key = key,
                values = listOf("", "", "", ""),
                updatedAt = Instant.now(),
                updatedBy = deviceId,
                deleted = true
            )
            val res = upsert(spreadsheetId, listOf(tombstoneRow))
            if (res is Result.Error) throw res.exception
        }
    }

    private suspend fun ensureConfigTabInitialized(spreadsheetId: String) {
        val tabsResult = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
        val tabs = if (tabsResult is Result.Success) tabsResult.data else emptyMap()

        if (!tabs.containsKey(ConfigConstants.CONFIG_TAB_NAME)) {
            // Create tab Config and hide it
            val addSheetRequest = listOf(
                mapOf(
                    "addSheet" to mapOf(
                        "properties" to mapOf(
                            "title" to ConfigConstants.CONFIG_TAB_NAME,
                            "hidden" to true
                        )
                    )
                )
            )
            googleSheetsClient.batchUpdate(spreadsheetId, addSheetRequest)

            // Write Row 1 header and Row 2 metadata
            val initialRows = listOf(
                listOf("Type", "Key", "Value1", "Value2", "Value3", "Value4", "UpdatedAt", "UpdatedBy", "Deleted"),
                listOf(ConfigConstants.TYPE_META, ConfigConstants.KEY_SCHEMA_VERSION, ConfigConstants.CONFIG_SCHEMA_VERSION, "", "", "", Instant.now().toString(), "system", "")
            )
            val initUpdate = mapOf(
                "'${ConfigConstants.CONFIG_TAB_NAME}'!A1:I2" to initialRows
            )
            googleSheetsClient.batchUpdateValues(spreadsheetId, initUpdate, valueInputOption = "RAW")
        }
    }
}

