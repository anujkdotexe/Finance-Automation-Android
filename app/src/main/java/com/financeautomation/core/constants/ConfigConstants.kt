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

    // Meta Keys
    const val KEY_SCHEMA_VERSION = "schemaVersion"

    // Preference keys that stay strictly on device and are never pushed or pulled
    val LOCAL_ONLY_PREF_KEYS: Set<String> = setOf(
        "device_id",
        "account_email",
        "active_account_email",
        "key_google_email",
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
        "key_theme_mode"
    )

    fun isLocalOnlyPref(key: String): Boolean {
        return key in LOCAL_ONLY_PREF_KEYS ||
                key.startsWith("key_spreadsheet_id") ||
                key.startsWith("spreadsheet_id") ||
                key.startsWith("pref_spreadsheet_id")
    }
}
