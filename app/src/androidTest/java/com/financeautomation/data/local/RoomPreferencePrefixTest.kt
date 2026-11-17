package com.financeautomation.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test verifying exact prefix matching for deletePreferencesWithPrefix
 * on real SQLite Room database, ensuring LIKE wildcard underscore collision is prevented.
 */
@RunWith(AndroidJUnit4::class)
class RoomPreferencePrefixTest {

    private lateinit var db: FinanceDatabase
    private lateinit var preferenceDao: PreferenceDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferenceDao = db.preferenceDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun deletePreferencesWithPrefix_deletesMatchingPrefix_andPreservesNonMatching() = runBlocking {
        val now = System.currentTimeMillis()
        preferenceDao.setPreference(PreferenceEntity("key_last_workbook_verified_date_2026", "true", now, 0))
        preferenceDao.setPreference(PreferenceEntity("key_last_workbook_verified_date_2027", "true", now, 0))
        preferenceDao.setPreference(PreferenceEntity("key_spreadsheet_id", "sheet_123", now, 0))

        preferenceDao.deletePreferencesWithPrefix("key_last_workbook_verified_date_")

        assertNull(preferenceDao.getPreference("key_last_workbook_verified_date_2026"))
        assertNull(preferenceDao.getPreference("key_last_workbook_verified_date_2027"))
        assertEquals("sheet_123", preferenceDao.getPreference("key_spreadsheet_id"))
    }

    @Test
    fun deletePreferencesWithPrefix_doesNotTreatUnderscoreAsWildcard() = runBlocking {
        val now = System.currentTimeMillis()
        val prefix = "test_pref_"

        preferenceDao.setPreference(PreferenceEntity("test_pref_1", "val1", now, 0))
        preferenceDao.setPreference(PreferenceEntity("testXpref_2", "val2", now, 0))
        preferenceDao.setPreference(PreferenceEntity("test_other_3", "val3", now, 0))

        preferenceDao.deletePreferencesWithPrefix(prefix)

        assertNull(preferenceDao.getPreference("test_pref_1"))
        assertNotNull(preferenceDao.getPreference("testXpref_2"))
        assertEquals("val2", preferenceDao.getPreference("testXpref_2"))
        assertNotNull(preferenceDao.getPreference("test_other_3"))
        assertEquals("val3", preferenceDao.getPreference("test_other_3"))
    }
}
