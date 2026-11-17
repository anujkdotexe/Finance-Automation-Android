package com.financeautomation.domain.usecase

import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.repository.ConfigRepositoryImpl
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.DirtyConfigSnapshot
import com.financeautomation.domain.repository.SyncedPreference
import com.financeautomation.testutil.FakeConfigSheet
import com.financeautomation.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * SyncConfigUseCase through its production constructor, with the real ConfigRepositoryImpl and GoogleSheetsClient
 * on an in-memory Config tab. The local side is a small gateway for categories with last-write-wins like the real one.
 */
class SyncConfigUseCaseTest {

    private val t0 = Instant.parse("2026-11-17T10:00:00Z")
    private val clock = MutableTestClock(t0)

    @Before
    fun clearDiagnostics() {
        Diagnostics.clear()
    }

    /** Categories only; every other kind is empty. applyRemote follows the real rule: newer updatedAt wins and is clean. */
    private class CategoryGateway : ConfigSyncLocalGateway {
        val categories = mutableMapOf<String, Category>()
        val dirty = mutableSetOf<String>()

        /** Every applyRemote call that reached the gateway, as "kind:name:deleted". */
        val applied = mutableListOf<String>()

        fun edit(name: String, excluded: Boolean, updatedAt: Long) {
            val existing = categories[name]
            categories[name] = (existing ?: Category(id = "cat_$name", name = name)).copy(
                isExcludedFromLivingBurn = excluded, updatedAt = updatedAt
            )
            dirty.add("cat_$name")
        }

        override suspend fun getDirtySnapshot() = DirtyConfigSnapshot(
            accounts = emptyList(),
            categories = categories.values.filter { it.id in dirty },
            companions = emptyList(),
            tripTags = emptyList(),
            preferences = emptyList<SyncedPreference>()
        )

        override suspend fun markCategoriesClean(ids: List<String>) { dirty.removeAll(ids.toSet()) }
        override suspend fun markAccountsClean(ids: List<String>) {}
        override suspend fun markCompanionsClean(ids: List<String>) {}
        override suspend fun markTripTagsClean(ids: List<String>) {}
        override suspend fun markPreferencesClean(keys: List<String>) {}
        override suspend fun markSubscriptionsClean(ids: List<String>) {}
        override suspend fun applyRemoteSubscription(subscription: Subscription, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteAccount(
            name: String, lastFour: String?, isDefault: Boolean, isArchived: Boolean,
            displayOrder: Int, updatedAt: Long, deleted: Boolean
        ) { applied.add("account:$name:$deleted") }
        override suspend fun applyRemoteCategory(
            name: String, excludedFromBurn: Boolean, isDefault: Boolean, isArchived: Boolean,
            displayOrder: Int, updatedAt: Long, deleted: Boolean, iconName: String
        ) {
            applied.add("category:$name:$deleted")
            val existing = categories[name]
            if (existing == null || updatedAt > existing.updatedAt) {
                categories[name] = (existing ?: Category(id = "cat_$name", name = name)).copy(
                    isExcludedFromLivingBurn = excludedFromBurn, updatedAt = updatedAt, deleted = deleted
                )
                dirty.remove("cat_$name")
            }
        }
        override suspend fun applyRemoteCompanion(name: String, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteTripTag(name: String, isActive: Boolean, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemotePreference(key: String, value: String?, updatedAt: Long, deleted: Boolean) {}
        override suspend fun markAllLocalItemsDirty() {}
    }

    private class Harness(val sheet: FakeConfigSheet, val gateway: CategoryGateway, val useCase: SyncConfigUseCase)

    private fun harness(): Harness {
        val sheet = FakeConfigSheet()
        val gateway = CategoryGateway()
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        runBlocking { prefs.setSpreadsheetId("sheet") }
        val useCase = SyncConfigUseCase(
            configRepository = ConfigRepositoryImpl(sheet.client(), ioDispatcher = Dispatchers.Unconfined, clock = clock),
            localGateway = gateway,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )
        return Harness(sheet, gateway, useCase)
    }

    @Test
    fun pushKeepsItemDirtyWhenRemoteRowIsNewer() = runBlocking {
        val h = harness()
        h.sheet.addRow("category", "Groceries", "true", "false", "false", "1|Teal", t0.toString(), "other", "")
        h.gateway.edit("Groceries", excluded = false, updatedAt = t0.minusSeconds(3600).toEpochMilli())

        h.useCase.push("sheet")

        assertEquals("the sheet row is untouched", "true", h.sheet.cells[h.sheet.indexOfKey("category", "Groceries")][2])
        assertTrue("a skipped item must stay dirty", "cat_Groceries" in h.gateway.dirty)

        // The next pull brings the sheet value down and the item is clean through applyRemote
        h.useCase.pull("sheet")
        assertTrue(h.gateway.categories.getValue("Groceries").isExcludedFromLivingBurn)
        assertTrue("cat_Groceries" !in h.gateway.dirty)
    }

    private val newer = t0.plusSeconds(60).toString()

    @Test
    fun pullIgnoresTombstoneForSavings() = runBlocking {
        val h = harness()
        h.gateway.categories["Savings"] = Category(id = "cat_Savings", name = "Savings", updatedAt = 1L)
        h.sheet.addRow("category", "Savings", "false", "false", "false", "9|Teal", newer, "other", "true")
        h.sheet.addRow("category", "Transfer", "false", "false", "true", "9|Teal", newer, "other", "")
        h.sheet.addRow("category", "other", "false", "false", "false", "9|Teal", newer, "other", "TRUE")

        h.useCase.pull("sheet")

        assertTrue("no protected category row reaches the gateway", h.gateway.applied.isEmpty())
        assertEquals(false, h.gateway.categories.getValue("Savings").deleted)
        assertTrue(Diagnostics.getRecords().count { it.reason.contains("CONFIG_PROTECTED_ITEM") } == 3)
    }

    @Test
    fun pullIgnoresTombstoneForCash() = runBlocking {
        val h = harness()
        h.sheet.addRow("account", "Cash", "", "false", "false", "0", newer, "other", "true")
        h.sheet.addRow("account", " other ", "", "false", "true", "1", newer, "other", "")

        h.useCase.pull("sheet")

        assertTrue(h.gateway.applied.isEmpty())
    }

    @Test
    fun pullStillAppliesTombstonesAndEditsOfOrdinaryItems() = runBlocking {
        val h = harness()
        h.sheet.addRow("category", "Gifts", "false", "false", "false", "3|Teal", newer, "other", "true")
        h.sheet.addRow("account", "SBI", "", "false", "false", "2", newer, "other", "true")
        h.sheet.addRow("category", "Savings", "true", "false", "false", "9|Teal", newer, "other", "")

        h.useCase.pull("sheet")

        assertEquals(listOf("category:Gifts:true", "account:SBI:true", "category:Savings:false"), h.gateway.applied)
    }

    @Test
    fun pushMarksCleanTheItemsTheSheetTook() = runBlocking {
        val h = harness()
        h.gateway.edit("Fuel", excluded = true, updatedAt = t0.toEpochMilli())

        h.useCase.push("sheet")

        assertEquals("true", h.sheet.cells[h.sheet.indexOfKey("category", "Fuel")][2])
        assertTrue(h.gateway.dirty.isEmpty())
    }

    @Test
    fun futureDatedRemoteRowDoesNotBlockLaterLocalEdit() = runBlocking {
        val h = harness()
        h.sheet.addRow("category", "Groceries", "true", "false", "false", "1|Teal", "2099-01-01T00:00:00Z", "other", "")

        h.useCase.pull("sheet")
        assertEquals("the pulled stamp is cut to now plus 5 minutes", t0.plusSeconds(300).toEpochMilli(), h.gateway.categories.getValue("Groceries").updatedAt)

        clock.set(t0.plusSeconds(600))
        h.gateway.edit("Groceries", excluded = false, updatedAt = clock.millis())
        h.useCase.push("sheet")

        val row = h.sheet.cells[h.sheet.indexOfKey("category", "Groceries")]
        assertEquals("the later local edit reached the sheet", "false", row[2])
        assertEquals(clock.instant().toString(), row[6])
        assertTrue("and the item is clean", h.gateway.dirty.isEmpty())
    }
}
