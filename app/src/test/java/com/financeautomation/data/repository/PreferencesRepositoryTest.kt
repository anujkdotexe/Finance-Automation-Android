package com.financeautomation.data.repository

import com.financeautomation.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        repo.setPreference("key_last_workbook_verified_date_2026", "2026-11-17")
        repo.setPreference("key_last_workbook_verified_date_2027", "2027-01-01")
        repo.setPreference("key_last_workbook_verified_different", "other")
        repo.setPreference("key_spreadsheet_id", "sheet_id_123")

        repo.clearKeysWithPrefix("key_last_workbook_verified_date_")

        assertNull(repo.getPreference("key_last_workbook_verified_date_2026"))
        assertNull(repo.getPreference("key_last_workbook_verified_date_2027"))
        assertEquals("other", repo.getPreference("key_last_workbook_verified_different"))
        assertEquals("sheet_id_123", repo.getPreference("key_spreadsheet_id"))
    }
}

