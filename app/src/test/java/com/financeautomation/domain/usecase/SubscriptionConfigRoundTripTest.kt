package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.core.model.Result
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.ConfigRow
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.repository.ConfigRepository
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.DirtyConfigSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Mirror config and Restore config must round trip subscriptions; cycles are never mirrored. */
class SubscriptionConfigRoundTripTest {

    private class FakeGateway : ConfigSyncLocalGateway {
        val subscriptions = mutableMapOf<String, Subscription>()
        val dirty = mutableSetOf<String>()
        private val updated = mutableMapOf<String, Long>()

        override suspend fun getDirtySnapshot() = DirtyConfigSnapshot(
            accounts = emptyList(), categories = emptyList(), companions = emptyList(), tripTags = emptyList(),
            preferences = emptyList(), subscriptions = dirty.mapNotNull { subscriptions[it] }
        )
        override suspend fun markAccountsClean(ids: List<String>) {}
        override suspend fun markCategoriesClean(ids: List<String>) {}
        override suspend fun markCompanionsClean(ids: List<String>) {}
        override suspend fun markTripTagsClean(ids: List<String>) {}
        override suspend fun markPreferencesClean(keys: List<String>) {}
        override suspend fun markSubscriptionsClean(ids: List<String>) { dirty.removeAll(ids.toSet()) }
        override suspend fun applyRemoteSubscription(subscription: Subscription, updatedAt: Long, deleted: Boolean) {
            val existingTs = updated[subscription.id] ?: -1L
            if (updatedAt <= existingTs) return
            val existing = subscriptions[subscription.id]
            if (existing == null && deleted) return
            subscriptions[subscription.id] = if (deleted) existing!!.copy(active = false) else subscription
            updated[subscription.id] = updatedAt
        }
        override suspend fun applyRemoteAccount(name: String, lastFour: String?, isDefault: Boolean, isArchived: Boolean, displayOrder: Int, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteCategory(name: String, excludedFromBurn: Boolean, isDefault: Boolean, isArchived: Boolean, displayOrder: Int, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteCompanion(name: String, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteTripTag(name: String, isActive: Boolean, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemotePreference(key: String, value: String?, updatedAt: Long, deleted: Boolean) {}
        override suspend fun markAllLocalItemsDirty() { dirty.addAll(subscriptions.keys) }
    }

    private class FakeConfigRepository : ConfigRepository {
        val rows = mutableListOf<ConfigRow>()
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        val maxRunning = java.util.concurrent.atomic.AtomicInteger(0)
        var upsertDelayMs = 0L
        override suspend fun readAll(spreadsheetId: String): Result<List<ConfigRow>> = Result.Success(rows.toList())
        override suspend fun upsert(spreadsheetId: String, rows: List<ConfigRow>): Result<Set<String>> {
            val now = running.incrementAndGet()
            maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            if (upsertDelayMs > 0) kotlinx.coroutines.delay(upsertDelayMs)
            running.decrementAndGet()
            for (r in rows) {
                this.rows.removeAll { it.compositeKey == r.compositeKey }
                this.rows.add(r)
            }
            return Result.Success(rows.map { it.compositeKey }.toSet())
        }
        override suspend fun tombstone(spreadsheetId: String, type: String, key: String, deviceId: String): Result<Unit> =
            Result.Success(Unit)
    }

    private fun useCase(gateway: FakeGateway, config: FakeConfigRepository) = SyncConfigUseCase(
        configRepository = config,
        localGateway = gateway,
        preferencesRepository = PreferencesRepositoryImpl(isTestHeadless = true),
        ioDispatcher = Dispatchers.Unconfined
    )

    private val netflix = Subscription(
        id = "sub_netflix", name = "Netflix", amount = 649.0, frequency = SubscriptionFrequency.MONTHLY,
        anchorEpochDay = LocalDate.of(2026, 1, 31).toEpochDay(), account = "HDFC", category = "Subscriptions",
        remind = true, keywords = "NFLX, netflix.com", active = true, createdAt = 1_700_000_000_000L
    )
    private val gym = Subscription(
        id = "sub_gym", name = "Gym \"Pro\", Koramangala", amount = null, frequency = SubscriptionFrequency.YEARLY,
        anchorEpochDay = LocalDate.of(2024, 2, 29).toEpochDay(), account = "Cash", category = "Health",
        remind = false, keywords = "", active = false, createdAt = 1_700_000_001_000L
    )

    @Test
    fun codecRoundTripsEveryField() {
        for (sub in listOf(netflix, gym)) {
            val decoded = SubscriptionConfigCodec.decode(sub.id, SubscriptionConfigCodec.encode(sub))
            assertEquals(sub, decoded)
        }
    }

    @Test
    fun codecWritesEmptyAmountWhenItVariesAndIsoAnchor() {
        val json = SubscriptionConfigCodec.encode(gym)
        assertTrue(json.contains("\"amount\":\"\""))
        assertTrue(json.contains("\"anchor\":\"2024-02-29\""))
    }

    @Test
    fun codecRejectsGarbage() {
        assertNull(SubscriptionConfigCodec.decode("x", "not json"))
        assertNull(SubscriptionConfigCodec.decode("x", "{}"))
        assertNull(SubscriptionConfigCodec.decode("x", "{\"name\":\"A\"}"))
    }

    @Test
    fun mirrorThenRestoreBringsSubscriptionsBackOnAnotherDevice() = runBlocking {
        val deviceA = FakeGateway().apply { subscriptions[netflix.id] = netflix; subscriptions[gym.id] = gym }
        val sheet = FakeConfigRepository()

        val mirror = useCase(deviceA, sheet).pushLocalDefaults("sheet_1")
        assertTrue("mirror failed: $mirror", mirror is Result.Success)

        val subscriptionRows = sheet.rows.filter { it.type == ConfigConstants.TYPE_SUBSCRIPTION }
        assertEquals(setOf("sub_netflix", "sub_gym"), subscriptionRows.map { it.key }.toSet())
        assertTrue("only Value1 carries the JSON", subscriptionRows.all { it.value1.startsWith("{") && it.value2.isEmpty() })
        assertTrue("dirty flags cleared after push", deviceA.dirty.isEmpty())

        val deviceB = FakeGateway()
        val restore = useCase(deviceB, sheet).restoreFromCloud("sheet_1")
        assertTrue("restore failed: $restore", restore is Result.Success)
        assertEquals(netflix, deviceB.subscriptions["sub_netflix"])
        assertEquals(gym, deviceB.subscriptions["sub_gym"])
        assertTrue("restored rows are not dirty", deviceB.dirty.isEmpty())
    }

    @Test
    fun onlyDirtySubscriptionsArePushed() = runBlocking {
        val device = FakeGateway().apply { subscriptions[netflix.id] = netflix; subscriptions[gym.id] = gym; dirty.add(gym.id) }
        val sheet = FakeConfigRepository()
        useCase(device, sheet).push("sheet_1")
        assertEquals(listOf("sub_gym"), sheet.rows.filter { it.type == ConfigConstants.TYPE_SUBSCRIPTION }.map { it.key })
    }

    @Test
    fun concurrentPushesAreSerializedSoConfigRowsAreNotDuplicated() = runBlocking {
        val device = FakeGateway().apply { subscriptions[netflix.id] = netflix; subscriptions[gym.id] = gym; dirty.addAll(listOf(netflix.id, gym.id)) }
        val sheet = FakeConfigRepository().apply { upsertDelayMs = 50 }
        val useCase = useCase(device, sheet)
        val a = async(Dispatchers.Default) { useCase.push("sheet_1") }
        val b = async(Dispatchers.Default) { useCase.push("sheet_1") }
        a.await(); b.await()
        assertEquals("two pushes must never run at the same time", 1, sheet.maxRunning.get())
    }

    @Test
    fun cyclesAreNeverMirrored() = runBlocking {
        val device = FakeGateway().apply { subscriptions[netflix.id] = netflix }
        val sheet = FakeConfigRepository()
        useCase(device, sheet).pushLocalDefaults("sheet_1")
        assertTrue(sheet.rows.none { it.type.contains("cycle", ignoreCase = true) || it.value1.contains("dueEpochDay") })
        assertFalse(sheet.rows.isEmpty())
    }

    @Test
    fun remoteTombstonePausesSubscription() = runBlocking {
        val device = FakeGateway().apply { subscriptions[netflix.id] = netflix }
        val sheet = FakeConfigRepository()
        sheet.rows.add(
            ConfigRow(
                ConfigConstants.TYPE_SUBSCRIPTION, "sub_netflix",
                listOf(SubscriptionConfigCodec.encode(netflix)),
                updatedAt = java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 1000), deleted = true
            )
        )
        useCase(device, sheet).pull("sheet_1")
        assertNotNull(device.subscriptions["sub_netflix"])
        assertFalse(device.subscriptions["sub_netflix"]!!.active)
    }
}
