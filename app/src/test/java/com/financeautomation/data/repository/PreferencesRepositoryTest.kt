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
}

