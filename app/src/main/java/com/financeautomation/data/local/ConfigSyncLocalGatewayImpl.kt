package com.financeautomation.data.local

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.dao.CategoryDao
import com.financeautomation.data.local.dao.CompanionDao
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.dao.BudgetDao
import com.financeautomation.data.local.dao.SubscriptionDao
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.data.local.entity.CategoryEntity
import com.financeautomation.data.local.entity.CompanionEntity
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.local.entity.BudgetEntity
import com.financeautomation.data.local.entity.SubscriptionEntity
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.DirtyConfigSnapshot
import com.financeautomation.domain.repository.SyncedPreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Implementation of [ConfigSyncLocalGateway] coordinating Room SQLite DAOs
 * for cloud configuration synchronization, supporting suspend DB provider for main-thread safety.
 */
class ConfigSyncLocalGatewayImpl(
    accountDao: AccountDao? = null,
    categoryDao: CategoryDao? = null,
    companionDao: CompanionDao? = null,
    tripTagDao: TripTagDao? = null,
    preferenceDao: PreferenceDao? = null,
    subscriptionDao: SubscriptionDao? = null,
    budgetDao: BudgetDao? = null,
    private val dbProvider: (suspend () -> FinanceDatabase)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ConfigSyncLocalGateway {

    private val directAccountDao = accountDao
    private val directCategoryDao = categoryDao
    private val directCompanionDao = companionDao
    private val directTripTagDao = tripTagDao
    private val directPreferenceDao = preferenceDao
    private val directSubscriptionDao = subscriptionDao
    private val directBudgetDao = budgetDao

    constructor(
        accountDao: AccountDao,
        categoryDao: CategoryDao,
        companionDao: CompanionDao,
        tripTagDao: TripTagDao,
        preferenceDao: PreferenceDao,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        accountDao = accountDao,
        categoryDao = categoryDao,
        companionDao = companionDao,
        tripTagDao = tripTagDao,
        preferenceDao = preferenceDao,
        dbProvider = null,
        ioDispatcher = ioDispatcher
    )

    private suspend fun resolveAccountDao(): AccountDao =
        directAccountDao ?: dbProvider?.invoke()?.accountDao() ?: throw IllegalStateException("AccountDao or dbProvider required")

    private suspend fun resolveCategoryDao(): CategoryDao =
        directCategoryDao ?: dbProvider?.invoke()?.categoryDao() ?: throw IllegalStateException("CategoryDao or dbProvider required")

    private suspend fun resolveCompanionDao(): CompanionDao =
        directCompanionDao ?: dbProvider?.invoke()?.companionDao() ?: throw IllegalStateException("CompanionDao or dbProvider required")

    private suspend fun resolveTripTagDao(): TripTagDao =
        directTripTagDao ?: dbProvider?.invoke()?.tripTagDao() ?: throw IllegalStateException("TripTagDao or dbProvider required")

    private suspend fun resolveBudgetDao(): BudgetDao? =
        directBudgetDao ?: dbProvider?.invoke()?.budgetDao()

    private suspend fun resolveSubscriptionDao(): SubscriptionDao? =
        directSubscriptionDao ?: dbProvider?.invoke()?.subscriptionDao()

    private suspend fun resolvePreferenceDao(): PreferenceDao =
        directPreferenceDao ?: dbProvider?.invoke()?.preferenceDao() ?: throw IllegalStateException("PreferenceDao or dbProvider required")

    override suspend fun getDirtySnapshot(): DirtyConfigSnapshot = withContext(ioDispatcher) {
        val accountDao = resolveAccountDao()
        val categoryDao = resolveCategoryDao()
        val companionDao = resolveCompanionDao()
        val tripTagDao = resolveTripTagDao()
        val preferenceDao = resolvePreferenceDao()

        val tombstones = preferenceDao.getDirtyPreferences()
            .filter { it.key.startsWith("tombstone:") }

        val tombstonedAccounts = mutableListOf<Account>()
        val tombstonedCategories = mutableListOf<Category>()
        val tombstonedCompanions = mutableListOf<Companion>()
        val tombstonedTripTags = mutableListOf<TripTag>()
        val tombstonedSubscriptions = mutableListOf<Subscription>()

        tombstones.forEach { t ->
            val prefixRemoved = t.key.removePrefix("tombstone:")
            val firstColon = prefixRemoved.indexOf(':')
            if (firstColon > 0) {
                val entityType = prefixRemoved.substring(0, firstColon)
                val entityKey = prefixRemoved.substring(firstColon + 1)
                when (entityType) {
                    ConfigConstants.TYPE_ACCOUNT -> tombstonedAccounts.add(
                        Account(id = entityKey, name = entityKey, deleted = true, updatedAt = t.updatedAt)
                    )
                    ConfigConstants.TYPE_CATEGORY -> tombstonedCategories.add(
                        Category(id = entityKey, name = entityKey, deleted = true, updatedAt = t.updatedAt)
                    )
                    ConfigConstants.TYPE_PERSON -> tombstonedCompanions.add(
                        Companion(id = entityKey, name = entityKey, deleted = true, updatedAt = t.updatedAt)
                    )
                    ConfigConstants.TYPE_TRIP -> tombstonedTripTags.add(
                        TripTag(id = entityKey, name = entityKey, deleted = true, updatedAt = t.updatedAt)
                    )
                    ConfigConstants.TYPE_SUBSCRIPTION -> tombstonedSubscriptions.add(
                        Subscription(
                            id = entityKey,
                            name = entityKey,
                            amount = null,
                            frequency = SubscriptionFrequency.MONTHLY,
                            anchorEpochDay = 0L,
                            account = "",
                            deleted = true,
                            updatedAt = t.updatedAt
                        )
                    )
                }
            }
        }

        val dirtyAccounts = accountDao.getDirtyAccounts().map { it.toDomain() } + tombstonedAccounts
        val dirtyCategories = categoryDao.getDirtyCategories().map { it.toDomain() } + tombstonedCategories
        val dirtyCompanions = companionDao.getDirtyCompanions().map { it.toDomain() } + tombstonedCompanions
        val dirtyTripTags = tripTagDao.getDirtyTripTags().map { it.toDomain() } + tombstonedTripTags
        val dirtyPrefs = preferenceDao.getDirtyPreferences()
            .filterNot { ConfigConstants.isLocalOnlyPref(it.key) || it.key.startsWith("tombstone:") }
            .map { SyncedPreference(it.key, it.value, it.updatedAt, it.dirty == 1) }

        val dirtySubscriptions = (resolveSubscriptionDao()?.getDirtySubscriptions()?.map { it.toDomain() }.orEmpty()) + tombstonedSubscriptions
        val dirtyBudgets = resolveBudgetDao()?.getDirty()?.map { it.toDomain() }.orEmpty()

        DirtyConfigSnapshot(
            accounts = dirtyAccounts,
            categories = dirtyCategories,
            companions = dirtyCompanions,
            tripTags = dirtyTripTags,
            preferences = dirtyPrefs,
            subscriptions = dirtySubscriptions,
            budgets = dirtyBudgets
        )
    }

    override suspend fun recordTombstone(type: String, key: String, updatedAt: Long): Unit = withContext(ioDispatcher) {
        val prefDao = resolvePreferenceDao()
        prefDao.setPreference(
            PreferenceEntity(
                key = "tombstone:$type:$key",
                value = "deleted",
                updatedAt = updatedAt,
                dirty = 1
            )
        )
    }

    override suspend fun markAccountsClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) {
            resolveAccountDao().markClean(ids)
            val prefDao = resolvePreferenceDao()
            ids.forEach { prefDao.deletePreference("tombstone:${ConfigConstants.TYPE_ACCOUNT}:$it") }
        }
    }

    override suspend fun markCategoriesClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) {
            resolveCategoryDao().markClean(ids)
            val prefDao = resolvePreferenceDao()
            ids.forEach { prefDao.deletePreference("tombstone:${ConfigConstants.TYPE_CATEGORY}:$it") }
        }
    }

    override suspend fun markCompanionsClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) {
            resolveCompanionDao().markClean(ids)
            val prefDao = resolvePreferenceDao()
            ids.forEach { prefDao.deletePreference("tombstone:${ConfigConstants.TYPE_PERSON}:$it") }
        }
    }

    override suspend fun markTripTagsClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) {
            resolveTripTagDao().markClean(ids)
            val prefDao = resolvePreferenceDao()
            ids.forEach { prefDao.deletePreference("tombstone:${ConfigConstants.TYPE_TRIP}:$it") }
        }
    }

    override suspend fun markPreferencesClean(keys: List<String>): Unit = withContext(ioDispatcher) {
        if (keys.isNotEmpty()) {
            val prefDao = resolvePreferenceDao()
            prefDao.markClean(keys)
            keys.forEach { key ->
                if (prefDao.getPreference(key) == null) {
                    prefDao.deletePreference(key)
                }
            }
        }
    }

    override suspend fun markSubscriptionsClean(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isNotEmpty()) {
            resolveSubscriptionDao()?.markClean(ids)
            val prefDao = resolvePreferenceDao()
            ids.forEach { prefDao.deletePreference("tombstone:${ConfigConstants.TYPE_SUBSCRIPTION}:$it") }
        }
    }

    override suspend fun markBudgetsClean(categories: List<String>): Unit = withContext(ioDispatcher) {
        if (categories.isNotEmpty()) resolveBudgetDao()?.markClean(categories)
    }

    override suspend fun applyRemoteBudget(
        category: String,
        monthlyAmount: Double,
        updatedAt: Long,
        deleted: Boolean
    ): Unit = withContext(ioDispatcher) {
        val dao = resolveBudgetDao() ?: return@withContext
        val existing = dao.getByCategory(category)
        if (existing == null) {
            if (!deleted) {
                dao.upsert(BudgetEntity(category, monthlyAmount, updatedAt, dirty = 0, deleted = 0))
            }
        } else if (updatedAt > existing.updatedAt) {
            dao.upsert(
                BudgetEntity(
                    category = existing.category,
                    monthlyAmount = if (deleted) existing.monthlyAmount else monthlyAmount,
                    updatedAt = updatedAt,
                    dirty = 0,
                    deleted = if (deleted) 1 else 0
                )
            )
        }
    }

    override suspend fun applyRemoteSubscription(
        subscription: Subscription,
        updatedAt: Long,
        deleted: Boolean
    ): Unit = withContext(ioDispatcher) {
        val dao = resolveSubscriptionDao() ?: return@withContext
        val existing = dao.getById(subscription.id)
        if (existing == null) {
            if (!deleted) {
                dao.insert(SubscriptionEntity.fromDomain(subscription, updatedAt = updatedAt, dirty = 0))
            }
        } else if (updatedAt > existing.updatedAt) {
            val incoming = if (deleted) existing.toDomain().copy(active = false) else subscription
            dao.insert(SubscriptionEntity.fromDomain(incoming, updatedAt = updatedAt, dirty = 0))
        }
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
        val accountDao = resolveAccountDao()
        val existing = accountDao.getByName(name)
        // Only one account may be the default. When the sheet marks two accounts default, the one with the newer
        // stamp keeps it, whatever order the rows are read in.
        val currentDefault = accountDao.getDefaultAccount()
        val takesDefault = isDefault && !deleted &&
            (currentDefault == null || currentDefault.id == existing?.id || currentDefault.updatedAt <= updatedAt)
        if (existing == null) {
            if (!deleted) {
                val newId = "acc_${UUID.randomUUID().toString().take(8)}"
                accountDao.insert(
                    AccountEntity(
                        id = newId,
                        name = name,
                        lastFourDigits = lastFour,
                        isDefault = takesDefault,
                        isArchived = isArchived,
                        displayOrder = displayOrder,
                        updatedAt = updatedAt,
                        dirty = 0
                    )
                )
                if (takesDefault) accountDao.makeOnlyDefault(newId, updatedAt)
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
                    // The default account was removed: Cash becomes the default so there is always one
                    if (existing.isDefault) {
                        accountDao.getByName(CategoryConstants.ACCOUNT_CASH)?.let { accountDao.makeOnlyDefault(it.id, updatedAt) }
                    }
                } else {
                    accountDao.update(
                        existing.copy(
                            name = name,
                            lastFourDigits = lastFour,
                            isDefault = takesDefault,
                            isArchived = isArchived,
                            displayOrder = displayOrder,
                            updatedAt = updatedAt,
                            dirty = 0
                        )
                    )
                    if (takesDefault) accountDao.makeOnlyDefault(existing.id, updatedAt)
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
        deleted: Boolean,
        iconName: String
    ): Unit = withContext(ioDispatcher) {
        val categoryDao = resolveCategoryDao()
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
                        iconName = iconName,
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
                            iconName = iconName,
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
        val companionDao = resolveCompanionDao()
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
        val tripTagDao = resolveTripTagDao()
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
        val preferenceDao = resolvePreferenceDao()

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
        val accountDao = resolveAccountDao()
        val categoryDao = resolveCategoryDao()
        val companionDao = resolveCompanionDao()
        val tripTagDao = resolveTripTagDao()
        val preferenceDao = resolvePreferenceDao()

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
        resolveSubscriptionDao()?.let { subDao ->
            subDao.getAll().forEach { subDao.update(it.copy(updatedAt = now, dirty = 1)) }
        }
        resolveBudgetDao()?.let { budgetDao ->
            budgetDao.getAllIncludingDeleted().forEach { budgetDao.upsert(it.copy(updatedAt = now, dirty = 1)) }
        }
    }
}
