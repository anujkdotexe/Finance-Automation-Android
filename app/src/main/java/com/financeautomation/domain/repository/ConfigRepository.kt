package com.financeautomation.domain.repository

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.ConfigRow

/**
 * Domain repository interface for managing the hidden Google Sheets Config tab.
 * Pure Kotlin contract with zero Android framework dependencies.
 */
interface ConfigRepository {

    /**
     * Reads all configuration rows from 'Config'!A1:I in [spreadsheetId].
     */
    suspend fun readAll(spreadsheetId: String): Result<List<ConfigRow>>

    /**
     * Upserts [rows] into the Config tab: updates existing rows in place or appends new rows.
     * A row is skipped when the sheet already holds a newer version of the same item. Returns the composite keys
     * ([ConfigRow.compositeKey]) of the rows that were really written, so callers clear their dirty flags only for those.
     */
    suspend fun upsert(spreadsheetId: String, rows: List<ConfigRow>): Result<Set<String>>

    /**
     * Writes a tombstone row marking [type] and [key] as deleted.
     */
    suspend fun tombstone(spreadsheetId: String, type: String, key: String, deviceId: String): Result<Unit>
}

