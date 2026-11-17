package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.testutil.InMemoryPreferenceDao
import com.financeautomation.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The theme choice is stored by name in the preference table and read back on the next start. */
class ThemePreferenceRoundTripTest {


    private fun repo(dao: PreferenceDao) = PreferencesRepositoryImpl(preferenceDao = dao, ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun everyModeSurvivesAFreshRepositoryOnTheSameStorage() = runBlocking {
        val dao = InMemoryPreferenceDao()
        for (mode in ThemeMode.entries) {
            repo(dao).setThemeMode(mode)
            val reopened = repo(dao)
            assertEquals(mode, reopened.getThemeModeSync())
            assertEquals(mode, reopened.getThemeMode().first())
        }
    }

    @Test
    fun theChoiceIsStoredByEnumName() = runBlocking {
        val dao = InMemoryPreferenceDao()
        repo(dao).setThemeMode(ThemeMode.DARK)
        assertEquals("DARK", dao.rows.value.values.single().value)
    }

    @Test
    fun missingOrUnknownStoredValuesFallBackToSystem() = runBlocking {
        val dao = InMemoryPreferenceDao()
        assertEquals(ThemeMode.SYSTEM, repo(dao).getThemeModeSync())
        dao.setPreference(PreferenceEntity(PreferencesRepositoryImpl.KEY_THEME_MODE, "PURPLE", updatedAt = 0L, dirty = 0))
        assertEquals(ThemeMode.SYSTEM, repo(dao).getThemeModeSync())
        assertEquals(ThemeMode.SYSTEM, repo(dao).getThemeMode().first())
    }

    @Test
    fun theFlowEmitsTheNewModeImmediatelyAfterAChange() = runBlocking {
        val dao = InMemoryPreferenceDao()
        val repo = repo(dao)
        repo.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repo.getThemeMode().first())
        repo.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repo.getThemeMode().first())
    }
}
