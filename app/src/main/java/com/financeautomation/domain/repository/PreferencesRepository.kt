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
     * Observes the display title of the active spreadsheet workbook.
     */
    fun getSpreadsheetTitle(): Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

    /**
     * Retrieves the display title of the active spreadsheet workbook synchronously.
     */
    suspend fun getSpreadsheetTitleSync(): String? = null

    /**
     * Updates the display title of the active spreadsheet workbook.
     */
    suspend fun setSpreadsheetTitle(title: String) {}

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
     * Observes the authenticated Google account photo URL.
     */
    fun getGoogleAccountPhotoUrl(): Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

    /**
     * Retrieves the authenticated Google account photo URL synchronously.
     */
    suspend fun getGoogleAccountPhotoUrlSync(): String? = null

    /**
     * Updates the authenticated Google account photo URL.
     */
    suspend fun setGoogleAccountPhotoUrl(url: String?) {}

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

    /** Whether the first-run dismissible onboarding hint on Dashboard has been dismissed (default false). */
    suspend fun isDashboardHintDismissed(): Boolean = getPreference(KEY_DASHBOARD_HINT_DISMISSED) == "true"

    suspend fun setDashboardHintDismissed(dismissed: Boolean) {
        setPreference(KEY_DASHBOARD_HINT_DISMISSED, dismissed.toString(), isDirty = false)
    }

    /** Whether the first-run dismissible onboarding hint on Needs Review has been dismissed (default false). */
    suspend fun isNeedsReviewHintDismissed(): Boolean = getPreference(KEY_NEEDS_REVIEW_HINT_DISMISSED) == "true"

    suspend fun setNeedsReviewHintDismissed(dismissed: Boolean) {
        setPreference(KEY_NEEDS_REVIEW_HINT_DISMISSED, dismissed.toString(), isDirty = false)
    }

    /** Whether the first-run onboarding accounts setup step has been seen or completed (default false). */
    suspend fun isOnboardingAccountsSeen(): Boolean = getPreference(KEY_ONBOARDING_ACCOUNTS_SEEN) == "true"

    suspend fun setOnboardingAccountsSeen(seen: Boolean) {
        setPreference(KEY_ONBOARDING_ACCOUNTS_SEEN, seen.toString(), isDirty = false)
    }

    /**
     * Observes whether a notice should be displayed alerting the user that their
     * workbook was replaced (e.g. because it was trashed or missing in Drive).
     */
    fun getWorkbookReplacedNoticePending(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    /** Set of package IDs that have been explicitly disabled by the user in capture settings. */
    suspend fun getDisabledPackages(): Set<String> =
        getPreference(KEY_DISABLED_PACKAGES)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    suspend fun setDisabledPackages(packages: Set<String>) {
        setPreference(KEY_DISABLED_PACKAGES, packages.joinToString(","), isDirty = false)
    }

    /** Set of custom package IDs that the user explicitly added to watch. */
    suspend fun getUserWatchedPackages(): Set<String> =
        getPreference(KEY_USER_WATCHED_PACKAGES)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    suspend fun setUserWatchedPackages(packages: Set<String>) {
        setPreference(KEY_USER_WATCHED_PACKAGES, packages.joinToString(","), isDirty = false)
    }

    suspend fun clearKeysWithPrefix(prefix: String) {}

    companion object {
        const val KEY_DISABLED_PACKAGES: String = "disabled_packages"
        const val KEY_USER_WATCHED_PACKAGES: String = "user_watched_packages"
        const val KEY_WORKBOOK_REPLACED_NOTICE_PENDING: String = "key_workbook_replaced_notice_pending"
        const val KEY_WORKBOOK_REQUEUE_PENDING_YEAR: String = "key_workbook_requeue_pending_year"
        /** Id of the workbook that last acknowledged this device's synced rows; reconcile prunes only against it. */
        const val KEY_LEDGER_CONFIRMED_WORKBOOK_ID: String = "key_ledger_confirmed_workbook_id"
        /** Entries removed locally because the sheet no longer holds them, not yet shown to the user. */
        const val KEY_SHEET_PRUNED_PENDING_COUNT: String = "key_sheet_pruned_pending_count"
        /** "1" while a reconcile held back a bulk removal because many entries are missing from the sheet. */
        const val KEY_SHEET_MANY_MISSING_FLAG: String = "key_sheet_many_missing_flag"
        /** Month (yyyy-MM) whose own debt carry last reached the sheet. Local to this device. */
        const val KEY_CARRY_DONE_MONTH: String = "key_carry_done_month"
        /** Year whose January carry, from the previous year's workbook, reached the sheet. Local to this device. */
        const val KEY_CARRY_YEAR_OPENED: String = "key_carry_year_opened"
        /** Reason (enum name) of a void the sheet refused during sync, not yet shown to the user. */
        const val KEY_VOID_BLOCKED_NOTICE: String = "key_void_blocked_notice"
        const val KEY_SPREADSHEET_ID: String = "key_spreadsheet_id"
        fun keySpreadsheetIdForYear(year: Int): String = "key_spreadsheet_id_$year"
        const val KEY_LAST_WORKBOOK_VERIFIED_DATE_PREFIX: String = "last_workbook_verified_date_"
        fun keyLastWorkbookVerifiedDate(year: Int): String = "$KEY_LAST_WORKBOOK_VERIFIED_DATE_PREFIX$year"
        /** Set while a workbook that was found has not yet given this device its Config. */
        fun keyConfigPullPending(spreadsheetId: String): String = "config_pull_pending_$spreadsheetId"
        const val KEY_SPREADSHEET_TITLE: String = "key_spreadsheet_title"
        const val KEY_DEFAULT_ACCOUNT: String = "key_default_account"
        const val KEY_ACTIVE_TRIP: String = "key_active_trip"
        const val KEY_GOOGLE_EMAIL: String = "key_google_email"
        const val KEY_GOOGLE_DISPLAY_NAME: String = "key_google_display_name"
        const val KEY_GOOGLE_PHOTO_URL: String = "key_google_photo_url"
        const val KEY_SETTLE_DELAY_SECONDS: String = "key_settle_delay_seconds"
        const val KEY_DEVICE_ID: String = "device_id"
        const val KEY_DEVELOPER_MODE: String = "key_developer_mode"
        const val KEY_THEME_MODE: String = "key_theme_mode"

        const val KEY_DASHBOARD_HINT_DISMISSED: String = "dashboard_hint_dismissed"
        const val KEY_NEEDS_REVIEW_HINT_DISMISSED: String = "needs_review_hint_dismissed"
        const val KEY_ONBOARDING_ACCOUNTS_SEEN: String = "onboarding_accounts_seen"
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
