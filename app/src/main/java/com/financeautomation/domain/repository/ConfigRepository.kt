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
     */
    suspend fun upsert(spreadsheetId: String, rows: List<ConfigRow>): Result<Unit>

    /**
     * Writes a tombstone row marking [type] and [key] as deleted.
     */
    suspend fun tombstone(spreadsheetId: String, type: String, key: String, deviceId: String): Result<Unit>
}

