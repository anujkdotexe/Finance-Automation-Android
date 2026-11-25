package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class TripsSheetSyncTest {

    private lateinit var fakeSheetsClient: FakeTripsGoogleSheetsClient
    private lateinit var fakeTripRepository: FakeTripTagRepository
    private lateinit var fakePrefsRepository: FakeTripsPreferencesRepository
    private lateinit var coordinator: TripsSheetSyncCoordinator

    private var pushCalledCount = 0

    @Before
    fun setUp() {
        Diagnostics.clear()
        pushCalledCount = 0
        fakeSheetsClient = FakeTripsGoogleSheetsClient()
        fakeTripRepository = FakeTripTagRepository()
        fakePrefsRepository = FakeTripsPreferencesRepository()

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
                override suspend fun applyRemoteSubscription(subscription: com.financeautomation.domain.model.Subscription, updatedAt: Long, deleted: Boolean) {}
                override suspend fun applyRemoteBudget(category: String, monthlyAmount: Double, updatedAt: Long, deleted: Boolean) {}
            },
            preferencesRepository = fakePrefsRepository
        ) {
            override suspend fun push(targetSpreadsheetId: String?): Result<Unit> {
                pushCalledCount++
                return Result.Success(Unit)
            }
        }

        coordinator = TripsSheetSyncCoordinator(
            googleSheetsClient = fakeSheetsClient,
            tripTagRepository = fakeTripRepository,
            preferencesRepository = fakePrefsRepository,
            transactionRepository = null,
            syncConfigUseCase = fakeSyncConfigUseCase
        )
    }

    @Test
    fun testEnsureTripsTabProvisionsElevenColumnsAndHidesColK() = runBlocking {
        fakeSheetsClient.tabsMap.remove(SheetSchemaConstants.TRIPS_TAB_NAME)

        val res = fakeSheetsClient.ensureTripsTabExists("sheet_123")
        assertTrue(res is Result.Success && res.data)
        assertTrue(fakeSheetsClient.tabsMap.containsKey(SheetSchemaConstants.TRIPS_TAB_NAME))

        val requests = fakeSheetsClient.executedBatchRequests
        val dimProps = requests.mapNotNull { it["updateDimensionProperties"] as? Map<*, *> }
        val hiddenCol = dimProps.firstOrNull {
            val range = it["range"] as? Map<*, *>
            range?.get("startIndex") == SheetSchemaConstants.COL_INDEX_TRIP_ID &&
                (it["properties"] as? Map<*, *>)?.get("hiddenByUser") == true
        }
        assertNotNull("Column K (index 10) must be hidden", hiddenCol)
    }

    @Test
    fun testUserAddedRowWithoutIdGetsUuidAndWritesBackToColK() = runBlocking {
        fakeSheetsClient.sheetRows.add(
            SheetTripRow(
                rowNumber = 4,
                name = "Goa Trip",
                startDateString = "2026-11-01",
                endDateString = "",
                status = "Active",
                id = null
            )
        )

        val res = coordinator.syncTripsTab("sheet_123")
        assertTrue(res is Result.Success)

        val trips = fakeTripRepository.getAllTripTags()
        assertEquals(1, trips.size)
        val created = trips.first()
        assertEquals("Goa Trip", created.name)
        assertTrue(created.isActive)
        assertTrue(created.id.startsWith("trip_"))
        assertEquals(1, pushCalledCount)

        val updatedCell = fakeSheetsClient.updatedCells["K4"]
        assertEquals(created.id, updatedCell)
    }

    @Test
    fun testConflictSheetWinsAndEndingTripSetsEndDate() = runBlocking {
        val existing = TripTag(
            id = "trip_001",
            name = "Manali Trek",
            isActive = true,
            isArchived = false,
            startDateEpochDay = LocalDate.parse("2026-10-01").toEpochDay(),
            endDateEpochDay = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        fakeTripRepository.upsertTripTag(existing)

        // Sheet has marked it Ended
        fakeSheetsClient.sheetRows.add(
            SheetTripRow(
                rowNumber = 4,
                name = "Manali Trek",
                startDateString = "2026-10-01",
                endDateString = "2026-10-10",
                status = "Ended",
                id = "trip_001"
            )
        )

        val res = coordinator.syncTripsTab("sheet_123")
        assertTrue(res is Result.Success)

        val updated = fakeTripRepository.getTagById("trip_001")
        assertNotNull(updated)
        assertFalse(updated!!.isActive)
        assertEquals(LocalDate.parse("2026-10-10").toEpochDay(), updated.endDateEpochDay)
        assertEquals(1, pushCalledCount)

        val records = Diagnostics.getRecords()
        assertTrue(records.any { it.tag == "TripsSync" && it.reason == "CONFLICT_SHEET_WON" })
    }

    @Test
    fun testReopeningEndedTripClearsEndDate() = runBlocking {
        val existing = TripTag(
            id = "trip_002",
            name = "Kerala Tour",
            isActive = false,
            isArchived = false,
            startDateEpochDay = LocalDate.parse("2026-09-01").toEpochDay(),
            endDateEpochDay = LocalDate.parse("2026-09-15").toEpochDay(),
            createdAt = 1000L,
            updatedAt = 1000L
        )
        fakeTripRepository.upsertTripTag(existing)

        // Sheet has reopened it as Active
        fakeSheetsClient.sheetRows.add(
            SheetTripRow(
                rowNumber = 4,
                name = "Kerala Tour",
                startDateString = "2026-09-01",
                endDateString = "",
                status = "Active",
                id = "trip_002"
            )
        )

        val res = coordinator.syncTripsTab("sheet_123")
        assertTrue(res is Result.Success)

        val updated = fakeTripRepository.getTagById("trip_002")
        assertNotNull(updated)
        assertTrue(updated!!.isActive)
        assertNull(updated.endDateEpochDay)
    }

    @Test
    fun testStage3a_sheetEndedTrip_clearsActiveTagAndPreferencesAndGuaranteesAgreement() = runBlocking {
        // Goa trip was previously active in Room and in preferences
        val goa = TripTag(
            id = "trip_goa",
            name = "Goa",
            isActive = true,
            isArchived = false,
            startDateEpochDay = LocalDate.parse("2026-11-20").toEpochDay(),
            endDateEpochDay = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        fakeTripRepository.upsertTripTag(goa)
        fakeTripRepository.setActiveTag("trip_goa")
        fakePrefsRepository.setActiveTrip("Goa")

        assertEquals("Goa", fakePrefsRepository.getPreference("activeTrip"))
        assertEquals("trip_goa", fakeTripRepository.getActiveTagSync()?.id)

        // Sheet Trips tab now says Goa is Ended with an end date (22 Nov 2026)
        fakeSheetsClient.sheetRows.add(
            SheetTripRow(
                rowNumber = 4,
                name = "Goa",
                startDateString = "2026-11-20",
                endDateString = "2026-11-22",
                status = "Ended",
                id = "trip_goa"
            )
        )

        // Synchronize Trips tab
        val res = coordinator.syncTripsTab("sheet_123")
        assertTrue(res is Result.Success)

        // 1. In Room: isActive is false
        val updatedGoa = fakeTripRepository.getTagById("trip_goa")
        assertNotNull(updatedGoa)
        assertFalse("Room trip must be marked inactive", updatedGoa!!.isActive)
        assertNotNull("End date must be recorded", updatedGoa.endDateEpochDay)

        // 2. Room active tag is cleared
        assertNull("Active trip tag in repo must be null", fakeTripRepository.getActiveTagSync())

        // 3. Preferences active trip is cleared
        assertNull("Active trip in preferences must be cleared", fakePrefsRepository.getPreference("activeTrip"))

        // 4. Agreement verification across UI header and card:
        val allTrips = fakeTripRepository.getAllTripTags()
        val headerActiveCount = allTrips.count { it.isActive }
        assertEquals("Header must show 0 active", 0, headerActiveCount)

        val cardIsActive = updatedGoa.isActive
        assertFalse("Trip card must evaluate to inactive/ended, agreeing with header and sheet", cardIsActive)
    }

    @Test
    fun testStage3b_midnightIstRollover_parsesMultipleDateFormatsWithoutShift() = runBlocking {
        // Date formats used in sheet: ISO, dd/MM/yyyy, d MMM yyyy, and date serial
        val testDates = listOf(
            "2026-11-22" to LocalDate.of(2026, 11, 22).toEpochDay(),
            "22 Nov 2026" to LocalDate.of(2026, 11, 22).toEpochDay(),
            "22/11/2026" to LocalDate.of(2026, 11, 22).toEpochDay(),
            "46348" to LocalDate.of(2026, 11, 22).toEpochDay()
        )

        for ((inputStr, expectedEpoch) in testDates) {
            fakeSheetsClient.sheetRows.clear()
            fakeSheetsClient.sheetRows.add(
                SheetTripRow(
                    rowNumber = 4,
                    name = "Trip_$inputStr",
                    startDateString = inputStr,
                    endDateString = "",
                    status = "Active",
                    id = null
                )
            )
            val res = coordinator.syncTripsTab("sheet_123")
            assertTrue(res is Result.Success)

            val created = fakeTripRepository.getAllTripTags().firstOrNull { it.name == "Trip_$inputStr" }
            assertNotNull("Trip must be created for input $inputStr", created)
            assertEquals("Start date epoch day must match expected for $inputStr", expectedEpoch, created?.startDateEpochDay)

            // Verify TripDateRangeFormatter produces exact matching 'Started' string
            val formatted = com.financeautomation.domain.usecase.TripDateRangeFormatter.format(
                transactions = emptyList(),
                isActive = true,
                startDateEpochDay = created?.startDateEpochDay
            )
            assertEquals("App 'Started' date must match sheet date 22 Nov 2026", "Started 22 Nov 2026", formatted)
        }
    }

    @Test
    fun testStage3b_healsExistingTripWithShiftedStartDate() = runBlocking {
        // An existing trip whose startDateEpochDay was mistakenly set to 21 Nov (UTC bug)
        val shiftedTrip = TripTag(
            id = "trip_utc_bug",
            name = "Goa",
            isActive = false,
            isArchived = false,
            startDateEpochDay = LocalDate.of(2026, 11, 21).toEpochDay(), // 1 day earlier
            endDateEpochDay = LocalDate.of(2026, 11, 23).toEpochDay(),
            createdAt = 1000L,
            updatedAt = 1000L
        )
        fakeTripRepository.upsertTripTag(shiftedTrip)

        // The transaction occurred at 04:31 AM IST on 22 Nov 2026
        val txInstant = java.time.ZonedDateTime.of(2026, 11, 22, 4, 31, 0, 0, java.time.ZoneId.of("Asia/Kolkata")).toInstant()
        val tx = com.financeautomation.domain.model.Transaction(
            id = "tx_goa_1",
            timestamp = txInstant.toEpochMilli(),
            dateSerial = com.financeautomation.core.utils.DateSerialUtils.toDateSerial(txInstant.toEpochMilli()),
            timeSerial = 0.2,
            totalAmount = 500.0,
            myShare = 500.0,
            owedToMe = 0.0,
            merchant = "Cafe",
            category = "Food & Drink",
            account = "UPI",
            tripTag = "Goa",
            companions = "Personal",
            status = com.financeautomation.core.model.TransactionStatus.PERSONAL
        )

        val fakeTxRepo = object : com.financeautomation.domain.repository.TransactionRepository {
            override fun getTransactions(): Flow<List<com.financeautomation.domain.model.Transaction>> = flowOf(listOf(tx))
            override fun getNeedsReview(): Flow<List<com.financeautomation.domain.model.Transaction>> = flowOf(emptyList())
            override suspend fun getTransactionById(id: String) = tx
            override suspend fun insertTransaction(transaction: com.financeautomation.domain.model.Transaction): Result<Unit> = Result.Success(Unit)
            override suspend fun updateTransaction(transaction: com.financeautomation.domain.model.Transaction): Result<Unit> = Result.Success(Unit)
            override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
            override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> = Result.Success(Unit)
            override suspend fun undoRecentSave(id: String): Result<Unit> = Result.Success(Unit)
            override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<com.financeautomation.domain.model.Transaction> = emptyList()
            override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
            override suspend fun logCredit(
                settler: String,
                amount: Double,
                explicitRefs: List<String>,
                account: String,
                inflowType: String,
                reason: String,
                timestamp: Long
            ): Result<String> = Result.Success("inf_test")
            override suspend fun logPayable(
                creditor: String,
                amount: Double,
                whatFor: String,
                timestamp: Long,
                category: String
            ): Result<String> = Result.Success("pay_test")
            override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
            override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
            override suspend fun reconcileAllMonths(): Result<Int> = Result.Success(0)
            override suspend fun requeueYearForNewWorkbook(year: Int) {}
        }

        val testCoordinator = TripsSheetSyncCoordinator(
            googleSheetsClient = fakeSheetsClient,
            tripTagRepository = fakeTripRepository,
            preferencesRepository = fakePrefsRepository,
            transactionRepository = fakeTxRepo
        )

        val res = testCoordinator.mirrorToSheet("sheet_123")
        assertTrue(res is Result.Success)

        val healed = fakeTripRepository.getTagById("trip_utc_bug")
        assertNotNull(healed)
        val expectedDay = LocalDate.of(2026, 11, 22).toEpochDay()
        assertEquals("Shifted start date must be healed to match earliest transaction in Asia/Kolkata", expectedDay, healed!!.startDateEpochDay)
    }

    private class FakeTripsGoogleSheetsClient : GoogleSheetsClient(
        okHttpClient = okhttp3.OkHttpClient(),
        tokenProvider = null
    ) {
        val tabsMap = mutableMapOf<String, Int>(SheetSchemaConstants.TRIPS_TAB_NAME to 99)
        val sheetRows = mutableListOf<SheetTripRow>()
        val updatedCells = mutableMapOf<String, String>()
        val executedBatchRequests = mutableListOf<Map<String, Any?>>()

        override suspend fun getSheetTabsWithIds(spreadsheetId: String): Result<Map<String, Int>> =
            Result.Success(tabsMap.toMap())

        override suspend fun executeBatchUpdate(
            spreadsheetId: String,
            requests: List<Map<String, Any?>>
        ): Result<String> {
            executedBatchRequests.addAll(requests)
            val addSheet = requests.firstOrNull { it.containsKey("addSheet") }
            if (addSheet != null) {
                val props = (addSheet["addSheet"] as Map<*, *>)["properties"] as Map<*, *>
                val title = props["title"] as String
                tabsMap[title] = 123
            }
            return Result.Success("{}")
        }

        override suspend fun batchUpdate(
            spreadsheetId: String,
            requests: List<Map<String, Any?>>
        ): Result<Unit> {
            executedBatchRequests.addAll(requests)
            return Result.Success(Unit)
        }

        override suspend fun readTripsTab(spreadsheetId: String): Result<List<SheetTripRow>> =
            Result.Success(sheetRows.toList())

        override suspend fun updateCell(
            spreadsheetId: String,
            tabName: String,
            cellCoordinate: String,
            value: Any?,
            valueInputOption: String
        ): Result<Unit> {
            updatedCells[cellCoordinate] = value?.toString().orEmpty()
            return Result.Success(Unit)
        }
    }

    private class FakeTripTagRepository : TripTagRepository {
        private val trips = mutableMapOf<String, TripTag>()
        private var activeId: String? = null

        override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> =
            flowOf(trips.values.filter { includeArchived || !it.isArchived })

        override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> =
            trips.values.filter { includeArchived || !it.isArchived }

        override suspend fun getActiveTripTags(): List<TripTag> =
            trips.values.filter { it.isActive && !it.isArchived }

        override fun getActiveTag(): Flow<TripTag?> =
            flowOf(activeId?.let { trips[it] })

        override suspend fun getActiveTagSync(): TripTag? =
            activeId?.let { trips[it] }

        override suspend fun getTagById(id: String): TripTag? =
            trips[id]

        override suspend fun getTagByName(name: String): TripTag? =
            trips.values.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

        override suspend fun insertTripTag(name: String): TripTag {
            val id = "trip_${System.currentTimeMillis()}"
            val trip = TripTag(id = id, name = name, isActive = false)
            trips[id] = trip
            return trip
        }

        override suspend fun updateTripTag(tripTag: TripTag) {
            trips[tripTag.id] = tripTag
            if (tripTag.isActive) activeId = tripTag.id
            else if (activeId == tripTag.id) activeId = null
        }

        override suspend fun upsertTripTag(tripTag: TripTag) {
            trips[tripTag.id] = tripTag
            if (tripTag.isActive) activeId = tripTag.id
            else if (activeId == tripTag.id) activeId = null
        }

        override suspend fun setActiveTag(id: String?) {
            activeId = id
            trips.values.forEach {
                trips[it.id] = it.copy(isActive = it.id == id)
            }
        }

        override suspend fun archiveTripTag(id: String) {
            trips[id]?.let { trips[id] = it.copy(isArchived = true, isActive = false) }
        }

        override suspend fun unarchiveTripTag(id: String) {
            trips[id]?.let { trips[id] = it.copy(isArchived = false) }
        }

        override suspend fun deleteTripTag(id: String) {
            trips.remove(id)
            if (activeId == id) activeId = null
        }

        override suspend fun removeTripTag(name: String): Result<Unit> {
            trips.values.removeIf { it.name.equals(name.trim(), ignoreCase = true) }
            return Result.Success(Unit)
        }
    }

    private class FakeTripsPreferencesRepository : PreferencesRepository {
        val rawPrefs = mutableMapOf<String, String?>()

        override fun getSpreadsheetId(): Flow<String?> = flowOf("sheet_123")
        override suspend fun getSpreadsheetIdSync(): String? = "sheet_123"
        override suspend fun setSpreadsheetId(id: String) {}
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf("sheet_123")
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = "sheet_123"
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {}
        override fun getDefaultAccount(): Flow<String> = flowOf("Canara")
        override suspend fun getDefaultAccountSync(): String = "Canara"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(rawPrefs["activeTrip"])
        override suspend fun setActiveTrip(tripTag: String?) {
            if (tripTag != null) rawPrefs["activeTrip"] = tripTag else rawPrefs.remove("activeTrip")
        }
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
        override fun getThemeMode(): Flow<com.financeautomation.domain.model.ThemeMode> = flowOf(com.financeautomation.domain.model.ThemeMode.SYSTEM)
        override suspend fun getThemeModeSync(): com.financeautomation.domain.model.ThemeMode = com.financeautomation.domain.model.ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: com.financeautomation.domain.model.ThemeMode) {}
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
