package com.financeautomation.data.repository

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository implementation of [PreferencesRepository].
 * Backed primarily by Room SQLite [PreferenceDao] in preferences_table
 * with reactive Flow observation and suspend DAO provider for main-thread safety.
 */
class PreferencesRepositoryImpl(
    private val preferenceDao: PreferenceDao? = null,
    private val preferenceDaoProvider: (suspend () -> PreferenceDao)? = null,
    private var configSyncCoordinatorProvider: (() -> ConfigSyncCoordinator)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PreferencesRepository {

    /**
     * Explicit test/headless constructor utilizing in-memory preferences fallback.
     */
    constructor(isTestHeadless: Boolean = true) : this(
        preferenceDao = null,
        preferenceDaoProvider = null,
        configSyncCoordinatorProvider = null,
        ioDispatcher = Dispatchers.IO
    )

    private suspend fun resolveDao(): PreferenceDao? {
        if (preferenceDao != null) return preferenceDao
        return preferenceDaoProvider?.invoke()
    }

    // In-memory fallbacks when running headless without Room database
    private val memSpreadsheetId = MutableStateFlow<String?>(null)
    private val memDefaultAccount = MutableStateFlow(CategoryConstants.ACCOUNT_OTHER)
    private val memActiveTrip = MutableStateFlow<String?>(null)
    private val memGoogleEmail = MutableStateFlow<String?>(null)
    private val memGoogleDisplayName = MutableStateFlow<String?>(null)
    private val memSettleDelay = MutableStateFlow(DEFAULT_SETTLE_DELAY_SECONDS)
    private val memDeviceId = MutableStateFlow<String?>(null)
    private val memDeveloperMode = MutableStateFlow(false)
    private val memThemeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private val memGenericPrefs = ConcurrentHashMap<String, String>()

    var configSyncCoordinator: ConfigSyncCoordinator?
        get() = configSyncCoordinatorProvider?.invoke()
        set(value) {
            configSyncCoordinatorProvider = value?.let { { it } }
        }

    override fun getSpreadsheetId(): Flow<String?> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memSpreadsheetId.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(KEY_SPREADSHEET_ID))
        }
    }.flowOn(ioDispatcher)

    override suspend fun getSpreadsheetIdSync(): String? = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memSpreadsheetId.value
        dao.getPreference(KEY_SPREADSHEET_ID)
    }

    override suspend fun setSpreadsheetId(id: String): Unit = withContext(ioDispatcher) {
        val trimmed = id.trim()
        val dao = resolveDao()
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_SPREADSHEET_ID, trimmed, updatedAt = System.currentTimeMillis(), dirty = 0))
        } else {
            memSpreadsheetId.value = trimmed
        }
    }

    override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memSpreadsheetId.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(keySpreadsheetIdForYear(year)).map { yearSpecificId ->
                yearSpecificId ?: dao.getPreference(KEY_SPREADSHEET_ID)
            })
        }
    }.flowOn(ioDispatcher)

    override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memSpreadsheetId.value
        val yearSpecific = dao.getPreference(keySpreadsheetIdForYear(year))
        if (!yearSpecific.isNullOrBlank()) {
            yearSpecific
        } else {
            dao.getPreference(KEY_SPREADSHEET_ID)
        }
    }

    override suspend fun setSpreadsheetIdForYear(year: Int, id: String): Unit = withContext(ioDispatcher) {
        val trimmed = id.trim()
        val dao = resolveDao()
        if (dao != null) {
            val now = System.currentTimeMillis()
            dao.setPreference(PreferenceEntity(keySpreadsheetIdForYear(year), trimmed, updatedAt = now, dirty = 0))
            dao.setPreference(PreferenceEntity(KEY_SPREADSHEET_ID, trimmed, updatedAt = now, dirty = 0))
        } else {
            memSpreadsheetId.value = trimmed
        }
    }

    override fun getDefaultAccount(): Flow<String> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memDefaultAccount.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(KEY_DEFAULT_ACCOUNT).map { it ?: CategoryConstants.ACCOUNT_OTHER })
        }
    }.flowOn(ioDispatcher)

    override suspend fun getDefaultAccountSync(): String = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memDefaultAccount.value
        dao.getPreference(KEY_DEFAULT_ACCOUNT) ?: CategoryConstants.ACCOUNT_OTHER
    }

    override suspend fun setDefaultAccount(account: String): Unit = withContext(ioDispatcher) {
        val trimmed = account.trim()
        val dao = resolveDao()
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_DEFAULT_ACCOUNT, trimmed, updatedAt = System.currentTimeMillis(), dirty = 1))
        } else {
            memDefaultAccount.value = trimmed
        }
        configSyncCoordinator?.schedulePush()
    }

    override fun getActiveTrip(): Flow<String?> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memActiveTrip.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(KEY_ACTIVE_TRIP))
        }
    }.flowOn(ioDispatcher)

    override suspend fun setActiveTrip(trip: String?): Unit = withContext(ioDispatcher) {
        val trimmed = trip?.trim()?.ifEmpty { null }
        val dao = resolveDao()
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

    override fun getGoogleAccountEmail(): Flow<String?> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memGoogleEmail.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(KEY_GOOGLE_EMAIL))
        }
    }.flowOn(ioDispatcher)

    override suspend fun getGoogleAccountEmailSync(): String? = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memGoogleEmail.value
        dao.getPreference(KEY_GOOGLE_EMAIL)
    }

    override suspend fun setGoogleAccountEmail(email: String?): Unit = withContext(ioDispatcher) {
        val trimmed = email?.trim()?.ifEmpty { null }
        val dao = resolveDao()
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

    override fun getGoogleAccountDisplayName(): Flow<String?> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memGoogleDisplayName.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(KEY_GOOGLE_DISPLAY_NAME))
        }
    }.flowOn(ioDispatcher)

    override suspend fun getGoogleAccountDisplayNameSync(): String? = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memGoogleDisplayName.value
        dao.getPreference(KEY_GOOGLE_DISPLAY_NAME)
    }

    override suspend fun setGoogleAccountDisplayName(name: String?): Unit = withContext(ioDispatcher) {
        val trimmed = name?.trim()?.ifEmpty { null }
        val dao = resolveDao()
        if (dao != null) {
            if (trimmed != null) {
                dao.setPreference(PreferenceEntity(KEY_GOOGLE_DISPLAY_NAME, trimmed, updatedAt = System.currentTimeMillis(), dirty = 0))
            } else {
                dao.deletePreference(KEY_GOOGLE_DISPLAY_NAME)
            }
        } else {
            memGoogleDisplayName.value = trimmed
        }
    }

    override fun getSettleDelaySeconds(): Flow<Int> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memSettleDelay.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(KEY_SETTLE_DELAY_SECONDS).map {
                it?.toIntOrNull() ?: DEFAULT_SETTLE_DELAY_SECONDS
            })
        }
    }.flowOn(ioDispatcher)

    override suspend fun getSettleDelaySecondsSync(): Int = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memSettleDelay.value
        dao.getPreference(KEY_SETTLE_DELAY_SECONDS)?.toIntOrNull() ?: DEFAULT_SETTLE_DELAY_SECONDS
    }

    override suspend fun setSettleDelaySeconds(seconds: Int): Unit = withContext(ioDispatcher) {
        val bounded = seconds.coerceIn(30, 300)
        val dao = resolveDao()
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
        val dao = resolveDao()
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_DEVICE_ID, newId, updatedAt = System.currentTimeMillis(), dirty = 0))
        } else {
            memDeviceId.value = newId
        }
        newId
    }

    override suspend fun getDeviceIdSync(): String? = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memDeviceId.value
        dao.getPreference(KEY_DEVICE_ID)
    }

    override fun getDeveloperMode(): Flow<Boolean> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memDeveloperMode.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(KEY_DEVELOPER_MODE).map {
                it?.toBooleanStrictOrNull() ?: false
            })
        }
    }.flowOn(ioDispatcher)

    override suspend fun getDeveloperModeSync(): Boolean = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memDeveloperMode.value
        dao.getPreference(KEY_DEVELOPER_MODE)?.toBooleanStrictOrNull() ?: false
    }

    override suspend fun setDeveloperMode(enabled: Boolean): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_DEVELOPER_MODE, enabled.toString(), updatedAt = System.currentTimeMillis(), dirty = 0))
        } else {
            memDeveloperMode.value = enabled
        }
    }

    override fun getThemeMode(): Flow<ThemeMode> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emitAll(memThemeMode.asStateFlow())
        } else {
            emitAll(dao.getPreferenceFlow(KEY_THEME_MODE).map { raw ->
                raw?.let {
                    try {
                        ThemeMode.valueOf(it)
                    } catch (_: Exception) {
                        ThemeMode.SYSTEM
                    }
                } ?: ThemeMode.SYSTEM
            })
        }
    }.flowOn(ioDispatcher)

    override suspend fun getThemeModeSync(): ThemeMode = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memThemeMode.value
        val raw = dao.getPreference(KEY_THEME_MODE) ?: return@withContext ThemeMode.SYSTEM
        try {
            ThemeMode.valueOf(raw)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        if (dao != null) {
            dao.setPreference(PreferenceEntity(KEY_THEME_MODE, mode.name, updatedAt = System.currentTimeMillis(), dirty = 0))
        } else {
            memThemeMode.value = mode
        }
    }

    override fun getSubscriptionReminderTime(): Flow<String> = flow {
        val dao = resolveDao()
        if (dao == null) {
            emit(getSubscriptionReminderTimeSync())
        } else {
            emitAll(dao.getPreferenceFlow(PreferencesRepository.KEY_SUBSCRIPTION_REMINDER_TIME).map {
                PreferencesRepository.normalizeReminderTime(it)
            })
        }
    }.flowOn(ioDispatcher)

    override suspend fun getPreference(key: String): String? = withContext(ioDispatcher) {
        val dao = resolveDao() ?: return@withContext memGenericPrefs[key]
        dao.getPreference(key)
    }

    override suspend fun setPreference(key: String, value: String?, isDirty: Boolean): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
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
        val dao = resolveDao()
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
        const val KEY_GOOGLE_DISPLAY_NAME: String = "key_google_display_name"
        const val KEY_SETTLE_DELAY_SECONDS: String = "key_settle_delay_seconds"
        const val KEY_DEVICE_ID: String = "device_id"
        const val KEY_DEVELOPER_MODE: String = "key_developer_mode"
        const val KEY_THEME_MODE: String = "key_theme_mode"
        const val DEFAULT_SETTLE_DELAY_SECONDS: Int = 120
    }
}
