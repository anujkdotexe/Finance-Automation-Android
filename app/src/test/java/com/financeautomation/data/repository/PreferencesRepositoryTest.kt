package com.financeautomation.data.repository

import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import com.financeautomation.domain.repository.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun requeueMarkerIsNeverMarkedDirty() = runBlocking {
        assertTrue(
            "KEY_WORKBOOK_REQUEUE_PENDING_YEAR must be in local-only keys",
            ConfigConstants.isLocalOnlyPref(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR)
        )
        assertTrue(
            "KEY_WORKBOOK_REPLACED_NOTICE_PENDING must be in local-only keys",
            ConfigConstants.isLocalOnlyPref(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING)
        )

        var recordedEntity: PreferenceEntity? = null
        val fakeDao = object : PreferenceDao {
            override fun getPreferenceFlow(key: String): Flow<String?> = flowOf(null)
            override suspend fun getPreference(key: String): String? = null
            override suspend fun getAllPreferences(): List<PreferenceEntity> = emptyList()
            override suspend fun setPreference(entity: PreferenceEntity) {
                recordedEntity = entity
            }
            override suspend fun deletePreference(key: String) {}
            override suspend fun deletePreferencesWithPrefix(prefix: String) {}
            override suspend fun clearAll() {}
            override suspend fun getDirtyPreferences(): List<PreferenceEntity> = emptyList()
            override suspend fun markClean(keys: List<String>) {}
        }
        val repo = PreferencesRepositoryImpl(preferenceDao = fakeDao, ioDispatcher = Dispatchers.Unconfined)

        repo.setPreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR, "2026", isDirty = true)
        assertNotNull(recordedEntity)
        assertEquals("Dirty flag must be 0 for local-only requeue marker", 0, recordedEntity?.dirty)

        repo.setPreference(PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING, "true", isDirty = true)
        assertNotNull(recordedEntity)
        assertEquals("Dirty flag must be 0 for local-only notice flag", 0, recordedEntity?.dirty)
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
