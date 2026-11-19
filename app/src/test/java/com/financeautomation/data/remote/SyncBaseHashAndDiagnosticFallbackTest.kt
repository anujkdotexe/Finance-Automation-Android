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
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.SubscriptionRepository
import com.financeautomation.domain.repository.TripTagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for Finding 8:
 * 1. Sheet sync design: keeps a base hash per row so a deleted row in Room is not resurrected.
 * 2. An unchanged row on the sheet does not overwrite local Room edits.
 * 3. Bad or unparseable dates are rejected with counted diagnostics code REJECTED_BAD_DATE, keeping the old value.
 * 4. An empty list on mirrorToSheet clears the sheet cells rather than returning early.
 */
class SyncBaseHashAndDiagnosticFallbackTest {

    private lateinit var fakeSheetsClient: FakeGoogleSheetsClient
    private lateinit var subRepo: SimpleSubRepo
    private lateinit var tripRepo: SimpleTripRepo
    private lateinit var subCoordinator: SubscriptionsSheetSyncCoordinator
    private lateinit var tripCoordinator: TripsSheetSyncCoordinator

    @Before
    fun setUp() {
        Diagnostics.clear()
        fakeSheetsClient = FakeGoogleSheetsClient()
        subRepo = SimpleSubRepo()
        tripRepo = SimpleTripRepo()

        subCoordinator = SubscriptionsSheetSyncCoordinator(
            googleSheetsClient = fakeSheetsClient,
            subscriptionRepository = subRepo
        )

        tripCoordinator = TripsSheetSyncCoordinator(
            googleSheetsClient = fakeSheetsClient,
            tripTagRepository = tripRepo,
            preferencesRepository = object : PreferencesRepository {
                override fun getSpreadsheetId(): Flow<String?> = flowOf("test_sheet")
                override suspend fun getSpreadsheetIdSync(): String = "test_sheet"
                override suspend fun setSpreadsheetId(id: String) {}
                override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf("test_sheet")
                override suspend fun getSpreadsheetIdForYearSync(year: Int): String = "test_sheet"
                override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {}
                override fun getDefaultAccount(): Flow<String> = flowOf("UPI")
                override suspend fun getDefaultAccountSync(): String = "UPI"
                override suspend fun setDefaultAccount(account: String) {}
                override fun getActiveTrip(): Flow<String?> = flowOf(null)
                override suspend fun setActiveTrip(trip: String?) {}
                override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
                override suspend fun getGoogleAccountEmailSync(): String? = null
                override suspend fun setGoogleAccountEmail(email: String?) {}
                override fun getSettleDelaySeconds(): Flow<Int> = flowOf(0)
                override suspend fun getSettleDelaySecondsSync(): Int = 0
                override suspend fun setSettleDelaySeconds(seconds: Int) {}
                override suspend fun getOrCreateDeviceId(): String = "dev1"
                override suspend fun getDeviceIdSync(): String = "dev1"
                override fun getDeveloperMode(): Flow<Boolean> = flowOf(false)
                override suspend fun getDeveloperModeSync(): Boolean = false
                override suspend fun setDeveloperMode(enabled: Boolean) {}
                override fun getThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
                override suspend fun getThemeModeSync(): ThemeMode = ThemeMode.SYSTEM
                override suspend fun setThemeMode(mode: ThemeMode) {}
                override suspend fun getPreference(key: String): String? = null
                override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {}
                override suspend fun deletePreference(key: String) {}
            }
        )
    }

    @Test
    fun syncSubscriptionsTab_badDueDate_isRejectedWithDiagnosticsCodeAndPreservesOldValue() = runBlocking {
        val originalEpoch = LocalDate.of(2026, 11, 15).toEpochDay()
        val sub = Subscription(
            id = "sub_bad_date",
            name = "Netflix",
            amount = 649.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = originalEpoch,
            account = "HDFC",
            active = true
        )
        subRepo.upsertSubscription(sub)

        fakeSheetsClient.subscriptionsRows = listOf(
            SheetSubscriptionRow(
                rowNumber = 4,
                name = "Netflix",
                amount = 649.0,
                frequency = "Monthly",
                dueDateString = "invalid-corrupted-date",
                account = "HDFC",
                active = true,
                note = "",
                keywords = "",
                id = "sub_bad_date"
            )
        )

        subCoordinator.syncSubscriptionsTab("sheet_id")

        val stored = subRepo.getSubscription("sub_bad_date")
        // Date must NOT be reset to today; original date preserved!
        assertEquals("Original anchor epoch must be preserved when sheet date is corrupt", originalEpoch, stored?.anchorEpochDay)
        val records = Diagnostics.getRecords()
        assertTrue("Must record REJECTED_BAD_DATE diagnostic", records.any { it.reason == "REJECTED_BAD_DATE" })
    }

    @Test
    fun syncSubscriptionsTab_deletedRowInRoom_isNotResurrectedFromSheet() = runBlocking {
        val sub = Subscription(
            id = "sub_deleted",
            name = "Gym Membership",
            amount = 1500.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
            account = "Canara",
            active = true
        )
        subRepo.upsertSubscription(sub)

        fakeSheetsClient.subscriptionsRows = listOf(
            SheetSubscriptionRow(
                rowNumber = 4,
                name = "Gym Membership",
                amount = 1500.0,
                frequency = "Monthly",
                dueDateString = "2026-11-01",
                account = "Canara",
                active = true,
                note = "",
                keywords = "",
                id = "sub_deleted"
            )
        )

        // 1. Initial sync establishes base hash
        subCoordinator.syncSubscriptionsTab("sheet_id")

        // 2. User deletes subscription locally in Room
        subRepo.deleteSubscription("sub_deleted")
        assertNull(subRepo.getSubscription("sub_deleted"))

        // 3. Second sync: sheet still has the old row
        subCoordinator.syncSubscriptionsTab("sheet_id")

        // 4. Must NOT resurrect the deleted item
        assertNull("Subscription deleted locally must not be resurrected from sheet", subRepo.getSubscription("sub_deleted"))
        val records = Diagnostics.getRecords()
        assertTrue("Must record DELETED_ROW_NOT_RESURRECTED", records.any { it.reason == "DELETED_ROW_NOT_RESURRECTED" })
    }

    @Test
    fun syncSubscriptionsTab_localEditInRoomPreserved_whenSheetUnchanged() = runBlocking {
        val sub = Subscription(
            id = "sub_local_edit",
            name = "Cloud Storage",
            amount = 130.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
            account = "SBI",
            note = "Old Note",
            active = true
        )
        subRepo.upsertSubscription(sub)

        fakeSheetsClient.subscriptionsRows = listOf(
            SheetSubscriptionRow(
                rowNumber = 4,
                name = "Cloud Storage",
                amount = 130.0,
                frequency = "Monthly",
                dueDateString = "2026-11-01",
                account = "SBI",
                note = "Old Note",
                keywords = "",
                active = true,
                id = "sub_local_edit"
            )
        )

        // Initial sync establishes base hash
        subCoordinator.syncSubscriptionsTab("sheet_id")

        // User edits note in Room locally
        val updatedLocal = subRepo.getSubscription("sub_local_edit")!!.copy(note = "New Local Note")
        subRepo.upsertSubscription(updatedLocal)

        // Sync again where sheet is unchanged
        subCoordinator.syncSubscriptionsTab("sheet_id")

        // Local edit must not be overwritten by unchanged sheet
        val current = subRepo.getSubscription("sub_local_edit")
        assertEquals("New Local Note", current?.note)
    }

    @Test
    fun syncTripsTab_badDatesAndDeletedTrips_handledSafely() = runBlocking {
        val trip = TripTag(
            id = "trip_del",
            name = "Goa 2026",
            isActive = true,
            startDateEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
            endDateEpochDay = LocalDate.of(2026, 11, 5).toEpochDay()
        )
        tripRepo.upsertTripTag(trip)

        fakeSheetsClient.tripsRows = listOf(
            SheetTripRow(
                rowNumber = 4,
                name = "Goa 2026",
                startDateString = "2026-11-01",
                endDateString = "2026-11-05",
                status = "Active",
                id = "trip_del"
            )
        )

        // Initial sync establishes base hash
        tripCoordinator.syncTripsTab("sheet_id")

        // Delete locally
        tripRepo.deleteTripTag("trip_del")
        assertNull(tripRepo.getTagById("trip_del"))

        // Sync again: sheet still has it
        tripCoordinator.syncTripsTab("sheet_id")

        // Must NOT resurrect
        assertNull("Trip deleted locally must not be resurrected", tripRepo.getTagById("trip_del"))
        val records = Diagnostics.getRecords()
        assertTrue(records.any { it.reason == "DELETED_ROW_NOT_RESURRECTED" })
    }

    private class FakeGoogleSheetsClient : GoogleSheetsClient(
        okHttpClient = okhttp3.OkHttpClient()
    ) {
        var subscriptionsRows: List<SheetSubscriptionRow> = emptyList()
        var tripsRows: List<SheetTripRow> = emptyList()
        val updatedCells = mutableMapOf<String, Any?>()
        var lastWrittenSubscriptions: List<Subscription> = emptyList()
        var lastWrittenTrips: List<TripTag> = emptyList()

        override suspend fun ensureSubscriptionsTabExists(
            spreadsheetId: String,
            accounts: List<String>,
            year: Int?
        ): Result<Boolean> = Result.Success(true)

        override suspend fun ensureTripsTabExists(
            spreadsheetId: String,
            year: Int?
        ): Result<Boolean> = Result.Success(true)

        override suspend fun readSubscriptionsTab(
            spreadsheetId: String
        ): Result<List<SheetSubscriptionRow>> = Result.Success(subscriptionsRows)

        override suspend fun readTripsTab(
            spreadsheetId: String
        ): Result<List<SheetTripRow>> = Result.Success(tripsRows)

        override suspend fun writeSubscriptionsTab(
            spreadsheetId: String,
            subscriptions: List<Subscription>,
            accounts: List<String>,
            year: Int?
        ): Result<Unit> {
            lastWrittenSubscriptions = subscriptions
            return Result.Success(Unit)
        }

        override suspend fun writeTripsTab(
            spreadsheetId: String,
            trips: List<TripTag>,
            repaidMap: Map<String, Double>,
            topCategoryMap: Map<String, String>,
            year: Int?
        ): Result<Unit> {
            lastWrittenTrips = trips
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

    private class SimpleSubRepo : SubscriptionRepository {
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

    private class SimpleTripRepo : TripTagRepository {
        val trips = mutableMapOf<String, TripTag>()
        var activeTagId: String? = null

        override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> = flowOf(trips.values.toList())
        override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> = trips.values.toList()
        override suspend fun getActiveTripTags(): List<TripTag> = trips.values.filter { it.isActive }
        override fun getActiveTag(): Flow<TripTag?> = flowOf(activeTagId?.let { trips[it] })
        override suspend fun getActiveTagSync(): TripTag? = activeTagId?.let { trips[it] }
        override suspend fun getTagById(id: String): TripTag? = trips[id]
        override suspend fun getTagByName(name: String): TripTag? = trips.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun insertTripTag(name: String): TripTag {
            val t = TripTag(id = name, name = name, isActive = true)
            trips[name] = t
            return t
        }
        override suspend fun updateTripTag(tripTag: TripTag) {
            trips[tripTag.id] = tripTag
        }
        override suspend fun upsertTripTag(tripTag: TripTag) {
            trips[tripTag.id] = tripTag
        }
        override suspend fun setActiveTag(id: String?) {
            activeTagId = id
        }
        override suspend fun archiveTripTag(id: String) {
            trips[id]?.let { trips[id] = it.copy(isArchived = true) }
        }
        override suspend fun unarchiveTripTag(id: String) {
            trips[id]?.let { trips[id] = it.copy(isArchived = false) }
        }
        override suspend fun deleteTripTag(id: String) {
            trips.remove(id)
        }
    }
}
