package com.financeautomation.service

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.testutil.FakePendingCaptureDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-5: the account the user chose as default in Settings is the account a captured payment lands in. */
class DefaultAccountCaptureTest {

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

    @Test
    fun aCapturedPaymentLandsInTheDefaultAccountChosenInSettings() = runBlocking {
        val prefDao = FakePreferenceDao()
        // Settings writes the default account through the production repository
        PreferencesRepositoryImpl(preferenceDao = prefDao, ioDispatcher = Dispatchers.Unconfined).setDefaultAccount("Salary Card")
        val captures = FakePendingCaptureDao()
        val processor = NotificationProcessor(pendingCaptureDao = captures, preferenceDao = prefDao)

        val outcome = processor.process(
            PackageWhitelist.PACKAGE_GPAY, "Paid ₹349 to Swiggy", "UPI transaction", postTime = 1_800_000_000_000L, sbnKey = "k1"
        )

        assertTrue("expected a capture but was $outcome", outcome is NotificationProcessingOutcome.Captured)
        assertEquals("Salary Card", captures.captures.values.single().inferredAccount)
    }

    @Test
    fun withNoDefaultAccountChosenTheCaptureIsFilledAsOther() = runBlocking {
        val captures = FakePendingCaptureDao()
        val processor = NotificationProcessor(pendingCaptureDao = captures, preferenceDao = FakePreferenceDao())
        processor.process(
            PackageWhitelist.PACKAGE_GPAY, "Paid ₹349 to Swiggy", "UPI transaction", postTime = 1_800_000_000_000L, sbnKey = "k2"
        )
        assertEquals("Other", captures.captures.values.single().inferredAccount)
    }
}
