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
     * Observes the authenticated Google account display name.
     */
    fun getGoogleAccountDisplayName(): Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

    /**
     * Retrieves the authenticated Google account display name synchronously.
     */
    suspend fun getGoogleAccountDisplayNameSync(): String? = null

    /**
     * Updates the authenticated Google account display name.
     */
    suspend fun setGoogleAccountDisplayName(name: String?) {}

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

    /** Whether the optional app lock is on (default false). Stays on this device only. */
    suspend fun getAppLockEnabled(): Boolean = getPreference(KEY_APP_LOCK_ENABLED) == "true"

    suspend fun setAppLockEnabled(enabled: Boolean) {
        setPreference(KEY_APP_LOCK_ENABLED, enabled.toString(), isDirty = false)
    }

    /** Whether windows hide their content in screenshots and the recent apps list (default false). */
    suspend fun getHideRecentsPreview(): Boolean = getPreference(KEY_HIDE_RECENTS_PREVIEW) == "true"

    suspend fun setHideRecentsPreview(enabled: Boolean) {
        setPreference(KEY_HIDE_RECENTS_PREVIEW, enabled.toString(), isDirty = false)
    }

    /**
     * Keys of recurring payment suggestions the owner dismissed (the normalised description of the payment).
     * Stored as one comma separated preference so it also travels with the Config tab mirror.
     */
    suspend fun getDismissedRecurring(): Set<String> =
        getPreference(DISMISSED_RECURRING_KEY).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Adds [key] to the dismissed recurring suggestions. Blank keys and keys with a comma are ignored. */
    suspend fun addDismissedRecurring(key: String) {
        val clean = key.trim()
        if (clean.isEmpty() || clean.contains(",")) return
        val updated = getDismissedRecurring() + clean
        setPreference(DISMISSED_RECURRING_KEY, updated.sorted().joinToString(","))
    }

    /**
     * Observes the local time (HH:mm, Asia/Kolkata) at which subscription reminders run (default 21:00).
     * The default implementation is built on [getPreference]; implementations backed by a database override it.
     */
    fun getSubscriptionReminderTime(): Flow<String> = kotlinx.coroutines.flow.flow {
        emit(getSubscriptionReminderTimeSync())
    }

    /** Retrieves the subscription reminder time (HH:mm), falling back to the default when unset or invalid. */
    suspend fun getSubscriptionReminderTimeSync(): String =
        normalizeReminderTime(getPreference(KEY_SUBSCRIPTION_REMINDER_TIME))

    /** Updates the subscription reminder time. Invalid values are ignored. */
    suspend fun setSubscriptionReminderTime(time: String) {
        val normalized = parseReminderTime(time) ?: return
        setPreference(KEY_SUBSCRIPTION_REMINDER_TIME, normalized, isDirty = true)
    }

    companion object {
        const val KEY_SUBSCRIPTION_REMINDER_TIME: String = "subscription_reminder_time"
        const val DISMISSED_RECURRING_KEY: String = "dismissed_recurring"
        const val KEY_APP_LOCK_ENABLED: String = "app_lock_enabled"
        const val KEY_HIDE_RECENTS_PREVIEW: String = "hide_recents_preview"
        const val DEFAULT_SUBSCRIPTION_REMINDER_TIME: String = "21:00"

        /** Returns the zero padded HH:mm form of [raw], or null when it is not a valid time. */
        fun parseReminderTime(raw: String?): String? {
            val parts = raw?.trim()?.split(":") ?: return null
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return "%02d:%02d".format(h, m)
        }

        fun normalizeReminderTime(raw: String?): String =
            parseReminderTime(raw) ?: DEFAULT_SUBSCRIPTION_REMINDER_TIME
    }
}
