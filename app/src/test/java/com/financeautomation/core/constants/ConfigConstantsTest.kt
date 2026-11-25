package com.financeautomation.core.constants

import com.financeautomation.domain.repository.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The preference keys that travel through the Config tab must be the keys the app really saves. */
class ConfigConstantsTest {

    @Test
    fun theAllowListUsesTheKeysThePreferencesRepositorySaves() {
        assertEquals(
            setOf(
                PreferencesRepository.KEY_DEFAULT_ACCOUNT,
                PreferencesRepository.KEY_ACTIVE_TRIP,
                PreferencesRepository.KEY_SETTLE_DELAY_SECONDS,
                PreferencesRepository.KEY_SUBSCRIPTION_REMINDER_TIME,
                PreferencesRepository.DISMISSED_RECURRING_KEY
            ),
            ConfigConstants.SYNCED_PREF_KEYS
        )
    }

    @Test
    fun consentSecurityAndUnknownKeysAreNeverSynced() {
        val keys = listOf(
            "crash_reports_enabled", "usage_counts_enabled", "crashlytics_opt_in", "app_lock_enabled",
            "hide_recents_preview", "key_spreadsheet_id", "some_future_setting", ""
        )
        for (key in keys) {
            assertFalse("'$key' must not be set from the sheet", ConfigConstants.isSyncedPref(key))
        }
    }
}
