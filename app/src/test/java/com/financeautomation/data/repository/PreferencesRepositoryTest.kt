package com.financeautomation.data.repository

import com.financeautomation.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.financeautomation.domain.repository.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesRepositoryTest {

    @Test
    fun testDeveloperMode_defaultsToFalse_andPersistsValue() = runBlocking {
        val repo = PreferencesRepositoryImpl(isTestHeadless = true)
        assertFalse(repo.getDeveloperModeSync())
        assertFalse(repo.getDeveloperMode().first())

        repo.setDeveloperMode(true)
        assertTrue(repo.getDeveloperModeSync())
        assertTrue(repo.getDeveloperMode().first())

        repo.setDeveloperMode(false)
        assertFalse(repo.getDeveloperModeSync())
        assertFalse(repo.getDeveloperMode().first())
    }

    @Test
    fun testThemeMode_defaultsToSystem_andPersistsValue() = runBlocking {
        val repo = PreferencesRepositoryImpl(isTestHeadless = true)
        assertEquals(ThemeMode.SYSTEM, repo.getThemeModeSync())
        assertEquals(ThemeMode.SYSTEM, repo.getThemeMode().first())

        repo.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repo.getThemeModeSync())
        assertEquals(ThemeMode.DARK, repo.getThemeMode().first())

        repo.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repo.getThemeModeSync())
        assertEquals(ThemeMode.LIGHT, repo.getThemeMode().first())
    }

    @Test
    fun testClearKeysWithPrefix_removesMatchingKeys_andPreservesOthers() = runBlocking {
        val repo = PreferencesRepositoryImpl(isTestHeadless = true)
        val key2026 = PreferencesRepository.keyLastWorkbookVerifiedDate(2026)
        val key2027 = PreferencesRepository.keyLastWorkbookVerifiedDate(2027)
        val otherKey = "${PreferencesRepository.KEY_LAST_WORKBOOK_VERIFIED_DATE_PREFIX}different"
        val sheetKey = PreferencesRepository.KEY_SPREADSHEET_ID

        repo.setPreference(key2026, "2026-11-17")
        repo.setPreference(key2027, "2027-01-01")
        repo.setPreference(otherKey, "other")
        repo.setPreference(sheetKey, "sheet_id_123")

        repo.clearKeysWithPrefix(PreferencesRepository.KEY_LAST_WORKBOOK_VERIFIED_DATE_PREFIX)

        assertNull(repo.getPreference(key2026))
        assertNull(repo.getPreference(key2027))
        assertNull(repo.getPreference(otherKey))
        assertEquals("sheet_id_123", repo.getPreference(sheetKey))
    }
}

