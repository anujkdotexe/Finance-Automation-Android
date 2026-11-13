package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The theme choice is stored by name in the preference table and read back on the next start. */
class ThemePreferenceRoundTripTest {

    private class FakePreferenceDao : PreferenceDao {
        val rows = MutableStateFlow<Map<String, PreferenceEntity>>(emptyMap())
        override fun getPreferenceFlow(key: String): Flow<String?> = rows.map { it[key]?.value }
        override suspend fun getPreference(key: String): String? = rows.value[key]?.value
        override suspend fun getAllPreferences(): List<PreferenceEntity> = rows.value.values.toList()
        override suspend fun setPreference(entity: PreferenceEntity) { rows.value = rows.value + (entity.key to entity) }
        override suspend fun deletePreference(key: String) { rows.value = rows.value - key }
        override suspend fun clearAll() { rows.value = emptyMap() }
        override suspend fun getDirtyPreferences(): List<PreferenceEntity> = rows.value.values.filter { it.dirty == 1 }
        override suspend fun markClean(keys: List<String>) {}
    }

    private fun repo(dao: PreferenceDao) = PreferencesRepositoryImpl(preferenceDao = dao, ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun everyModeSurvivesAFreshRepositoryOnTheSameStorage() = runBlocking {
        val dao = FakePreferenceDao()
        for (mode in ThemeMode.entries) {
            repo(dao).setThemeMode(mode)
            val reopened = repo(dao)
            assertEquals(mode, reopened.getThemeModeSync())
            assertEquals(mode, reopened.getThemeMode().first())
        }
    }

    @Test
    fun theChoiceIsStoredByEnumName() = runBlocking {
        val dao = FakePreferenceDao()
        repo(dao).setThemeMode(ThemeMode.DARK)
        assertEquals("DARK", dao.rows.value.values.single().value)
    }

    @Test
    fun missingOrUnknownStoredValuesFallBackToSystem() = runBlocking {
        val dao = FakePreferenceDao()
        assertEquals(ThemeMode.SYSTEM, repo(dao).getThemeModeSync())
        dao.setPreference(PreferenceEntity(PreferencesRepositoryImpl.KEY_THEME_MODE, "PURPLE", updatedAt = 0L, dirty = 0))
        assertEquals(ThemeMode.SYSTEM, repo(dao).getThemeModeSync())
        assertEquals(ThemeMode.SYSTEM, repo(dao).getThemeMode().first())
    }

    @Test
    fun theFlowEmitsTheNewModeImmediatelyAfterAChange() = runBlocking {
        val dao = FakePreferenceDao()
        val repo = repo(dao)
        repo.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repo.getThemeMode().first())
        repo.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repo.getThemeMode().first())
    }
}
