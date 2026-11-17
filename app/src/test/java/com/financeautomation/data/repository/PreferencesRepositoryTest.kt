package com.financeautomation.data.repository

import com.financeautomation.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun settleDelayFromSheetIsClamped() = runBlocking {
        val dao = com.financeautomation.testutil.InMemoryPreferenceDao()
        val repo = PreferencesRepositoryImpl(preferenceDao = dao, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val key = PreferencesRepositoryImpl.KEY_SETTLE_DELAY_SECONDS
        for ((stored, expected) in listOf("0" to 30, "5" to 30, "99999999" to 300, "-50" to 30, "180" to 180, "abc" to 120)) {
            dao.setPreference(com.financeautomation.data.local.entity.PreferenceEntity(key, stored, updatedAt = 1L, dirty = 0))
            assertEquals("stored '$stored'", expected, repo.getSettleDelaySecondsSync())
            assertEquals("stored '$stored' (flow)", expected, repo.getSettleDelaySeconds().first())
        }
    }
}
