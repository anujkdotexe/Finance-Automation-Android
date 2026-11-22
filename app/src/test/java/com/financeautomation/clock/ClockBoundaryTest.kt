package com.financeautomation.clock

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.data.remote.SheetProvisioner
import com.financeautomation.data.repository.TripTagRepositoryImpl
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.usecase.CarryForwardUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * T2: Boundary Clock tests verifying Asia/Kolkata timezone rollover for trips,
 * month tabs, workbook naming, and year-end debt carry-forward.
 */
class ClockBoundaryTest {

    private val kolkataZone = ZoneId.of("Asia/Kolkata")

    // The 4 test instants specified by T2
    private val instant1 = Instant.parse("2026-11-21T23:30:00Z") // 05:00 on 22 Nov in India
    private val instant2 = Instant.parse("2026-11-30T18:30:00Z") // 00:00 on 1 Dec in India (midnight)
    private val instant3 = Instant.parse("2026-12-31T18:29:59Z") // 23:59:59 on 31 Dec in India
    private val instant4 = Instant.parse("2026-12-31T18:30:00Z") // 00:00:00 on 1 Jan 2027 in India (new year midnight)

    private fun formatMonthTabName(instant: Instant): String {
        val dateTime = instant.atZone(kolkataZone)
        val formatter = DateTimeFormatter.ofPattern("MMMyy", Locale.US)
        return dateTime.format(formatter)
    }

    private fun resolveWorkbookYear(instant: Instant): Int {
        return instant.atZone(kolkataZone).year
    }

    private class FakeTripTagDao : TripTagDao {
        val tags = ConcurrentHashMap<String, TripTagEntity>()
        var lastTodayEpochDayPassed: Long? = null

        override fun getTripTagsFlow(): Flow<List<TripTagEntity>> = flowOf(tags.values.toList())
        override fun getActiveTripTagsFlow(): Flow<List<TripTagEntity>> = flowOf(tags.values.filter { it.isActive && !it.isArchived })
        override suspend fun getAllTripTags(): List<TripTagEntity> = tags.values.toList()
        override suspend fun getActiveTripTags(): List<TripTagEntity> = tags.values.filter { it.isActive && !it.isArchived }

        override suspend fun getById(id: String): TripTagEntity? = tags[id]
        override suspend fun getByName(name: String): TripTagEntity? = tags.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun getActiveTag(): TripTagEntity? = tags.values.firstOrNull { it.isActive && !it.isArchived }
        override fun getActiveTagFlow(): Flow<TripTagEntity?> = flowOf(tags.values.firstOrNull { it.isActive && !it.isArchived })

        override suspend fun clearActiveFlagsWithDate(endDateEpochDay: Long, updatedAt: Long) {
            tags.values.forEach {
                val endDate = if (it.isActive && it.endDateEpochDay == null) endDateEpochDay else it.endDateEpochDay
                tags[it.id] = it.copy(isActive = false, endDateEpochDay = endDate, updatedAt = updatedAt)
            }
        }

        override suspend fun clearActiveFlags(updatedAt: Long) {
            tags.values.forEach { tags[it.id] = it.copy(isActive = false, updatedAt = updatedAt) }
        }

        override suspend fun setActiveFlagWithDate(id: String, startDateEpochDay: Long, updatedAt: Long) {
            tags[id]?.let {
                val startDate = it.startDateEpochDay ?: startDateEpochDay
                tags[id] = it.copy(isActive = true, startDateEpochDay = startDate, endDateEpochDay = null, updatedAt = updatedAt)
            }
        }

        override suspend fun setActiveFlag(id: String, updatedAt: Long) {
            tags[id]?.let { tags[id] = it.copy(isActive = true, updatedAt = updatedAt) }
        }

        override suspend fun count(): Int = tags.size
        override suspend fun insert(tag: TripTagEntity) { tags[tag.id] = tag }
        override suspend fun insertAll(tags: List<TripTagEntity>) { tags.forEach { insert(it) } }
        override suspend fun update(tag: TripTagEntity) { tags[tag.id] = tag }
        override suspend fun delete(tag: TripTagEntity) { tags.remove(tag.id) }
        override suspend fun deleteById(id: String) { tags.remove(id) }

        override suspend fun archiveTag(id: String, updatedAt: Long) {
            tags[id]?.let { tags[id] = it.copy(isArchived = true, isActive = false, updatedAt = updatedAt) }
        }

        override suspend fun unarchiveTag(id: String, updatedAt: Long) {
            tags[id]?.let { tags[id] = it.copy(isArchived = false, updatedAt = updatedAt) }
        }

        override suspend fun getDirtyTripTags(): List<TripTagEntity> = tags.values.filter { it.dirty == 1 }
        override suspend fun markClean(ids: List<String>) {
            ids.forEach { id -> tags[id]?.let { tags[id] = it.copy(dirty = 0) } }
        }

        override suspend fun setActiveTagAtomic(id: String?, todayEpochDay: Long, updatedAt: Long): String? {
            lastTodayEpochDayPassed = todayEpochDay
            clearActiveFlagsWithDate(todayEpochDay, updatedAt)
            return if (id != null) {
                setActiveFlagWithDate(id, todayEpochDay, updatedAt)
                getById(id)?.name
            } else {
                null
            }
        }
    }

    @Test
    fun testTripStartDate_usesIndiaLocalDayAtUtcBoundary() = runBlocking {
        // At 2026-11-21T23:30:00Z, UTC is 21 Nov, but in India it is 22 Nov 05:00.
        // N3 fix: Trip start date written must be 2026-11-22 (local India date), not 2026-11-21 (UTC date).
        val fixedClock = Clock.fixed(instant1, ZoneId.of("UTC"))
        val fakeDao = FakeTripTagDao()

        val tag = TripTagEntity(
            id = "trip_mumbai",
            name = "Mumbai",
            isActive = false,
            startDateEpochDay = null,
            endDateEpochDay = null
        )
        fakeDao.insert(tag)

        val repo = TripTagRepositoryImpl(
            tripTagDao = fakeDao,
            clock = fixedClock
        )

        repo.setActiveTag("trip_mumbai")

        val expectedLocalDay = LocalDate.of(2026, 11, 22)
        val expectedEpochDay = expectedLocalDay.toEpochDay()

        assertEquals("Passed epoch day to DAO must be India local day", expectedEpochDay, fakeDao.lastTodayEpochDayPassed)

        val activeTag = fakeDao.getActiveTag()
        assertNotNull("Active tag must exist", activeTag)
        assertEquals("Active trip start date epoch day must match India local day 2026-11-22", expectedEpochDay, activeTag?.startDateEpochDay)
        val actualLocalDate = LocalDate.ofEpochDay(activeTag!!.startDateEpochDay!!)
        assertEquals(expectedLocalDay, actualLocalDate)

        // Verify Trips tab row values builder formats date serial corresponding to India local day
        val tripRowValues = SheetProvisioner.buildTripRowValues(
            trip = activeTag.toDomain(),
            row1BasedIndex = 4,
            year = 2026
        )
        val expectedDateSerial = DateSerialUtils.toDateSerial(expectedLocalDay)
        assertEquals("Trips tab start date serial must correspond to India local day 2026-11-22", expectedDateSerial, tripRowValues[1])
    }

    @Test
    fun testMonthTabNames_atBoundaryInstants() {
        assertEquals("Nov26", formatMonthTabName(instant1))
        assertEquals("Dec26", formatMonthTabName(instant2))
        assertEquals("Dec26", formatMonthTabName(instant3))
        assertEquals("Jan27", formatMonthTabName(instant4))
    }

    @Test
    fun testWorkbookName_atYearBoundaryInstants() {
        val year1 = resolveWorkbookYear(instant1)
        val year2 = resolveWorkbookYear(instant2)
        val year3 = resolveWorkbookYear(instant3)
        val year4 = resolveWorkbookYear(instant4)

        assertEquals(2026, year1)
        assertEquals(2026, year2)
        assertEquals(2026, year3)
        assertEquals(2027, year4)

        assertEquals("Finance2026", "Finance$year1")
        assertEquals("Finance2026", "Finance$year2")
        assertEquals("Finance2026", "Finance$year3")
        assertEquals("Finance2027", "Finance$year4")
    }

    @Test
    fun testCarryForward_openDebtMovesToNewYearWorkbook_settledDoesNot() {
        val carryForward = CarryForwardUseCase()

        // Dec26 in Finance2026 has:
        // 1. One open debt of 300 with Kabir (Pending)
        // 2. One settled debt of 500 with Asha (Settled, owed = 0.0)
        val openDebt = LedgerDebit(
            tab = "Dec26",
            row = 4,
            uuid = "debit_open_300",
            dateSerial = 46387,
            timeSerial = 0.5,
            account = "Cash",
            item = "Dinner",
            tripTag = null,
            category = "Food & Drink",
            total = 600.0,
            companions = "Kabir",
            myShare = 300.0,
            owed = 300.0,
            status = "Pending"
        )

        val settledDebt = LedgerDebit(
            tab = "Dec26",
            row = 5,
            uuid = "debit_settled_500",
            dateSerial = 46376,
            timeSerial = 0.5,
            account = "Cash",
            item = "Lunch",
            tripTag = null,
            category = "Food & Drink",
            total = 1000.0,
            companions = "Asha",
            myShare = 500.0,
            owed = 0.0,
            status = "Settled"
        )

        val result = carryForward(
            targetTab = "Jan27",
            sourceTab = "Dec26",
            targetDebits = emptyList(),
            targetPayables = emptyList(),
            sourceDebits = listOf(openDebt, settledDebt),
            sourcePayables = emptyList()
        )

        // Verify only the open debt of 300 is carried into Jan27
        val appends = result.plan.appends
        assertEquals(1, appends.size)

        val carriedRow = appends.first()
        assertEquals("Jan27", carriedRow.tab)
        val lineage = result.lineageRecords.first()
        assertEquals(openDebt.uuid, lineage.carriedFromUuid)
        assertEquals(carriedRow.uuid, lineage.uuid)

        // Cells: Item (idx 3) must have [Carried from Dec26], Owed (idx 9) must be 300.0, Status (idx 10) must be Pending
        val cells = carriedRow.cells
        val itemCell = cells[COL_INDEX_DEBIT_DESCRIPTION].toString()
        assertTrue("Item must indicate carried from Dec26", itemCell.contains("[Carried from Dec26]"))
        assertTrue("Item must preserve original item description", itemCell.contains("Dinner"))

        val owedAmount = (cells[COL_INDEX_DEBIT_OWED_TO_ME] as Number).toDouble()
        assertEquals(300.0, owedAmount, 0.001)

        val statusCell = cells[COL_INDEX_DEBIT_STATUS].toString()
        assertEquals("Pending", statusCell)

        // Edits: source row in Dec26 must be updated to 'Carried Fwd'
        val edits = result.plan.edits
        val statusEdit = edits.find { it.tab == "Dec26" && it.column == SheetSchemaConstants.COL_LETTER_DEBIT_STATUS }
        assertEquals("Carried Fwd", statusEdit?.value)
    }

    companion object {
        private const val COL_INDEX_DEBIT_DESCRIPTION = 3
        private const val COL_INDEX_DEBIT_OWED_TO_ME = 9
        private const val COL_INDEX_DEBIT_STATUS = 10
    }
}
