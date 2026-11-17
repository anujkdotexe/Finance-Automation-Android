package com.financeautomation.core.constants

/**
 * Constants governing the key-value settings layout in the hidden Config tab.
 * Schema v1: Type | Key | Value1 | Value2 | Value3 | Value4 | UpdatedAt | UpdatedBy | Deleted
 */
object ConfigConstants {
    const val CONFIG_TAB_NAME = "Config"
    const val CONFIG_SCHEMA_VERSION = "1"

    // Row Types
    const val TYPE_META = "meta"
    const val TYPE_ACCOUNT = "account"
    const val TYPE_CATEGORY = "category"
    const val TYPE_PERSON = "person"
    const val TYPE_TRIP = "trip"
    const val TYPE_PREF = "pref"
    const val TYPE_SUBSCRIPTION = "subscription"

    /** Monthly category budget: key = category name, Value1 = amount. */
    const val TYPE_BUDGET = "budget"

    // Limits for what is read from the Config tab. The tab can be edited by hand or by another device, so nothing in it
    // is trusted to be small or clean.
    /** Rows requested from the Config tab (header and meta rows included). Rows below this are not read. */
    const val MAX_CONFIG_ROWS = 2000
    const val MAX_KEY_LENGTH = 80
    const val MAX_VALUE_LENGTH = 400

    /** A subscription travels as one JSON object in Value1 (name, keywords and more), so it gets a larger limit. */
    const val MAX_SUBSCRIPTION_VALUE_LENGTH = 2000

    // Meta Keys
    const val KEY_SCHEMA_VERSION = "schemaVersion"

    // Preference keys that stay strictly on device and are never pushed or pulled
    val LOCAL_ONLY_PREF_KEYS: Set<String> = setOf(
        "device_id",
        "account_email",
        "active_account_email",
        "key_google_email",
        "key_google_display_name",
        "key_google_photo_url",
        "spreadsheet_id",
        "key_spreadsheet_id",
        "google_account",
        "drive_folder_id",
        "is_onboarding_completed",
        "setup_health_results",
        "widget_state",
        "permissions_granted",
        "oem_setup_completed",
        "developer_mode",
        "key_developer_mode",
        "theme_mode",
        "key_theme_mode",
        "app_lock_enabled",
        "hide_recents_preview",
        "key_workbook_requeue_pending_year",
        "key_workbook_replaced_notice_pending"
    )

    fun isLocalOnlyPref(key: String): Boolean {
        return key in LOCAL_ONLY_PREF_KEYS ||
                key.startsWith("key_spreadsheet_id") ||
                key.startsWith("spreadsheet_id") ||
                key.startsWith("pref_spreadsheet_id")
    }
}
