package com.financeautomation.data.repository

import android.content.Context
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.data.local.FinanceDatabase
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository implementation of [PreferencesRepository].
 * Backed primarily by Room SQLite [PreferenceDao] in preferences_table
 * with reactive Flow observation.
 */
class PreferencesRepositoryImpl(
    private val preferenceDao: PreferenceDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PreferencesRepository {

    constructor(context: Context?) : this(
        preferenceDao = context?.let {
            try {
                FinanceDatabase.getInstance(it).preferenceDao()
            } catch (_: Exception) {
                null
            }
        }
    )

    // In-memory fallbacks when running headless without Room database
    private val memSpreadsheetId = MutableStateFlow<String?>(null)
    private val memDefaultAccount = MutableStateFlow(CategoryConstants.ACCOUNT_SBI)
    private val memActiveTrip = MutableStateFlow<String?>(null)
    private val memGoogleEmail = MutableStateFlow<String?>(null)
    private val memSettleDelay = MutableStateFlow(DEFAULT_SETTLE_DELAY_SECONDS)
    private val memDeviceId = MutableStateFlow<String?>(null)
    private val memGenericPrefs = ConcurrentHashMap<String, String>()

    var configSyncCoordinator: ConfigSyncCoordinator? = null

    override fun getSpreadsheetId(): Flow<String?> {
        val dao = preferenceDao ?: return memSpreadsheetId.asStateFlow()
        return dao.getPreferenceFlow(KEY_SPREADSHEET_ID)
    }

    override suspend fun getSpreadsheetIdSync(): String? = withContext(ioDispatcher) {
        val dao = preferenceDao ?: return@withContext memSpreadsheetId.value
        dao.getPreference(KEY_SPREADSHEET_ID)
    }

    override suspend fun setSpreadsheetId(id: String): Unit = withContext(ioDispatcher) {
        val trimmed = id.trim()
        val dao = preferenceDao
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_SPREADSHEET_ID, trimmed, updatedAt = System.currentTimeMillis(), dirty = 0))
        } else {
            memSpreadsheetId.value = trimmed
        }
    }

    override fun getSpreadsheetIdForYear(year: Int): Flow<String?> {
        val dao = preferenceDao ?: return memSpreadsheetId.asStateFlow()
        return dao.getPreferenceFlow(keySpreadsheetIdForYear(year)).map { yearSpecificId ->
            yearSpecificId ?: dao.getPreference(KEY_SPREADSHEET_ID)
        }
    }

    override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = withContext(ioDispatcher) {
        val dao = preferenceDao ?: return@withContext memSpreadsheetId.value
        val yearSpecific = dao.getPreference(keySpreadsheetIdForYear(year))
        if (!yearSpecific.isNullOrBlank()) {
            yearSpecific
        } else {
            dao.getPreference(KEY_SPREADSHEET_ID)
        }
    }

    override suspend fun setSpreadsheetIdForYear(year: Int, id: String): Unit = withContext(ioDispatcher) {
        val trimmed = id.trim()
        val dao = preferenceDao
        if (dao != null) {
            val now = System.currentTimeMillis()
            dao.setPreference(PreferenceEntity(keySpreadsheetIdForYear(year), trimmed, updatedAt = now, dirty = 0))
            dao.setPreference(PreferenceEntity(KEY_SPREADSHEET_ID, trimmed, updatedAt = now, dirty = 0))
        } else {
            memSpreadsheetId.value = trimmed
        }
    }

    override fun getDefaultAccount(): Flow<String> {
        val dao = preferenceDao ?: return memDefaultAccount.asStateFlow()
        return dao.getPreferenceFlow(KEY_DEFAULT_ACCOUNT).map { it ?: CategoryConstants.ACCOUNT_SBI }
    }

    override suspend fun getDefaultAccountSync(): String = withContext(ioDispatcher) {
        val dao = preferenceDao ?: return@withContext memDefaultAccount.value
        dao.getPreference(KEY_DEFAULT_ACCOUNT) ?: CategoryConstants.ACCOUNT_SBI
    }

    override suspend fun setDefaultAccount(account: String): Unit = withContext(ioDispatcher) {
        val trimmed = account.trim()
        val dao = preferenceDao
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_DEFAULT_ACCOUNT, trimmed, updatedAt = System.currentTimeMillis(), dirty = 1))
        } else {
            memDefaultAccount.value = trimmed
        }
        configSyncCoordinator?.schedulePush()
    }

    override fun getActiveTrip(): Flow<String?> {
        val dao = preferenceDao ?: return memActiveTrip.asStateFlow()
        return dao.getPreferenceFlow(KEY_ACTIVE_TRIP)
    }

    override suspend fun setActiveTrip(trip: String?): Unit = withContext(ioDispatcher) {
        val trimmed = trip?.trim()?.ifEmpty { null }
        val dao = preferenceDao
        if (dao != null) {
            if (trimmed != null) {
                dao.setPreference(PreferenceEntity(KEY_ACTIVE_TRIP, trimmed, updatedAt = System.currentTimeMillis(), dirty = 1))
            } else {
                dao.deletePreference(KEY_ACTIVE_TRIP)
            }
        } else {
            memActiveTrip.value = trimmed
        }
        configSyncCoordinator?.schedulePush()
    }

    override fun getGoogleAccountEmail(): Flow<String?> {
        val dao = preferenceDao ?: return memGoogleEmail.asStateFlow()
        return dao.getPreferenceFlow(KEY_GOOGLE_EMAIL)
    }

    override suspend fun getGoogleAccountEmailSync(): String? = withContext(ioDispatcher) {
        val dao = preferenceDao ?: return@withContext memGoogleEmail.value
        dao.getPreference(KEY_GOOGLE_EMAIL)
    }

    override suspend fun setGoogleAccountEmail(email: String?): Unit = withContext(ioDispatcher) {
        val trimmed = email?.trim()?.ifEmpty { null }
        val dao = preferenceDao
        if (dao != null) {
            if (trimmed != null) {
                dao.setPreference(PreferenceEntity(KEY_GOOGLE_EMAIL, trimmed, updatedAt = System.currentTimeMillis(), dirty = 0))
            } else {
                dao.deletePreference(KEY_GOOGLE_EMAIL)
            }
        } else {
            memGoogleEmail.value = trimmed
        }
    }

    override fun getSettleDelaySeconds(): Flow<Int> {
        val dao = preferenceDao ?: return memSettleDelay.asStateFlow()
        return dao.getPreferenceFlow(KEY_SETTLE_DELAY_SECONDS).map {
            it?.toIntOrNull() ?: DEFAULT_SETTLE_DELAY_SECONDS
        }
    }

    override suspend fun getSettleDelaySecondsSync(): Int = withContext(ioDispatcher) {
        val dao = preferenceDao ?: return@withContext memSettleDelay.value
        dao.getPreference(KEY_SETTLE_DELAY_SECONDS)?.toIntOrNull() ?: DEFAULT_SETTLE_DELAY_SECONDS
    }

    override suspend fun setSettleDelaySeconds(seconds: Int): Unit = withContext(ioDispatcher) {
        val bounded = seconds.coerceIn(30, 300)
        val dao = preferenceDao
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_SETTLE_DELAY_SECONDS, bounded.toString(), updatedAt = System.currentTimeMillis(), dirty = 1))
        } else {
            memSettleDelay.value = bounded
        }
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun getOrCreateDeviceId(): String = withContext(ioDispatcher) {
        val existing = getDeviceIdSync()
        if (!existing.isNullOrBlank()) {
            return@withContext existing
        }
        val newId = UUID.randomUUID().toString()
        val dao = preferenceDao
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_DEVICE_ID, newId, updatedAt = System.currentTimeMillis(), dirty = 0))
        } else {
            memDeviceId.value = newId
        }
        newId
    }

    override suspend fun getDeviceIdSync(): String? = withContext(ioDispatcher) {
        val dao = preferenceDao ?: return@withContext memDeviceId.value
        dao.getPreference(KEY_DEVICE_ID)
    }

    override suspend fun getPreference(key: String): String? = withContext(ioDispatcher) {
        val dao = preferenceDao ?: return@withContext memGenericPrefs[key]
        dao.getPreference(key)
    }

    override suspend fun setPreference(key: String, value: String?, isDirty: Boolean): Unit = withContext(ioDispatcher) {
        val dao = preferenceDao
        val trimmed = value?.trim()
        val dirtyFlag = if (isDirty && !ConfigConstants.isLocalOnlyPref(key)) 1 else 0
        if (dao != null) {
            if (trimmed != null) {
                dao.setPreference(PreferenceEntity(key, trimmed, updatedAt = System.currentTimeMillis(), dirty = dirtyFlag))
            } else {
                dao.deletePreference(key)
            }
        } else {
            if (trimmed != null) {
                memGenericPrefs[key] = trimmed
            } else {
                memGenericPrefs.remove(key)
            }
        }
        if (dirtyFlag == 1) {
            configSyncCoordinator?.schedulePush()
        }
    }

    override suspend fun deletePreference(key: String): Unit = withContext(ioDispatcher) {
        val dao = preferenceDao
        if (dao != null) {
            dao.deletePreference(key)
        } else {
            memGenericPrefs.remove(key)
        }
        if (!ConfigConstants.isLocalOnlyPref(key)) {
            configSyncCoordinator?.schedulePush()
        }
    }

    companion object {
        const val KEY_SPREADSHEET_ID: String = "key_spreadsheet_id"
        fun keySpreadsheetIdForYear(year: Int): String = "key_spreadsheet_id_$year"
        const val KEY_DEFAULT_ACCOUNT: String = "key_default_account"
        const val KEY_ACTIVE_TRIP: String = "key_active_trip"
        const val KEY_GOOGLE_EMAIL: String = "key_google_email"
        const val KEY_SETTLE_DELAY_SECONDS: String = "key_settle_delay_seconds"
        const val KEY_DEVICE_ID: String = "device_id"
        const val DEFAULT_SETTLE_DELAY_SECONDS: Int = 120
    }
}
