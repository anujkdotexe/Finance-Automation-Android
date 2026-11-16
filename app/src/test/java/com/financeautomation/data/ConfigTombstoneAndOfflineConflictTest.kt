package com.financeautomation.data

import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.core.model.Result
import com.financeautomation.data.account.AccountSwitchGuard
import com.financeautomation.data.account.RoomAccountDataStore
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.repository.ConfigRepositoryImpl
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.ConfigRow
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.repository.SyncedPreference
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.ConfigRepository
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.DirtyConfigSnapshot
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Tests for Item D:
 * 1. Tombstone preference keys survival across sign-out vs wipe.
 * 2. Deletions reach Config sheet for accounts, categories, companions, trip tags, subscriptions, and preferences.
 * 3. Offline conflict resolution: old offline edits never overwrite newer ones on pull or push.
 * 4. Backward compatibility: old Config rows without "|" in category value4 still read correctly.
 * 5. Category chosen color preservation through push and pull.
 */
class ConfigTombstoneAndOfflineConflictTest {

    private class TestLocalGateway : ConfigSyncLocalGateway {
        val accounts = mutableMapOf<String, Account>()
        val categories = mutableMapOf<String, Category>()
        val companions = mutableMapOf<String, Companion>()
        val tripTags = mutableMapOf<String, TripTag>()
        val subscriptions = mutableMapOf<String, Subscription>()
        val preferences = mutableMapOf<String, SyncedPreference>()
        val tombstones = mutableMapOf<String, Long>()

        val dirtyAccounts = mutableSetOf<String>()
        val dirtyCategories = mutableSetOf<String>()
        val dirtyCompanions = mutableSetOf<String>()
        val dirtyTripTags = mutableSetOf<String>()
        val dirtySubscriptions = mutableSetOf<String>()
        val dirtyPreferences = mutableSetOf<String>()

        override suspend fun getDirtySnapshot(): DirtyConfigSnapshot {
            val tombstonedAccounts = mutableListOf<Account>()
            val tombstonedCategories = mutableListOf<Category>()
            val tombstonedCompanions = mutableListOf<Companion>()
            val tombstonedTripTags = mutableListOf<TripTag>()
            val tombstonedSubscriptions = mutableListOf<Subscription>()

            tombstones.forEach { (key, ts) ->
                val prefixRemoved = key.removePrefix("tombstone:")
                val colon = prefixRemoved.indexOf(':')
                if (colon > 0) {
                    val type = prefixRemoved.substring(0, colon)
                    val entityKey = prefixRemoved.substring(colon + 1)
                    when (type) {
                        ConfigConstants.TYPE_ACCOUNT -> tombstonedAccounts.add(Account(id = entityKey, name = entityKey, deleted = true, updatedAt = ts))
                        ConfigConstants.TYPE_CATEGORY -> tombstonedCategories.add(Category(id = entityKey, name = entityKey, deleted = true, updatedAt = ts))
                        ConfigConstants.TYPE_PERSON -> tombstonedCompanions.add(Companion(id = entityKey, name = entityKey, deleted = true, updatedAt = ts))
                        ConfigConstants.TYPE_TRIP -> tombstonedTripTags.add(TripTag(id = entityKey, name = entityKey, deleted = true, updatedAt = ts))
                        ConfigConstants.TYPE_SUBSCRIPTION -> tombstonedSubscriptions.add(
                            Subscription(
                                id = entityKey, name = entityKey, amount = null, frequency = SubscriptionFrequency.MONTHLY,
                                anchorEpochDay = 0L, account = "", deleted = true, updatedAt = ts
                            )
                        )
                    }
                }
            }

            return DirtyConfigSnapshot(
                accounts = dirtyAccounts.mapNotNull { accounts[it] } + tombstonedAccounts,
                categories = dirtyCategories.mapNotNull { categories[it] } + tombstonedCategories,
                companions = dirtyCompanions.mapNotNull { companions[it] } + tombstonedCompanions,
                tripTags = dirtyTripTags.mapNotNull { tripTags[it] } + tombstonedTripTags,
                preferences = dirtyPreferences.mapNotNull { preferences[it] },
                subscriptions = dirtySubscriptions.mapNotNull { subscriptions[it] } + tombstonedSubscriptions
            )
        }

        override suspend fun recordTombstone(type: String, key: String, updatedAt: Long) {
            tombstones["tombstone:$type:$key"] = updatedAt
        }

        override suspend fun markAccountsClean(ids: List<String>) {
            dirtyAccounts.removeAll(ids.toSet())
            ids.forEach { tombstones.remove("tombstone:${ConfigConstants.TYPE_ACCOUNT}:$it") }
        }

        override suspend fun markCategoriesClean(ids: List<String>) {
            dirtyCategories.removeAll(ids.toSet())
            ids.forEach { tombstones.remove("tombstone:${ConfigConstants.TYPE_CATEGORY}:$it") }
        }

        override suspend fun markCompanionsClean(ids: List<String>) {
            dirtyCompanions.removeAll(ids.toSet())
            ids.forEach { tombstones.remove("tombstone:${ConfigConstants.TYPE_PERSON}:$it") }
        }

        override suspend fun markTripTagsClean(ids: List<String>) {
            dirtyTripTags.removeAll(ids.toSet())
            ids.forEach { tombstones.remove("tombstone:${ConfigConstants.TYPE_TRIP}:$it") }
        }

        override suspend fun markPreferencesClean(keys: List<String>) {
            dirtyPreferences.removeAll(keys.toSet())
            keys.forEach { k ->
                if (preferences[k]?.value == null) {
                    preferences.remove(k)
                }
            }
        }

        override suspend fun markSubscriptionsClean(ids: List<String>) {
            dirtySubscriptions.removeAll(ids.toSet())
            ids.forEach { tombstones.remove("tombstone:${ConfigConstants.TYPE_SUBSCRIPTION}:$it") }
        }

        override suspend fun applyRemoteAccount(
            name: String, lastFour: String?, isDefault: Boolean, isArchived: Boolean,
            displayOrder: Int, updatedAt: Long, deleted: Boolean
        ) {
            val existing = accounts[name]
            if (existing == null) {
                if (!deleted) accounts[name] = Account(id = "acc_$name", name = name, lastFourDigits = lastFour, isDefault = isDefault, isArchived = isArchived, displayOrder = displayOrder, updatedAt = updatedAt)
            } else if (updatedAt > existing.updatedAt) {
                accounts[name] = existing.copy(lastFourDigits = lastFour, isDefault = isDefault, isArchived = isArchived, displayOrder = displayOrder, updatedAt = updatedAt, deleted = deleted)
            }
        }

        override suspend fun applyRemoteCategory(
            name: String, excludedFromBurn: Boolean, isDefault: Boolean, isArchived: Boolean,
            displayOrder: Int, updatedAt: Long, deleted: Boolean, iconName: String
        ) {
            val existing = categories[name]
            if (existing == null) {
                if (!deleted) categories[name] = Category(id = "cat_$name", name = name, isDefault = isDefault, isExcludedFromLivingBurn = excludedFromBurn, isArchived = isArchived, displayOrder = displayOrder, iconName = iconName, updatedAt = updatedAt)
            } else if (updatedAt > existing.updatedAt) {
                categories[name] = existing.copy(isDefault = isDefault, isExcludedFromLivingBurn = excludedFromBurn, isArchived = isArchived, displayOrder = displayOrder, iconName = iconName, updatedAt = updatedAt, deleted = deleted)
            }
        }

        override suspend fun applyRemoteCompanion(name: String, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {
            val existing = companions[name]
            if (existing == null) {
                if (!deleted) companions[name] = Companion(id = "comp_$name", name = name, isArchived = isArchived, updatedAt = updatedAt)
            } else if (updatedAt > existing.updatedAt) {
                companions[name] = existing.copy(isArchived = isArchived, updatedAt = updatedAt, deleted = deleted)
            }
        }

        override suspend fun applyRemoteTripTag(name: String, isActive: Boolean, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {
            val existing = tripTags[name]
            if (existing == null) {
                if (!deleted) tripTags[name] = TripTag(id = "trip_$name", name = name, isActive = isActive, isArchived = isArchived, updatedAt = updatedAt)
            } else if (updatedAt > existing.updatedAt) {
                tripTags[name] = existing.copy(isActive = isActive, isArchived = isArchived, updatedAt = updatedAt, deleted = deleted)
            }
        }

        override suspend fun applyRemoteSubscription(subscription: Subscription, updatedAt: Long, deleted: Boolean) {
            val existing = subscriptions[subscription.id]
            if (existing == null) {
                if (!deleted) subscriptions[subscription.id] = subscription.copy(updatedAt = updatedAt)
            } else if (updatedAt > existing.updatedAt) {
                subscriptions[subscription.id] = if (deleted) existing.copy(active = false, deleted = true, updatedAt = updatedAt) else subscription.copy(updatedAt = updatedAt)
            }
        }

        override suspend fun applyRemotePreference(key: String, value: String?, updatedAt: Long, deleted: Boolean) {
            val existing = preferences[key]
            if (existing == null) {
                if (!deleted && value != null) preferences[key] = SyncedPreference(key, value, updatedAt, false)
            } else if (updatedAt > existing.updatedAt) {
                if (deleted || value == null) {
                    preferences.remove(key)
                } else {
                    preferences[key] = SyncedPreference(key, value, updatedAt, false)
                }
            }
        }

        override suspend fun markAllLocalItemsDirty() {
            dirtyAccounts.addAll(accounts.keys)
            dirtyCategories.addAll(categories.keys)
            dirtyCompanions.addAll(companions.keys)
            dirtyTripTags.addAll(tripTags.keys)
            dirtySubscriptions.addAll(subscriptions.keys)
            dirtyPreferences.addAll(preferences.keys)
        }
    }

    private class TestConfigRepository : ConfigRepository {
        val rows = mutableListOf<ConfigRow>()

        override suspend fun readAll(spreadsheetId: String): Result<List<ConfigRow>> = Result.Success(rows.toList())

        override suspend fun upsert(spreadsheetId: String, rows: List<ConfigRow>): Result<Unit> {
            for (incoming in rows) {
                val existing = this.rows.find { it.compositeKey == incoming.compositeKey }
                if (existing != null) {
                    // Conflict resolution: only overwrite if incoming is newer or equal
                    if (incoming.updatedAt >= existing.updatedAt) {
                        this.rows.remove(existing)
                        this.rows.add(incoming)
                    }
                } else {
                    this.rows.add(incoming)
                }
            }
            return Result.Success(Unit)
        }

        override suspend fun tombstone(spreadsheetId: String, type: String, key: String, deviceId: String): Result<Unit> =
            Result.Success(Unit)
    }

    @Test
    fun tombstonePreferenceKeysAreExcludedFromKeptKeysAndWipedOnAccountSwitch() {
        val tombstoneSampleKey = "tombstone:category:Food"
        // 1. Must NOT be in KEPT_PREFERENCE_KEYS (which survives an account wipe)
        assertFalse(
            "Tombstone keys must not be kept during account switch wipe",
            RoomAccountDataStore.KEPT_PREFERENCE_KEYS.contains(tombstoneSampleKey)
        )
        assertFalse(
            "No tombstone prefix can be retained in kept preference keys",
            RoomAccountDataStore.KEPT_PREFERENCE_KEYS.any { it.startsWith("tombstone:") }
        )

        // 2. Simulate account wipe filter: WHERE `key` NOT IN (KEPT_PREFERENCE_KEYS)
        val allPrefs = listOf(
            PreferenceEntity("key_theme_mode", "DARK"),
            PreferenceEntity("tombstone:category:Food", "deleted"),
            PreferenceEntity("tombstone:account:HDFC", "deleted")
        )
        val afterWipe = allPrefs.filter { RoomAccountDataStore.KEPT_PREFERENCE_KEYS.contains(it.key) }
        assertEquals(1, afterWipe.size)
        assertEquals("key_theme_mode", afterWipe[0].key)
        assertTrue("All tombstones are wiped on account switch", afterWipe.none { it.key.startsWith("tombstone:") })
    }

    @Test
    fun deletionsReachConfigSheetForEachKind() = runBlocking {
        val gateway = TestLocalGateway()
        val repo = TestConfigRepository()
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        prefs.setSpreadsheetId("sheet_123")
        val useCase = SyncConfigUseCase(
            configRepository = repo,
            localGateway = gateway,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Record deletions for each kind
        val now = 1_700_000_100_000L
        gateway.recordTombstone(ConfigConstants.TYPE_ACCOUNT, "HDFC", now)
        gateway.recordTombstone(ConfigConstants.TYPE_CATEGORY, "Entertainment", now)
        gateway.recordTombstone(ConfigConstants.TYPE_PERSON, "Bob", now)
        gateway.recordTombstone(ConfigConstants.TYPE_TRIP, "Paris2025", now)
        gateway.recordTombstone(ConfigConstants.TYPE_SUBSCRIPTION, "sub_spotify", now)

        // Delete preference
        gateway.preferences["custom_pref"] = SyncedPreference("custom_pref", null, now, dirty = true)
        gateway.dirtyPreferences.add("custom_pref")

        // Push
        val result = useCase.push()
        assertTrue("Push should succeed", result is Result.Success)

        // Verify each deletion reached the Config sheet
        val rows = repo.rows

        val accRow = rows.find { it.type == ConfigConstants.TYPE_ACCOUNT && it.key == "HDFC" }
        assertNotNull(accRow)
        assertTrue("Account deletion pushed with deleted=true", accRow!!.deleted)

        val catRow = rows.find { it.type == ConfigConstants.TYPE_CATEGORY && it.key == "Entertainment" }
        assertNotNull(catRow)
        assertTrue("Category deletion pushed with deleted=true", catRow!!.deleted)

        val personRow = rows.find { it.type == ConfigConstants.TYPE_PERSON && it.key == "Bob" }
        assertNotNull(personRow)
        assertTrue("Person deletion pushed with deleted=true", personRow!!.deleted)

        val tripRow = rows.find { it.type == ConfigConstants.TYPE_TRIP && it.key == "Paris2025" }
        assertNotNull(tripRow)
        assertTrue("Trip deletion pushed with deleted=true", tripRow!!.deleted)

        val subRow = rows.find { it.type == ConfigConstants.TYPE_SUBSCRIPTION && it.key == "sub_spotify" }
        assertNotNull(subRow)
        assertTrue("Subscription deletion pushed with deleted=true", subRow!!.deleted)

        val prefRow = rows.find { it.type == ConfigConstants.TYPE_PREF && it.key == "custom_pref" }
        assertNotNull(prefRow)
        assertTrue("Preference deletion pushed with deleted=true", prefRow!!.deleted)

        // Verify tombstones and dirty flags are cleared locally after push
        assertTrue("Tombstones cleared locally", gateway.tombstones.isEmpty())
        assertNull("Deleted preference removed locally after push", gateway.preferences["custom_pref"])
    }

    @Test
    fun oldOfflineEditDoesNotOverwriteNewerOneOnPullOrPush() = runBlocking {
        val gateway = TestLocalGateway()
        val repo = TestConfigRepository()
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        prefs.setSpreadsheetId("sheet_123")
        val useCase = SyncConfigUseCase(
            configRepository = repo,
            localGateway = gateway,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )

        val tOld = 1_700_000_000_000L
        val tNew = 1_700_000_500_000L

        // 1. PULL conflict resolution: Local is newer (tNew), incoming remote is older (tOld)
        gateway.categories["Food"] = Category(
            id = "cat_food", name = "Food", isDefault = false, isExcludedFromLivingBurn = false,
            isArchived = false, displayOrder = 1, iconName = "Emerald", updatedAt = tNew
        )

        repo.rows.add(
            ConfigRow(
                type = ConfigConstants.TYPE_CATEGORY,
                key = "Food",
                values = listOf("true", "false", "false", "5|Orange"),
                updatedAt = Instant.ofEpochMilli(tOld),
                updatedBy = "other_device",
                deleted = false
            )
        )

        useCase.pull()

        // Local state must NOT be overwritten by the older remote row
        val localCat = gateway.categories["Food"]
        assertNotNull(localCat)
        assertEquals("Emerald", localCat!!.iconName)
        assertEquals(1, localCat.displayOrder)
        assertEquals(tNew, localCat.updatedAt)

        // 2. PUSH conflict resolution: Remote has newer row (tNew), local offline edit was older (tOld)
        repo.rows.clear()
        repo.rows.add(
            ConfigRow(
                type = ConfigConstants.TYPE_CATEGORY,
                key = "Shopping",
                values = listOf("false", "false", "false", "1|Ruby"),
                updatedAt = Instant.ofEpochMilli(tNew),
                updatedBy = "cloud_device",
                deleted = false
            )
        )

        gateway.categories["Shopping"] = Category(
            id = "cat_shop", name = "Shopping", isDefault = false, isExcludedFromLivingBurn = false,
            isArchived = false, displayOrder = 9, iconName = "Teal", updatedAt = tOld
        )
        gateway.dirtyCategories.add("Shopping")

        useCase.push()

        // Remote row in sheet must remain the newer cloud edit (tNew, Ruby), not overwritten by tOld
        val remoteShop = repo.rows.find { it.key == "Shopping" }
        assertNotNull(remoteShop)
        assertEquals(Instant.ofEpochMilli(tNew), remoteShop!!.updatedAt)
        assertTrue(remoteShop.value4.contains("Ruby"))
    }

    @Test
    fun oldConfigRowsWithoutPipeStillReadCorrectlyAndDefaultColorToTeal() = runBlocking {
        val gateway = TestLocalGateway()
        val repo = TestConfigRepository()
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        prefs.setSpreadsheetId("sheet_123")
        val useCase = SyncConfigUseCase(
            configRepository = repo,
            localGateway = gateway,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Older Config row without "|" in value4 (only displayOrder "7")
        repo.rows.add(
            ConfigRow(
                type = ConfigConstants.TYPE_CATEGORY,
                key = "Bills",
                values = listOf("false", "false", "false", "7"),
                updatedAt = Instant.ofEpochMilli(1_700_000_000_000L),
                updatedBy = "legacy_client",
                deleted = false
            )
        )

        useCase.pull()

        val bills = gateway.categories["Bills"]
        assertNotNull(bills)
        assertEquals("Display order parsed cleanly", 7, bills!!.displayOrder)
        assertEquals("Missing color safely defaults to Teal", "Teal", bills.iconName)
    }

    @Test
    fun categoryChosenColorPreservedThroughPushAndPull() = runBlocking {
        val gateway = TestLocalGateway()
        val repo = TestConfigRepository()
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        prefs.setSpreadsheetId("sheet_123")
        val useCase = SyncConfigUseCase(
            configRepository = repo,
            localGateway = gateway,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )

        val editTime = 1_700_000_200_000L
        gateway.categories["Travel"] = Category(
            id = "cat_travel", name = "Travel", isDefault = false, isExcludedFromLivingBurn = false,
            isArchived = false, displayOrder = 3, iconName = "Indigo", updatedAt = editTime
        )
        gateway.dirtyCategories.add("Travel")

        // 1. Push: verify value4 is formatted as displayOrder|iconName
        useCase.push()
        val row = repo.rows.find { it.key == "Travel" }
        assertNotNull(row)
        assertEquals("3|Indigo", row!!.value4)
        assertEquals(Instant.ofEpochMilli(editTime), row.updatedAt)

        // 2. Pull on a fresh gateway: verify color round trips back to Indigo
        val freshGateway = TestLocalGateway()
        val freshUseCase = SyncConfigUseCase(
            configRepository = repo,
            localGateway = freshGateway,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )

        freshUseCase.pull()
        val pulled = freshGateway.categories["Travel"]
        assertNotNull(pulled)
        assertEquals(3, pulled!!.displayOrder)
        assertEquals("Indigo", pulled.iconName)
        assertEquals(editTime, pulled.updatedAt)
    }
}
