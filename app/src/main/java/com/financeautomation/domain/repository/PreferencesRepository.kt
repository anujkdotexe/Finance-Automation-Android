package com.financeautomation.domain.repository

import com.financeautomation.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing persistent preferences and configuration.
 * Pure Kotlin contract with zero Android framework dependencies.
 */
interface PreferencesRepository {

    /**
     * Observes the active Google Sheets spreadsheet ID.
     */
    fun getSpreadsheetId(): Flow<String?>

    /**
     * Retrieves the active Google Sheets spreadsheet ID synchronously.
     */
    suspend fun getSpreadsheetIdSync(): String?

    /**
     * Updates the active Google Sheets spreadsheet ID.
     */
    suspend fun setSpreadsheetId(id: String)

    /**
     * Observes the active Google Sheets spreadsheet ID for a specific fiscal year.
     */
    fun getSpreadsheetIdForYear(year: Int): Flow<String?>

    /**
     * Retrieves the active Google Sheets spreadsheet ID for a specific fiscal year synchronously.
     */
    suspend fun getSpreadsheetIdForYearSync(year: Int): String?

    /**
     * Updates the active Google Sheets spreadsheet ID for a specific fiscal year.
     */
    suspend fun setSpreadsheetIdForYear(year: Int, id: String)

    /**
     * Observes the default payment funding account name.
     */
    fun getDefaultAccount(): Flow<String>

    /**
     * Retrieves the default payment funding account name synchronously.
     */
    suspend fun getDefaultAccountSync(): String

    /**
     * Updates the default payment funding account name.
     */
    suspend fun setDefaultAccount(account: String)

    /**
     * Observes the active trip or event tag name.
     */
    fun getActiveTrip(): Flow<String?>

    /**
     * Updates the active trip or event tag name.
     */
    suspend fun setActiveTrip(trip: String?)

    /**
     * Observes the authenticated Google account email.
     */
    fun getGoogleAccountEmail(): Flow<String?>

    /**
     * Retrieves the authenticated Google account email synchronously.
     */
    suspend fun getGoogleAccountEmailSync(): String?

    /**
     * Updates the authenticated Google account email.
     */
    suspend fun setGoogleAccountEmail(email: String?)

    /**
     * Observes the configurable settle delay in seconds (30s to 300s, default 120s).
     */
    fun getSettleDelaySeconds(): Flow<Int>

    /**
     * Retrieves the configurable settle delay in seconds synchronously.
     */
    suspend fun getSettleDelaySecondsSync(): Int

    /**
     * Updates the configurable settle delay in seconds.
     */
    suspend fun setSettleDelaySeconds(seconds: Int)

    /**
     * Retrieves or generates a persistent device UUID.
     */
    suspend fun getOrCreateDeviceId(): String

    /**
     * Retrieves the stored device UUID synchronously, or null if not yet created.
     */
    suspend fun getDeviceIdSync(): String?

    /**
     * Observes whether developer diagnostics mode is enabled (default false).
     */
    fun getDeveloperMode(): Flow<Boolean>

    /**
     * Retrieves developer diagnostics mode status synchronously.
     */
    suspend fun getDeveloperModeSync(): Boolean

    /**
     * Updates developer diagnostics mode status.
     */
    suspend fun setDeveloperMode(enabled: Boolean)

    /**
     * Observes the active UI theme mode (SYSTEM, LIGHT, DARK, default SYSTEM).
     */
    fun getThemeMode(): Flow<ThemeMode>

    /**
     * Retrieves the active UI theme mode synchronously.
     */
    suspend fun getThemeModeSync(): ThemeMode

    /**
     * Updates the active UI theme mode.
     */
    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * Retrieves an arbitrary preference value synchronously.
     */
    suspend fun getPreference(key: String): String?

    /**
     * Persists an arbitrary preference value with optional dirty tracking.
     */
    suspend fun setPreference(key: String, value: String?, isDirty: Boolean = true)

    /**
     * Deletes a preference by key.
     */
    suspend fun deletePreference(key: String)
}
