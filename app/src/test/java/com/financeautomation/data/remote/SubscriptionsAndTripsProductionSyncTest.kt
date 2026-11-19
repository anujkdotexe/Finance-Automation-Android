package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.model.SubscriptionCycleWithSubscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.SubscriptionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionsAndTripsProductionSyncTest {

    private class FakeTransactionDao : TransactionDao {
        val transactions = ConcurrentHashMap<String, TransactionEntity>()
        override fun getTransactionsFlow(): Flow<List<TransactionEntity>> = flowOf(transactions.values.toList())
        override fun getNeedsReviewFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override fun getConfirmedUnsyncedFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override fun getAccountTransactionCounts(from: Long, to: Long) = emptyFlow<List<com.financeautomation.data.local.dao.AccountTransactionCount>>()
        override suspend fun getById(id: String): TransactionEntity? = transactions[id]
        override suspend fun getBySheetRowIndex(rowIndex: Int): TransactionEntity? = null
        override suspend fun getAllBySheetRowIndex(rowIndex: Int): List<TransactionEntity> = emptyList()
        override suspend fun getRecentSimilar(minTimestamp: Long, maxTimestamp: Long, minAmount: Double, maxAmount: Double) = emptyList<TransactionEntity>()
        override suspend fun insert(transaction: TransactionEntity) { transactions[transaction.id] = transaction }
        override suspend fun insertAll(transactions: List<TransactionEntity>) { transactions.forEach { insert(it) } }
        override suspend fun update(transaction: TransactionEntity) { transactions[transaction.id] = transaction }
        override suspend fun delete(transaction: TransactionEntity) { transactions.remove(transaction.id) }
        override suspend fun deleteById(id: String) { transactions.remove(id) }
        override suspend fun voidTransaction(id: String, voidTitle: String) {}
        override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {}
        override suspend fun updateReviewState(id: String, reviewState: String, category: String?) {}
        override suspend fun updateAccountName(oldName: String, newName: String): Int = 0
        override suspend fun updateCategoryName(oldName: String, newName: String): Int = 0
        override suspend fun updateCompanionName(oldName: String, newName: String): Int = 0
        override suspend fun getAllForYear(startMillis: Long, endMillis: Long) = emptyList<TransactionEntity>()
        override suspend fun resetSyncStateForRequeue(id: String) {}
    }

    private class FakeSyncQueueDao : SyncQueueDao {
        val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
        private val idGen = AtomicInteger(1)
        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(queue.values.toList())
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override suspend fun insert(item: SyncQueueEntity): Long {
            val id = if (item.id == 0L) idGen.getAndIncrement().toLong() else item.id
            queue[id] = item.copy(id = id)
            return id
        }
        override suspend fun update(item: SyncQueueEntity) { queue[item.id] = item }
        override suspend fun delete(item: SyncQueueEntity) { queue.remove(item.id) }
        override suspend fun deleteById(id: Long) { queue.remove(id) }
        override suspend fun getById(id: Long): SyncQueueEntity? = queue[id]
        override suspend fun deleteByTransactionId(transactionId: String) {}
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = null
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> =
            queue.values.filter { it.status == SyncQueueEntity.STATUS_PENDING }.sortedBy { it.createdAt }
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {}
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount) }
        }
        override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {}
        override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {}
        override suspend fun resetQueueItem(id: Long) {
            queue[id]?.let { queue[id] = it.copy(status = SyncQueueEntity.STATUS_PENDING, retryCount = 0, lastErrorKind = null, lastErrorDetail = null) }
        }
        override suspend fun recoverRetryableFailedItems(): Int = 0
        override suspend fun countPendingOperations(opType: String): Int =
            queue.values.count { it.operationType == opType && it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun updatePlanJson(id: Long, planJson: String?) {}
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
        override suspend fun getActiveTransactionIds(): List<String> = emptyList()
        override suspend fun deletePendingForTransactions(transactionIds: List<String>) {}
    }

    private class FakeSubscriptionRepository : SubscriptionRepository {
        val subs = ConcurrentHashMap<String, Subscription>()
        override fun observeSubscriptions(): Flow<List<Subscription>> = flowOf(subs.values.toList())
        override fun observeOpenCycles(): Flow<List<SubscriptionCycleWithSubscription>> = flowOf(emptyList())
        override fun observeNeedsReviewCycles(): Flow<List<SubscriptionCycleWithSubscription>> = flowOf(emptyList())
        override suspend fun getSubscription(id: String): Subscription? = subs[id]
        override suspend fun getActiveSubscriptions(): List<Subscription> = subs.values.filter { it.active }
        override suspend fun upsertSubscription(subscription: Subscription) { subs[subscription.id] = subscription }
        override suspend fun deleteSubscription(id: String) { subs.remove(id) }
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

    private class FakeTripTagRepository : TripTagRepository {
        val trips = ConcurrentHashMap<String, TripTag>()
        var activeTagId: String? = null
        override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> =
            flowOf(trips.values.filter { includeArchived || !it.isArchived })
        override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> =
            trips.values.filter { includeArchived || !it.isArchived }
        override suspend fun getActiveTripTags(): List<TripTag> =
            trips.values.filter { it.isActive && !it.isArchived }
        override fun getActiveTag(): Flow<TripTag?> = flowOf(activeTagId?.let { trips[it] })
        override suspend fun getActiveTagSync(): TripTag? = activeId
        private val activeId: TripTag? get() = activeTagId?.let { trips[it] }
        override suspend fun getTagById(id: String): TripTag? = trips[id]
        override suspend fun getTagByName(name: String): TripTag? = trips.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun insertTripTag(name: String): TripTag {
            val trip = TripTag(id = "trip_${System.currentTimeMillis()}", name = name, isActive = false)
            trips[trip.id] = trip
            return trip
        }
        override suspend fun updateTripTag(tripTag: TripTag) {
            trips[tripTag.id] = tripTag
            if (tripTag.isActive) activeTagId = tripTag.id
            else if (activeTagId == tripTag.id) activeTagId = null
        }
        override suspend fun upsertTripTag(tripTag: TripTag) { updateTripTag(tripTag) }
        override suspend fun setActiveTag(id: String?) {
            activeTagId = id
            trips.values.forEach { trips[it.id] = it.copy(isActive = it.id == id) }
        }
        override suspend fun archiveTripTag(id: String) {
            trips[id]?.let { trips[id] = it.copy(isArchived = true, isActive = false) }
        }
        override suspend fun unarchiveTripTag(id: String) {
            trips[id]?.let { trips[id] = it.copy(isArchived = false) }
        }
        override suspend fun deleteTripTag(id: String) { trips.remove(id) }
        override suspend fun removeTripTag(name: String): Result<Unit> {
            trips.values.removeIf { it.name.equals(name, ignoreCase = true) }
            return Result.Success(Unit)
        }
    }

    private class FakePreferencesRepository : PreferencesRepository {
        var spreadsheetId: String? = "sheet_test_123"
        var email: String? = "test@example.com"
        val rawPrefs = ConcurrentHashMap<String, String>()
        override fun getSpreadsheetId(): Flow<String?> = flowOf(spreadsheetId)
        override suspend fun getSpreadsheetIdSync(): String? = spreadsheetId
        override suspend fun setSpreadsheetId(id: String) { spreadsheetId = id }
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(spreadsheetId)
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = spreadsheetId
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) { spreadsheetId = id }
        override fun getDefaultAccount(): Flow<String> = flowOf("Account1")
        override suspend fun getDefaultAccountSync(): String = "Account1"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(tripTag: String?) {}
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(email)
        override suspend fun getGoogleAccountEmailSync(): String? = email
        override suspend fun setGoogleAccountEmail(email: String?) { this.email = email }
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
            rawPrefs.keys.filter { it.startsWith(prefix) }.forEach { rawPrefs.remove(it) }
        }
    }

    private class FakeProductionSheetsClient : GoogleSheetsClient(okHttpClient = OkHttpClient()) {
        var remoteSubscriptionRows: List<SheetSubscriptionRow> = emptyList()
        var remoteTripRows: List<SheetTripRow> = emptyList()
        var lastWrittenSubscriptions: List<Subscription> = emptyList()
        var lastWrittenTrips: List<TripTag> = emptyList()

        override suspend fun ensureSubscriptionsTabExists(spreadsheetId: String, accounts: List<String>, year: Int?): Result<Boolean> = Result.Success(true)
        override suspend fun readSubscriptionsTab(spreadsheetId: String): Result<List<SheetSubscriptionRow>> = Result.Success(remoteSubscriptionRows)
        override suspend fun writeSubscriptionsTab(spreadsheetId: String, subscriptions: List<Subscription>, accounts: List<String>, year: Int?): Result<Unit> {
            lastWrittenSubscriptions = subscriptions
            return Result.Success(Unit)
        }
        override suspend fun ensureTripsTabExists(spreadsheetId: String, year: Int?): Result<Boolean> = Result.Success(true)
        override suspend fun readTripsTab(spreadsheetId: String): Result<List<SheetTripRow>> = Result.Success(remoteTripRows)
        override suspend fun writeTripsTab(spreadsheetId: String, trips: List<TripTag>, repaidMap: Map<String, Double>, topCategoryMap: Map<String, String>, year: Int?): Result<Unit> {
            lastWrittenTrips = trips
            return Result.Success(Unit)
        }
        override suspend fun readLedgerSnapshot(spreadsheetId: String, tabFilter: ((String) -> Boolean)?): Result<LedgerWorkbookSnapshot> =
            Result.Success(LedgerWorkbookSnapshot(spreadsheetId = spreadsheetId, monthTabs = emptyList()))
        override suspend fun updateCell(spreadsheetId: String, tabName: String, cellCoordinate: String, value: Any?, valueInputOption: String): Result<Unit> =
            Result.Success(Unit)
    }

    @Test
    fun productionSyncEntryPoint_sheetEditReachesRoom() = runBlocking {
        val fakeSheets = FakeProductionSheetsClient()
        fakeSheets.remoteSubscriptionRows = listOf(
            SheetSubscriptionRow(
                rowNumber = 4,
                name = "Netflix",
                amount = 649.0,
                frequency = "Monthly",
                dueDateString = "2026-11-20",
                account = "Canara",
                active = true,
                note = "Standard plan",
                keywords = "netflix",
                id = "sub_netflix_remote"
            )
        )
        fakeSheets.remoteTripRows = listOf(
            SheetTripRow(
                rowNumber = 4,
                name = "Goa Trip",
                status = "Active",
                startDateString = "2026-11-15",
                endDateString = "",
                id = "trip_goa_remote"
            )
        )

        val fakeSubRepo = FakeSubscriptionRepository()
        val fakeTripRepo = FakeTripTagRepository()
        val fakePrefs = FakePreferencesRepository()
        val fakeTxnDao = FakeTransactionDao()
        val fakeSyncQueueDao = FakeSyncQueueDao()

        val subCoordinator = SubscriptionsSheetSyncCoordinator(
            googleSheetsClient = fakeSheets,
            subscriptionRepository = fakeSubRepo,
            preferencesRepository = fakePrefs
        )
        val tripCoordinator = TripsSheetSyncCoordinator(
            googleSheetsClient = fakeSheets,
            tripTagRepository = fakeTripRepo,
            preferencesRepository = fakePrefs
        )

        val txnRepo = TransactionRepositoryImpl(
            transactionDao = fakeTxnDao,
            syncQueueDao = fakeSyncQueueDao,
            googleSheetsClient = fakeSheets,
            preferencesRepository = fakePrefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(sheetsClient = fakeSheets, syncQueueDao = fakeSyncQueueDao, transactionDao = fakeTxnDao),
            subscriptionsSheetSyncCoordinator = subCoordinator,
            tripsSheetSyncCoordinator = tripCoordinator
        )

        // Run production sync entry point with userInitiated = true (triggers reconcileWithSheet)
        val syncResult = txnRepo.syncPendingTransactions(userInitiated = true)
        assertTrue(syncResult is Result.Success)

        // Assert Sheet edits reached Room
        val importedSub = fakeSubRepo.getSubscription("sub_netflix_remote")
        assertNotNull("Expected Netflix subscription to be imported to Room", importedSub)
        assertEquals("Netflix", importedSub?.name)
        assertEquals(649.0, importedSub?.amount ?: 0.0, 0.001)

        val importedTrip = fakeTripRepo.getTagById("trip_goa_remote")
        assertNotNull("Expected Goa Trip to be imported to Room", importedTrip)
        assertEquals("Goa Trip", importedTrip?.name)
        assertTrue(importedTrip?.isActive == true)
    }

    @Test
    fun productionSyncEntryPoint_roomEditReachesSheet() = runBlocking {
        val fakeSheets = FakeProductionSheetsClient()
        val fakeSubRepo = FakeSubscriptionRepository()
        val fakeTripRepo = FakeTripTagRepository()
        val fakePrefs = FakePreferencesRepository()
        val fakeTxnDao = FakeTransactionDao()
        val fakeSyncQueueDao = FakeSyncQueueDao()

        // Room has a subscription and a trip
        fakeSubRepo.upsertSubscription(
            Subscription(
                id = "sub_spotify_local",
                name = "Spotify",
                amount = 119.0,
                frequency = SubscriptionFrequency.MONTHLY,
                anchorEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
                account = "Canara",
                active = true
            )
        )
        fakeTripRepo.upsertTripTag(
            TripTag(
                id = "trip_kerala_local",
                name = "Kerala",
                isActive = true,
                isArchived = false,
                startDateEpochDay = LocalDate.of(2026, 11, 10).toEpochDay(),
                endDateEpochDay = null
            )
        )

        // Queue has OP_CONFIG_PUSH (as enqueued by repository on local change)
        fakeSyncQueueDao.insert(
            SyncQueueEntity(
                id = 1L,
                transactionId = "config_push_1",
                operationType = SyncQueueEntity.OP_CONFIG_PUSH,
                payloadJson = "{}",
                status = SyncQueueEntity.STATUS_PENDING
            )
        )

        val subCoordinator = SubscriptionsSheetSyncCoordinator(
            googleSheetsClient = fakeSheets,
            subscriptionRepository = fakeSubRepo,
            preferencesRepository = fakePrefs
        )
        val tripCoordinator = TripsSheetSyncCoordinator(
            googleSheetsClient = fakeSheets,
            tripTagRepository = fakeTripRepo,
            preferencesRepository = fakePrefs
        )

        val txnRepo = TransactionRepositoryImpl(
            transactionDao = fakeTxnDao,
            syncQueueDao = fakeSyncQueueDao,
            googleSheetsClient = fakeSheets,
            preferencesRepository = fakePrefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(sheetsClient = fakeSheets, syncQueueDao = fakeSyncQueueDao, transactionDao = fakeTxnDao),
            subscriptionsSheetSyncCoordinator = subCoordinator,
            tripsSheetSyncCoordinator = tripCoordinator
        )

        // Run production sync entry point
        val syncResult = txnRepo.syncPendingTransactions(userInitiated = false)
        assertTrue(syncResult is Result.Success)

        // Assert Room edits reached Sheet
        assertEquals(1, fakeSheets.lastWrittenSubscriptions.size)
        assertEquals("Spotify", fakeSheets.lastWrittenSubscriptions[0].name)

        assertEquals(1, fakeSheets.lastWrittenTrips.size)
        assertEquals("Kerala", fakeSheets.lastWrittenTrips[0].name)
    }

    @Test
    fun baseHashesPersistAcrossCoordinatorRestart_deletedRowNotResurrected() = runBlocking {
        val fakeSheets = FakeProductionSheetsClient()
        fakeSheets.remoteSubscriptionRows = listOf(
            SheetSubscriptionRow(
                rowNumber = 4,
                name = "Netflix",
                amount = 649.0,
                frequency = "Monthly",
                dueDateString = "2026-11-20",
                account = "Canara",
                active = true,
                note = "Standard plan",
                keywords = "netflix",
                id = "sub_netflix_persisted"
            )
        )
        fakeSheets.remoteTripRows = listOf(
            SheetTripRow(
                rowNumber = 4,
                name = "Goa Trip",
                status = "Active",
                startDateString = "2026-11-15",
                endDateString = "",
                id = "trip_goa_persisted"
            )
        )

        val fakeSubRepo = FakeSubscriptionRepository()
        val fakeTripRepo = FakeTripTagRepository()
        val fakePrefs = FakePreferencesRepository()

        // First coordinator session
        val subCoordinator1 = SubscriptionsSheetSyncCoordinator(
            googleSheetsClient = fakeSheets,
            subscriptionRepository = fakeSubRepo,
            preferencesRepository = fakePrefs
        )
        val tripCoordinator1 = TripsSheetSyncCoordinator(
            googleSheetsClient = fakeSheets,
            tripTagRepository = fakeTripRepo,
            preferencesRepository = fakePrefs
        )

        // Sync initial remote rows into Room
        assertTrue(subCoordinator1.syncSubscriptionsTab("sheet_test_123") is Result.Success)
        assertTrue(tripCoordinator1.syncTripsTab("sheet_test_123") is Result.Success)

        assertNotNull(fakeSubRepo.getSubscription("sub_netflix_persisted"))
        assertNotNull(fakeTripRepo.getTagById("trip_goa_persisted"))

        // Locally delete the items in Room
        fakeSubRepo.deleteSubscription("sub_netflix_persisted")
        fakeTripRepo.deleteTripTag("trip_goa_persisted")

        // Simulate app restart: re-instantiate coordinators with fresh in-memory state
        val subCoordinator2 = SubscriptionsSheetSyncCoordinator(
            googleSheetsClient = fakeSheets,
            subscriptionRepository = fakeSubRepo,
            preferencesRepository = fakePrefs
        )
        val tripCoordinator2 = TripsSheetSyncCoordinator(
            googleSheetsClient = fakeSheets,
            tripTagRepository = fakeTripRepo,
            preferencesRepository = fakePrefs
        )

        // Sync again from sheet: because baseHashes are persisted in fakePrefs, deleted items must NOT be resurrected
        assertTrue(subCoordinator2.syncSubscriptionsTab("sheet_test_123") is Result.Success)
        assertTrue(tripCoordinator2.syncTripsTab("sheet_test_123") is Result.Success)

        assertEquals("Deleted subscription must not be resurrected from sheet after restart", null, fakeSubRepo.getSubscription("sub_netflix_persisted"))
        assertEquals("Deleted trip must not be resurrected from sheet after restart", null, fakeTripRepo.getTagById("trip_goa_persisted"))
    }
}
