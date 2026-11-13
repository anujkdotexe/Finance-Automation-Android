package com.financeautomation.domain.repository

import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.TripTag

/**
 * Domain entity representing a synchronized key-value preference.
 */
data class SyncedPreference(
    val key: String,
    val value: String?,
    val updatedAt: Long,
    val dirty: Boolean
)

/**
 * Snapshot of all locally modified entities awaiting cloud push.
 */
data class DirtyConfigSnapshot(
    val accounts: List<Account>,
    val categories: List<Category>,
    val companions: List<Companion>,
    val tripTags: List<TripTag>,
    val preferences: List<SyncedPreference>,
    val subscriptions: List<Subscription> = emptyList(),
    val budgets: List<Budget> = emptyList()
) {
    val isEmpty: Boolean
        get() = accounts.isEmpty() && categories.isEmpty() && companions.isEmpty() && tripTags.isEmpty() &&
            preferences.isEmpty() && subscriptions.isEmpty() && budgets.isEmpty()
}

/**
 * Domain contract abstracting local Room database queries and mutations
 * needed during cloud configuration pull and push synchronization.
 * Pure Kotlin interface with zero Android framework dependencies.
 */
interface ConfigSyncLocalGateway {

    /**
     * Gathers all local entities marked with dirty = 1.
     */
    suspend fun getDirtySnapshot(): DirtyConfigSnapshot

    /**
     * Clears dirty flags for accounts with IDs in [ids].
     */
    suspend fun markAccountsClean(ids: List<String>)

    /**
     * Clears dirty flags for categories with IDs in [ids].
     */
    suspend fun markCategoriesClean(ids: List<String>)

    /**
     * Clears dirty flags for companions with IDs in [ids].
     */
    suspend fun markCompanionsClean(ids: List<String>)

    /**
     * Clears dirty flags for trip tags with IDs in [ids].
     */
    suspend fun markTripTagsClean(ids: List<String>)

    /**
     * Clears dirty flags for preferences with keys in [keys].
     */
    suspend fun markPreferencesClean(keys: List<String>)

    /**
     * Clears dirty flags for subscriptions with IDs in [ids].
     */
    suspend fun markSubscriptionsClean(ids: List<String>)

    /**
     * Clears dirty flags for budgets of the given [categories].
     */
    suspend fun markBudgetsClean(categories: List<String>) {}

    /**
     * Reconciles a remote budget row against the local database.
     * Updates or inserts only if the remote timestamp is newer. A deleted row removes the budget.
     */
    suspend fun applyRemoteBudget(
        category: String,
        monthlyAmount: Double,
        updatedAt: Long,
        deleted: Boolean
    ) {}

    /**
     * Reconciles a remote subscription row against the local database.
     * Updates or inserts only if the remote timestamp is newer. A deleted row pauses the subscription.
     */
    suspend fun applyRemoteSubscription(
        subscription: Subscription,
        updatedAt: Long,
        deleted: Boolean
    )

    /**
     * Reconciles a remote account row against local database.
     * Updates or inserts only if remote timestamp is newer.
     */
    suspend fun applyRemoteAccount(
        name: String,
        lastFour: String?,
        isDefault: Boolean,
        isArchived: Boolean,
        displayOrder: Int,
        updatedAt: Long,
        deleted: Boolean
    )

    /**
     * Reconciles a remote category row against local database.
     * Updates or inserts only if remote timestamp is newer.
     */
    suspend fun applyRemoteCategory(
        name: String,
        excludedFromBurn: Boolean,
        isDefault: Boolean,
        isArchived: Boolean,
        displayOrder: Int,
        updatedAt: Long,
        deleted: Boolean
    )

    /**
     * Reconciles a remote companion row against local database.
     * Updates or inserts only if remote timestamp is newer.
     */
    suspend fun applyRemoteCompanion(
        name: String,
        isArchived: Boolean,
        updatedAt: Long,
        deleted: Boolean
    )

    /**
     * Reconciles a remote trip tag row against local database.
     * Updates or inserts only if remote timestamp is newer.
     */
    suspend fun applyRemoteTripTag(
        name: String,
        isActive: Boolean,
        isArchived: Boolean,
        updatedAt: Long,
        deleted: Boolean
    )

    /**
     * Reconciles a remote preference row against local database.
     * Updates, deletes or inserts only if remote timestamp is newer.
     */
    suspend fun applyRemotePreference(
        key: String,
        value: String?,
        updatedAt: Long,
        deleted: Boolean
    )

    /**
     * Marks all local accounts, categories, companions, trip tags and non-local preferences
     * as dirty so they can be pushed to an initially empty remote Config tab.
     */
    suspend fun markAllLocalItemsDirty()
}

