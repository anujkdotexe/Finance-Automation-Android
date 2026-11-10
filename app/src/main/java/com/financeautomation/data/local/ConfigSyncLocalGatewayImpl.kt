package com.financeautomation.data.local

import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.dao.CategoryDao
import com.financeautomation.data.local.dao.CompanionDao
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.data.local.entity.CategoryEntity
import com.financeautomation.data.local.entity.CompanionEntity
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.DirtyConfigSnapshot
import com.financeautomation.domain.repository.SyncedPreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Implementation of [ConfigSyncLocalGateway] coordinating Room SQLite DAOs
 * for cloud configuration synchronization.
 */
class ConfigSyncLocalGatewayImpl(
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val companionDao: CompanionDao,
    private val tripTagDao: TripTagDao,
    private val preferenceDao: PreferenceDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ConfigSyncLocalGateway {

    override suspend fun getDirtySnapshot(): DirtyConfigSnapshot = withContext(ioDispatcher) {
        val dirtyAccounts = accountDao.getDirtyAccounts().map { it.toDomain() }
        val dirtyCategories = categoryDao.getDirtyCategories().map { it.toDomain() }
        val dirtyCompanions = companionDao.getDirtyCompanions().map { it.toDomain() }
        val dirtyTripTags = tripTagDao.getDirtyTripTags().map { it.toDomain() }
        val dirtyPrefs = preferenceDao.getDirtyPreferences()
            .filterNot { ConfigConstants.isLocalOnlyPref(it.key) }
            .map { SyncedPreference(it.key, it.value, it.updatedAt, it.dirty == 1) }

        DirtyConfigSnapshot(
            accounts = dirtyAccounts,
            categories = dirtyCategories,
            companions = dirtyCompanions,
            tripTags = dirtyTripTags,
            preferences = dirtyPrefs
        )
    }

    override suspend fun markAccountsClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) accountDao.markClean(ids)
    }

    override suspend fun markCategoriesClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) categoryDao.markClean(ids)
    }

    override suspend fun markCompanionsClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) companionDao.markClean(ids)
    }

    override suspend fun markTripTagsClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) tripTagDao.markClean(ids)
    }

    override suspend fun markPreferencesClean(keys: List<String>): Unit = withContext(ioDispatcher) {
        if (keys.isNotEmpty()) preferenceDao.markClean(keys)
    }

    override suspend fun applyRemoteAccount(
        name: String,
        lastFour: String?,
        isDefault: Boolean,
        isArchived: Boolean,
        displayOrder: Int,
        updatedAt: Long,
        deleted: Boolean
    ): Unit = withContext(ioDispatcher) {
        val existing = accountDao.getByName(name)
        if (existing == null) {
            if (!deleted) {
                val newId = "acc_${UUID.randomUUID().toString().take(8)}"
                accountDao.insert(
                    AccountEntity(
                        id = newId,
                        name = name,
                        lastFourDigits = lastFour,
                        isDefault = isDefault,
                        isArchived = isArchived,
                        displayOrder = displayOrder,
                        updatedAt = updatedAt,
                        dirty = 0
                    )
                )
            }
        } else {
            if (updatedAt > existing.updatedAt) {
                if (deleted) {
                    accountDao.update(
                        existing.copy(
                            isArchived = true,
                            isDefault = false,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                } else {
                    accountDao.update(
                        existing.copy(
                            name = name,
                            lastFourDigits = lastFour,
                            isDefault = isDefault,
                            isArchived = isArchived,
                            displayOrder = displayOrder,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                }
            }
        }
    }

    override suspend fun applyRemoteCategory(
        name: String,
        excludedFromBurn: Boolean,
        isDefault: Boolean,
        isArchived: Boolean,
        displayOrder: Int,
        updatedAt: Long,
        deleted: Boolean
    ): Unit = withContext(ioDispatcher) {
        val existing = categoryDao.getByName(name)
        if (existing == null) {
            if (!deleted) {
                val newId = "cat_${UUID.randomUUID().toString().take(8)}"
                categoryDao.insert(
                    CategoryEntity(
                        id = newId,
                        name = name,
                        isDefault = isDefault,
                        isExcludedFromLivingBurn = excludedFromBurn,
                        isArchived = isArchived,
                        displayOrder = displayOrder,
                        updatedAt = updatedAt,
                        dirty = 0
                    )
                )
            }
        } else {
            if (updatedAt > existing.updatedAt) {
                if (deleted) {
                    categoryDao.update(
                        existing.copy(
                            isArchived = true,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                } else {
                    categoryDao.update(
                        existing.copy(
                            name = name,
                            isDefault = isDefault,
                            isExcludedFromLivingBurn = excludedFromBurn,
                            isArchived = isArchived,
                            displayOrder = displayOrder,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                }
            }
        }
    }

    override suspend fun applyRemoteCompanion(
        name: String,
        isArchived: Boolean,
        updatedAt: Long,
        deleted: Boolean
    ): Unit = withContext(ioDispatcher) {
        val existing = companionDao.getByName(name)
        if (existing == null) {
            if (!deleted) {
                val newId = "comp_${UUID.randomUUID().toString().take(8)}"
                companionDao.insert(
                    CompanionEntity(
                        id = newId,
                        name = name,
                        isSelected = false,
                        balance = 0.0,
                        isArchived = isArchived,
                        updatedAt = updatedAt,
                        dirty = 0
                    )
                )
            }
        } else {
            if (updatedAt > existing.updatedAt) {
                if (deleted) {
                    companionDao.update(
                        existing.copy(
                            isArchived = true,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                } else {
                    companionDao.update(
                        existing.copy(
                            name = name,
                            isArchived = isArchived,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                }
            }
        }
    }

    override suspend fun applyRemoteTripTag(
        name: String,
        isActive: Boolean,
        isArchived: Boolean,
        updatedAt: Long,
        deleted: Boolean
    ): Unit = withContext(ioDispatcher) {
        val existing = tripTagDao.getByName(name)
        if (existing == null) {
            if (!deleted) {
                val newId = "tag_${UUID.randomUUID().toString().take(8)}"
                tripTagDao.insert(
                    TripTagEntity(
                        id = newId,
                        name = name,
                        isActive = isActive,
                        isArchived = isArchived,
                        createdAt = updatedAt,
                        updatedAt = updatedAt,
                        dirty = 0
                    )
                )
            }
        } else {
            if (updatedAt > existing.updatedAt) {
                if (deleted) {
                    tripTagDao.update(
                        existing.copy(
                            isArchived = true,
                            isActive = false,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                } else {
                    tripTagDao.update(
                        existing.copy(
                            name = name,
                            isActive = isActive,
                            isArchived = isArchived,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                }
            }
        }
    }

    override suspend fun applyRemotePreference(
        key: String,
        value: String?,
        updatedAt: Long,
        deleted: Boolean
    ): Unit = withContext(ioDispatcher) {
        if (ConfigConstants.isLocalOnlyPref(key)) return@withContext

        val allPrefs = preferenceDao.getAllPreferences()
        val existing = allPrefs.find { it.key == key }
        val existingUpdatedAt = existing?.updatedAt ?: 0L

        if (updatedAt > existingUpdatedAt) {
            if (deleted) {
                preferenceDao.deletePreference(key)
            } else {
                preferenceDao.setPreference(
                    PreferenceEntity(
                        key = key,
                        value = value,
                        updatedAt = updatedAt,
                        dirty = 0
                    )
                )
            }
        }
    }

    override suspend fun markAllLocalItemsDirty(): Unit = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        accountDao.getAllAccounts().forEach {
            accountDao.update(it.copy(updatedAt = now, dirty = 1))
        }
        categoryDao.getAllCategories().forEach {
            categoryDao.update(it.copy(updatedAt = now, dirty = 1))
        }
        companionDao.getActiveCompanions().forEach {
            companionDao.update(it.copy(updatedAt = now, dirty = 1))
        }
        tripTagDao.getAllTripTags().forEach {
            tripTagDao.update(it.copy(updatedAt = now, dirty = 1))
        }
        preferenceDao.getAllPreferences()
            .filterNot { ConfigConstants.isLocalOnlyPref(it.key) }
            .forEach {
                preferenceDao.setPreference(it.copy(updatedAt = now, dirty = 1))
            }
    }
}

