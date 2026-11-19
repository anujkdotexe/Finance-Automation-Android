package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.model.SubscriptionCycleWithSubscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.SubscriptionRepository
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class SubscriptionsSheetSyncTest {

    private lateinit var fakeSheetsClient: FakeGoogleSheetsClient
    private lateinit var fakeSubRepository: FakeSubscriptionRepository
    private lateinit var fakePrefsRepository: FakePreferencesRepository
    private lateinit var coordinator: SubscriptionsSheetSyncCoordinator

    private var pushCalledCount = 0

    @Before
    fun setUp() {
        Diagnostics.clear()
        pushCalledCount = 0
        fakeSheetsClient = FakeGoogleSheetsClient()
        fakeSubRepository = FakeSubscriptionRepository()
        fakePrefsRepository = FakePreferencesRepository()

        val fakeSyncConfigUseCase = object : SyncConfigUseCase(
            configRepository = object : com.financeautomation.domain.repository.ConfigRepository {
                override suspend fun readAll(spreadsheetId: String): Result<List<com.financeautomation.domain.model.ConfigRow>> = Result.Success(emptyList())
                override suspend fun upsert(spreadsheetId: String, rows: List<com.financeautomation.domain.model.ConfigRow>): Result<Set<String>> = Result.Success(emptySet())
                override suspend fun tombstone(spreadsheetId: String, type: String, key: String, deviceId: String): Result<Unit> = Result.Success(Unit)
            },
            localGateway = object : com.financeautomation.domain.repository.ConfigSyncLocalGateway {
                override suspend fun getDirtySnapshot() = com.financeautomation.domain.repository.DirtyConfigSnapshot(
                    emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
                )
                override suspend fun markAccountsClean(ids: List<String>) {}
                override suspend fun markCategoriesClean(ids: List<String>) {}
                override suspend fun markCompanionsClean(ids: List<String>) {}
                override suspend fun markTripTagsClean(ids: List<String>) {}
                override suspend fun markPreferencesClean(keys: List<String>) {}
                override suspend fun markSubscriptionsClean(ids: List<String>) {}
                override suspend fun markAllLocalItemsDirty() {}
                override suspend fun applyRemoteAccount(name: String, lastFour: String?, isDefault: Boolean, isArchived: Boolean, displayOrder: Int, updatedAt: Long, deleted: Boolean) {}
                override suspend fun applyRemoteCategory(name: String, excludedFromBurn: Boolean, isDefault: Boolean, isArchived: Boolean, displayOrder: Int, updatedAt: Long, deleted: Boolean, iconName: String) {}
                override suspend fun applyRemoteCompanion(name: String, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
                override suspend fun applyRemoteTripTag(name: String, isActive: Boolean, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
                override suspend fun applyRemotePreference(key: String, value: String?, updatedAt: Long, deleted: Boolean) {}
                override suspend fun applyRemoteSubscription(subscription: Subscription, updatedAt: Long, deleted: Boolean) {}
                override suspend fun applyRemoteBudget(category: String, monthlyAmount: Double, updatedAt: Long, deleted: Boolean) {}
            },
            preferencesRepository = fakePrefsRepository
        ) {
            override suspend fun push(targetSpreadsheetId: String?): Result<Unit> {
                pushCalledCount++
                return Result.Success(Unit)
            }
        }

        coordinator = SubscriptionsSheetSyncCoordinator(
            googleSheetsClient = fakeSheetsClient,
            subscriptionRepository = fakeSubRepository,
            preferencesRepository = fakePrefsRepository,
            accountRepository = null,
            syncConfigUseCase = fakeSyncConfigUseCase
        )
    }

    @Test
    fun buildSubscriptionsRequests_verifiesHeadersAndValidations() {
        val requests = SheetProvisioner.buildSubscriptionsRequests(
            sheetId = 12345,
            accounts = listOf("Canara", "HDFC"),
            rowCount = 500,
            columnCount = 10
        )

        // Ensure grid size request exists
        val propReq = requests.firstOrNull { it.containsKey("updateSheetProperties") }
        assertNotNull(propReq)

        // Banner merge request exists
        val mergeReq = requests.firstOrNull { it.containsKey("mergeCells") }
        assertNotNull(mergeReq)

        // Header updateCells request exists at row index 2 (Row 3)
        val headerReq = requests.firstOrNull {
            val updateCells = it["updateCells"] as? Map<*, *>
            val start = updateCells?.get("start") as? Map<*, *>
            start?.get("rowIndex") == 2
        }
        assertNotNull(headerReq)

        // Hidden column J request
        val hideReq = requests.firstOrNull {
            val dimProps = it["updateDimensionProperties"] as? Map<*, *>
            val range = dimProps?.get("range") as? Map<*, *>
            range?.get("startIndex") == 9 && range.get("endIndex") == 10
        }
        assertNotNull(hideReq)
    }

    @Test
    fun buildPaidThisMonthFormula_matchesCategoryAndNameOverDebits() {
        val formula = SheetProvisioner.buildPaidThisMonthFormula(4)
        assertTrue(formula.contains("!G4:G"))
        assertTrue(formula.contains("!F4:F"))
        assertTrue(formula.contains("Subscriptions"))
        assertTrue(formula.contains("!D4:D"))
        assertTrue(formula.contains("A4"))
    }

    @Test
    fun syncSubscriptionsTab_updatesRoomWhenSheetHasEdits() = runBlocking {
        val existing = Subscription(
            id = "sub_netflix",
            name = "Netflix",
            amount = 649.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
            account = "Canara",
            note = "Standard plan",
            keywords = "netflix"
        )
        fakeSubRepository.upsertSubscription(existing)

        // Sheet row has updated amount 799 and updated note "Premium 4K"
        fakeSheetsClient.subscriptionsRows = listOf(
            SheetSubscriptionRow(
                rowNumber = 4,
                name = "Netflix",
                amount = 799.0,
                frequency = "Monthly",
                dueDateString = "2026-11-01",
                account = "Canara",
                active = true,
                note = "Premium 4K",
                keywords = "netflix",
                id = "sub_netflix"
            )
        )

        val res = coordinator.syncSubscriptionsTab("sheet123")
        assertTrue(res is Result.Success)

        val updated = fakeSubRepository.getSubscription("sub_netflix")
        assertNotNull(updated)
        assertEquals(799.0, updated?.amount)
        assertEquals("Premium 4K", updated?.note)
        assertTrue("Expected push to rewrite Config JSON mirror", pushCalledCount > 0)
    }

    @Test
    fun syncSubscriptionsTab_sheetWinsOnConflict_recordsDiagnosticCode() = runBlocking {
        val existing = Subscription(
            id = "sub_spotify",
            name = "Spotify",
            amount = 119.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = LocalDate.of(2026, 11, 15).toEpochDay(),
            account = "Canara",
            note = "Old note"
        )
        fakeSubRepository.upsertSubscription(existing)

        // Sheet has conflicting frequency and note
        fakeSheetsClient.subscriptionsRows = listOf(
            SheetSubscriptionRow(
                rowNumber = 4,
                name = "Spotify",
                amount = 119.0,
                frequency = "Yearly",
                dueDateString = "2026-11-15",
                account = "Canara",
                active = true,
                note = "Annual student discount",
                keywords = "",
                id = "sub_spotify"
            )
        )

        coordinator.syncSubscriptionsTab("sheet123")

        val inRoom = fakeSubRepository.getSubscription("sub_spotify")
        assertEquals(SubscriptionFrequency.YEARLY, inRoom?.frequency)
        assertEquals("Annual student discount", inRoom?.note)

        // Must record counted code CONFLICT_SHEET_WON
        val hasDiagnostic = Diagnostics.getRecords().any {
            it.reason.contains("CONFLICT_SHEET_WON") || it.tag.contains("CONFLICT_SHEET_WON")
        }
        assertTrue("Expected CONFLICT_SHEET_WON diagnostic to be recorded", hasDiagnostic)
    }

    @Test
    fun syncSubscriptionsTab_userAddedRowWithoutId_generatesUuidAndWritesBackToCellJ() = runBlocking {
        fakeSheetsClient.subscriptionsRows = listOf(
            SheetSubscriptionRow(
                rowNumber = 5,
                name = "Gym Membership",
                amount = 1500.0,
                frequency = "Monthly",
                dueDateString = "2026-12-01",
                account = "HDFC",
                active = true,
                note = "Gold gym",
                keywords = "gym,fitness",
                id = null
            )
        )

        val res = coordinator.syncSubscriptionsTab("sheet123")
        assertTrue(res is Result.Success)

        // Room must now have a subscription for Gym Membership
        val activeSubs = fakeSubRepository.getActiveSubscriptions()
        val gymSub = activeSubs.firstOrNull { it.name == "Gym Membership" }
        assertNotNull(gymSub)
        assertTrue(gymSub!!.id.startsWith("sub_"))
        assertEquals(1500.0, gymSub.amount)
        assertEquals("Gold gym", gymSub.note)

        // Cell J5 must have been written back with the generated ID
        val writtenId = fakeSheetsClient.updatedCells["${SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME}!J5"]
        assertEquals(gymSub.id, writtenId)
    }

    @Test
    fun mirrorToSheet_writesActiveSubscriptions() = runBlocking {
        fakeSubRepository.upsertSubscription(
            Subscription(
                id = "sub_1",
                name = "iCloud",
                amount = 75.0,
                frequency = SubscriptionFrequency.MONTHLY,
                anchorEpochDay = LocalDate.of(2026, 11, 5).toEpochDay(),
                account = "Canara",
                note = "50GB storage",
                active = true
            )
        )

        val res = coordinator.mirrorToSheet("sheet123")
        assertTrue(res is Result.Success)
        assertEquals(1, fakeSheetsClient.lastWrittenSubscriptions.size)
        assertEquals("iCloud", fakeSheetsClient.lastWrittenSubscriptions[0].name)
        assertEquals("50GB storage", fakeSheetsClient.lastWrittenSubscriptions[0].note)
    }

    // Fakes
    private class FakeGoogleSheetsClient : GoogleSheetsClient(
        okHttpClient = okhttp3.OkHttpClient()
    ) {
        var subscriptionsRows: List<SheetSubscriptionRow> = emptyList()
        val updatedCells = mutableMapOf<String, Any?>()
        var lastWrittenSubscriptions: List<Subscription> = emptyList()

        override suspend fun ensureSubscriptionsTabExists(
            spreadsheetId: String,
            accounts: List<String>
        ): Result<Boolean> = Result.Success(true)

        override suspend fun readSubscriptionsTab(
            spreadsheetId: String
        ): Result<List<SheetSubscriptionRow>> = Result.Success(subscriptionsRows)

        override suspend fun writeSubscriptionsTab(
            spreadsheetId: String,
            subscriptions: List<Subscription>,
            accounts: List<String>
        ): Result<Unit> {
            lastWrittenSubscriptions = subscriptions
            return Result.Success(Unit)
        }

        override suspend fun updateCell(
            spreadsheetId: String,
            tabName: String,
            cellCoordinate: String,
            value: Any?,
            valueInputOption: String
        ): Result<Unit> {
            updatedCells["$tabName!$cellCoordinate"] = value
            return Result.Success(Unit)
        }
    }

    private class FakeSubscriptionRepository : SubscriptionRepository {
        val subs = mutableMapOf<String, Subscription>()

        override fun observeSubscriptions(): Flow<List<Subscription>> = flowOf(subs.values.toList())
        override fun observeOpenCycles(): Flow<List<SubscriptionCycleWithSubscription>> = flowOf(emptyList())
        override fun observeNeedsReviewCycles(): Flow<List<SubscriptionCycleWithSubscription>> = flowOf(emptyList())

        override suspend fun getSubscription(id: String): Subscription? = subs[id]
        override suspend fun getActiveSubscriptions(): List<Subscription> = subs.values.filter { it.active }

        override suspend fun upsertSubscription(subscription: Subscription) {
            subs[subscription.id] = subscription
        }

        override suspend fun deleteSubscription(id: String) {
            subs.remove(id)
        }

        override suspend fun getCycle(id: String): SubscriptionCycle? = null
        override suspend fun getCycleWithSubscription(id: String): SubscriptionCycleWithSubscription? = null
        override suspend fun getCyclesInWindow(fromEpochDay: Long, toEpochDay: Long): List<SubscriptionCycleWithSubscription> = emptyList()
        override suspend fun getDueCyclesUpTo(epochDay: Long): List<SubscriptionCycleWithSubscription> = emptyList()
        override suspend fun createCycleIfMissing(subscriptionId: String, dueEpochDay: Long): Boolean = false
        override suspend fun updateCycle(cycle: SubscriptionCycle) {}
        override suspend fun setCycleState(cycleId: String, state: SubscriptionCycleState, remindCount: Int?) {}
        override suspend fun markCyclePaidForSubscription(subscriptionId: String, nowEpochDay: Long, transactionId: String): Boolean = false
        override suspend fun markCyclePaid(cycleId: String, transactionId: String) {}
        override suspend fun skipPausedCycles(subscriptionId: String, fromEpochDay: Long, toEpochDay: Long) {}
    }

    private class FakePreferencesRepository : PreferencesRepository {
        val rawPrefs = mutableMapOf<String, String?>()

        override fun getSpreadsheetId(): Flow<String?> = flowOf("sheet123")
        override suspend fun getSpreadsheetIdSync(): String? = "sheet123"
        override suspend fun setSpreadsheetId(id: String) {}
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf("sheet123")
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = "sheet123"
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {}
        override fun getDefaultAccount(): Flow<String> = flowOf("Canara")
        override suspend fun getDefaultAccountSync(): String = "Canara"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(trip: String?) {}
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
        override suspend fun getGoogleAccountEmailSync(): String? = null
        override suspend fun setGoogleAccountEmail(email: String?) {}
        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(120)
        override suspend fun getSettleDelaySecondsSync(): Int = 120
        override suspend fun setSettleDelaySeconds(seconds: Int) {}
        override suspend fun getOrCreateDeviceId(): String = "device_test"
        override suspend fun getDeviceIdSync(): String? = "device_test"
        override fun getDeveloperMode(): Flow<Boolean> = flowOf(false)
        override suspend fun getDeveloperModeSync(): Boolean = false
        override suspend fun setDeveloperMode(enabled: Boolean) {}
        override fun getThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun getThemeModeSync(): ThemeMode = ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun getPreference(key: String): String? = rawPrefs[key]
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {
            if (value != null) rawPrefs[key] = value else rawPrefs.remove(key)
        }
        override suspend fun deletePreference(key: String) { rawPrefs.remove(key) }
        override suspend fun clearKeysWithPrefix(prefix: String) {
            rawPrefs.keys.filter { it.startsWith(prefix) }.toList().forEach { rawPrefs.remove(it) }
        }
    }
}
