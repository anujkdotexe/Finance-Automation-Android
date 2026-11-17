package com.financeautomation.domain.usecase

import com.financeautomation.data.repository.ConfigRepositoryImpl
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.DirtyConfigSnapshot
import com.financeautomation.domain.repository.SyncedPreference
import com.financeautomation.testutil.FakeConfigSheet
import com.financeautomation.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * SyncConfigUseCase.push maps every dirty item to the key its Config row is written under. These tests put one dirty
 * item of each of the seven kinds through the real row mapper and the real ConfigRepositoryImpl, and check that each
 * kind is cleared exactly when the sheet took it.
 */
class SyncConfigUseCasePushKeysTest {

    private val t0 = Instant.parse("2026-11-17T10:00:00Z")
    private val clock = MutableTestClock(t0)

    private class RecordingGateway(private val snapshot: DirtyConfigSnapshot) : ConfigSyncLocalGateway {
        val cleaned = mutableMapOf<String, List<String>>()

        override suspend fun getDirtySnapshot() = snapshot
        override suspend fun markAccountsClean(ids: List<String>) { cleaned["account"] = ids }
        override suspend fun markCategoriesClean(ids: List<String>) { cleaned["category"] = ids }
        override suspend fun markCompanionsClean(ids: List<String>) { cleaned["person"] = ids }
        override suspend fun markTripTagsClean(ids: List<String>) { cleaned["trip"] = ids }
        override suspend fun markPreferencesClean(keys: List<String>) { cleaned["pref"] = keys }
        override suspend fun markSubscriptionsClean(ids: List<String>) { cleaned["subscription"] = ids }
        override suspend fun markBudgetsClean(categories: List<String>) { cleaned["budget"] = categories }
        override suspend fun applyRemoteSubscription(subscription: Subscription, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteAccount(
            name: String, lastFour: String?, isDefault: Boolean, isArchived: Boolean,
            displayOrder: Int, updatedAt: Long, deleted: Boolean
        ) {}
        override suspend fun applyRemoteCompanion(name: String, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteTripTag(name: String, isActive: Boolean, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemotePreference(key: String, value: String?, updatedAt: Long, deleted: Boolean) {}
        override suspend fun markAllLocalItemsDirty() {}
    }

    private fun snapshot(updatedAt: Long) = DirtyConfigSnapshot(
        accounts = listOf(Account(id = "acc_1", name = "HDFC", updatedAt = updatedAt)),
        // Different case and surrounding spaces: the composite key must still match the sheet row
        categories = listOf(Category(id = "cat_1", name = " Mixed Case ", updatedAt = updatedAt)),
        companions = listOf(Companion(id = "comp_1", name = "Asha", updatedAt = updatedAt)),
        tripTags = listOf(TripTag(id = "trip_1", name = "Goa", updatedAt = updatedAt)),
        preferences = listOf(SyncedPreference("key_default_account", "HDFC", updatedAt, true)),
        subscriptions = listOf(
            Subscription(
                id = "sub_1", name = "Netflix", amount = 199.0, frequency = SubscriptionFrequency.MONTHLY,
                anchorEpochDay = 20_000L, account = "HDFC", updatedAt = updatedAt
            )
        ),
        budgets = listOf(Budget(category = "Food", monthlyAmount = 5000.0, updatedAt = updatedAt))
    )

    private val expectedIds = mapOf(
        "account" to listOf("acc_1"),
        "category" to listOf("cat_1"),
        "person" to listOf("comp_1"),
        "trip" to listOf("trip_1"),
        "pref" to listOf("key_default_account"),
        "subscription" to listOf("sub_1"),
        "budget" to listOf("Food")
    )

    private fun useCase(sheet: FakeConfigSheet, gateway: ConfigSyncLocalGateway): SyncConfigUseCase {
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        runBlocking { prefs.setSpreadsheetId("sheet") }
        return SyncConfigUseCase(
            configRepository = ConfigRepositoryImpl(sheet.client(), ioDispatcher = Dispatchers.Unconfined, clock = clock),
            localGateway = gateway,
            preferencesRepository = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun pushMarksCleanEveryItemTypeWhenTheSheetTookIt() = runBlocking {
        val sheet = FakeConfigSheet()
        val gateway = RecordingGateway(snapshot(t0.toEpochMilli()))

        val result = useCase(sheet, gateway).push("sheet")

        assertEquals(true, result is com.financeautomation.core.model.Result.Success)
        assertEquals("every kind is cleared with its own id", expectedIds, gateway.cleaned)
        assertEquals("seven rows were written", 7, sheet.cells.size - 2)
    }

    @Test
    fun pushKeepsEveryItemTypeDirtyWhenTheSheetSkippedIt() = runBlocking {
        val sheet = FakeConfigSheet()
        val newer = t0.toString()
        sheet.addRow("account", "hdfc", "", "false", "false", "0", newer, "other", "")
        // Same item as the local " Mixed Case ", spelled differently
        sheet.addRow("category", "mixed case", "false", "false", "false", "0|Teal", newer, "other", "")
        sheet.addRow("person", "ASHA", "0", "false", "", "", newer, "other", "")
        sheet.addRow("trip", "goa", "false", "false", "", "", newer, "other", "")
        sheet.addRow("pref", "key_default_account", "SBI", "", "", "", newer, "other", "")
        sheet.addRow("subscription", "sub_1", "x", "", "", "", newer, "other", "")
        sheet.addRow("budget", "food", "1", "", "", "", newer, "other", "")
        val before = sheet.cells.map { it.toList() }
        val gateway = RecordingGateway(snapshot(t0.minusSeconds(3600).toEpochMilli()))

        useCase(sheet, gateway).push("sheet")

        assertEquals("nothing is cleared when everything was skipped", expectedIds.keys.associateWith { emptyList<String>() }, gateway.cleaned)
        assertEquals("the sheet was not changed", before, sheet.cells.map { it.toList() })
    }
}
