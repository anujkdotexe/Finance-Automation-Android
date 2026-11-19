package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.CompanionDao
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.CompanionEntity
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.usecase.PersonBalance
import com.financeautomation.presentation.viewmodel.QuickLogTransactionHelper
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** A removed friend or trip stays removed; rows that mention them keep their text. */
class PeopleRemovalTest {

    private class MemoryCompanionDao : CompanionDao {
        val rows = ConcurrentHashMap<String, CompanionEntity>()
        override fun getCompanionsFlow(): Flow<List<CompanionEntity>> = flowOf(rows.values.sortedBy { it.name })
        override suspend fun getById(id: String) = rows[id]
        override suspend fun getByName(name: String) = rows.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun getSelectedCompanions() = rows.values.filter { it.isSelected }
        override suspend fun count() = rows.size
        override suspend fun insert(companion: CompanionEntity) {
            rows.values.filter { it.name.equals(companion.name, ignoreCase = true) }.forEach { rows.remove(it.id) } // unique name
            rows[companion.id] = companion
        }
        override suspend fun insertAll(companions: List<CompanionEntity>) { companions.forEach { insert(it) } }
        override suspend fun update(companion: CompanionEntity) { rows[companion.id] = companion }
        override suspend fun delete(companion: CompanionEntity) { rows.remove(companion.id) }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override fun getActiveCompanionsFlow(): Flow<List<CompanionEntity>> = flowOf(rows.values.filter { !it.isArchived })
        override suspend fun getActiveCompanions() = rows.values.filter { !it.isArchived }
        override suspend fun archiveCompanion(id: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(isArchived = true) } }
        override suspend fun unarchiveCompanion(id: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(isArchived = false) } }
        override suspend fun updateName(id: String, name: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(name = name) } }
        override suspend fun getDirtyCompanions() = rows.values.filter { it.dirty == 1 }
        override suspend fun markClean(ids: List<String>) {}
    }

    private class MemoryTripTagDao : TripTagDao {
        val rows = ConcurrentHashMap<String, TripTagEntity>()
        override fun getTripTagsFlow(): Flow<List<TripTagEntity>> = flowOf(rows.values.toList())
        override fun getActiveTripTagsFlow(): Flow<List<TripTagEntity>> = flowOf(rows.values.filter { !it.isArchived })
        override suspend fun getAllTripTags() = rows.values.toList()
        override suspend fun getActiveTripTags() = rows.values.filter { !it.isArchived }
        override suspend fun getById(id: String) = rows[id]
        override suspend fun getByName(name: String) = rows.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun getActiveTag() = rows.values.firstOrNull { it.isActive && !it.isArchived }
        override fun getActiveTagFlow(): Flow<TripTagEntity?> = flowOf(rows.values.firstOrNull { it.isActive && !it.isArchived })
        override suspend fun clearActiveFlags(updatedAt: Long) { rows.values.forEach { rows[it.id] = it.copy(isActive = false) } }
        override suspend fun clearActiveFlagsWithDate(endDateEpochDay: Long, updatedAt: Long) { rows.values.forEach { rows[it.id] = it.copy(isActive = false, endDateEpochDay = endDateEpochDay) } }
        override suspend fun setActiveFlag(id: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(isActive = true) } }
        override suspend fun setActiveFlagWithDate(id: String, startDateEpochDay: Long, updatedAt: Long) { rows[id]?.let { rows[it.id] = it.copy(isActive = true, startDateEpochDay = it.startDateEpochDay ?: startDateEpochDay, endDateEpochDay = null) } }
        override suspend fun count() = rows.size
        override suspend fun insert(tag: TripTagEntity) { rows[tag.id] = tag }
        override suspend fun insertAll(tags: List<TripTagEntity>) { tags.forEach { insert(it) } }
        override suspend fun update(tag: TripTagEntity) { rows[tag.id] = tag }
        override suspend fun delete(tag: TripTagEntity) { rows.remove(tag.id) }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun archiveTag(id: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(isArchived = true, isActive = false) } }
        override suspend fun unarchiveTag(id: String, updatedAt: Long) { rows[id]?.let { rows[id] = it.copy(isArchived = false) } }
        override suspend fun getDirtyTripTags() = rows.values.filter { it.dirty == 1 }
        override suspend fun markClean(ids: List<String>) {}
    }

    private var openBalance = false
    private val dao = MemoryCompanionDao()
    private val people = PeopleRepositoryImpl(
        companionDao = dao, ioDispatcher = Dispatchers.Unconfined, openBalanceChecker = { openBalance }
    )

    private suspend fun addCharlie() = people.insertCompanion(Companion(id = "c1", name = "Charlie"))

    private suspend fun names() = people.getAllCompanions().map { it.name }

    // people

    @Test
    fun removalIsRefusedWithAReasonWhileThereIsAnOpenBalance() = runBlocking {
        addCharlie()
        openBalance = true
        val res = people.removePerson("Charlie")
        assertTrue(res is Result.Error)
        assertTrue((res as Result.Error).exception.message!!.contains("open balance"))
        assertEquals(listOf("Charlie"), names())
    }

    @Test
    fun aSettledFriendIsRemovedFromTheList() = runBlocking {
        addCharlie()
        assertTrue(people.removePerson("charlie") is Result.Success)
        assertEquals(emptyList<String>(), names())
        assertEquals(emptyList<Companion>(), people.getCompanions().first())
    }

    @Test
    fun aRemovedFriendDoesNotComeBackWhenThePeopleListIsRebuilt() = runBlocking {
        val h = SyncHarness()
        addCharlie()
        people.removePerson("Charlie")
        val tab = ScriptedSheetsServer.currentTab()
        h.server.customRead = { call ->
            if (call.url.contains("values:batchGet")) {
                """{"valueRanges":[
                    {"range":"'$tab'!A4:L","majorDimension":"ROWS","values":[[46000,0.5,"SBI","Dinner","","Food & Drink",300.0,"Charlie, Dana",100.0,200.0,"Pending",""]]},
                    {"range":"'$tab'!AD4:AD","majorDimension":"ROWS","values":[["txn_x"]]},
                    {"range":"'$tab'!N4:U","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AE4:AE","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!W4:AC","majorDimension":"ROWS","values":[]},
                    {"range":"'$tab'!AF4:AF","majorDimension":"ROWS","values":[]}
                ]}"""
            } else null
        }
        val rebuilder = PeopleListRebuilder(
            preferencesRepository = h.prefs, googleSheetsClient = h.sheets, peopleRepository = people,
            ioDispatcher = Dispatchers.Unconfined, readGate = h.gate, clock = { h.now }
        )
        assertTrue(rebuilder.refresh())
        val shown = names()
        assertFalse("Charlie must stay removed", "Charlie" in shown)
        assertTrue("other people are still added", "Dana" in shown)
    }

    @Test
    fun aNewTransactionNamingThemAgainBringsThemBack() = runBlocking {
        addCharlie()
        people.removePerson("Charlie")
        val ids = QuickLogTransactionHelper.matchOrInsertCompanions(listOf("Charlie"), people.getAllCompanions(), people)
        assertEquals(1, ids.size)
        assertEquals(listOf("Charlie"), names())
        assertEquals("the same entry is restored, not duplicated", 1, dao.rows.size)
    }

    @Test
    fun aNameKnownOnlyFromTransactionsCanBeRemovedAndStaysRemoved() = runBlocking {
        assertTrue(people.removePerson("Dave") is Result.Success)
        // the removal is recorded, so the rebuild (which asks getCompanionByName first) will skip him
        assertNotNull(people.getCompanionByName("Dave"))
        assertFalse("Dave" in names())
    }

    // the open balance rule

    @Test
    fun anOpenBalanceMeansMoneyOwedEitherWay() {
        val owed = com.financeautomation.testutil.SyncHarness().expense("t").copy(
            companions = "Charlie", owedToMe = 50.0, myShare = 50.0, totalAmount = 100.0, status = TransactionStatus.PENDING
        )
        val settled = owed.copy(owedToMe = 0.0, status = TransactionStatus.SETTLED)
        assertTrue(PersonBalance.hasOpenBalance("charlie", listOf(owed), emptyList()))
        assertFalse(PersonBalance.hasOpenBalance("charlie", listOf(settled), emptyList()))
        assertFalse(PersonBalance.hasOpenBalance("dana", listOf(owed), emptyList()))
        assertTrue(PersonBalance.hasOpenBalance("Charlie", emptyList(), listOf(PersonBalance.OpenPayable("Charlie", 20.0))))
    }

    @Test
    fun theAppChecksRealBalancesWhenRemovingAPerson() {
        val container = java.io.File("src/main/java/com/financeautomation/di/AppContainer.kt").readText()
        assertTrue(container.contains("openBalanceChecker"))
        assertTrue(container.contains("PersonBalance.hasOpenBalance"))
        val settings = java.io.File("src/main/java/com/financeautomation/presentation/viewmodel/SettingsCustomizationCoordinator.kt").readText()
        assertTrue(settings.contains("peopleRepository.removePerson"))
    }

    // trips

    private fun trips(active: Boolean): Pair<TripTagRepositoryImpl, MemoryTripTagDao> {
        val d = MemoryTripTagDao()
        d.rows["t1"] = TripTagEntity(id = "t1", name = "Goa", isActive = active)
        return TripTagRepositoryImpl(tripTagDao = d, ioDispatcher = Dispatchers.Unconfined) to d
    }

    @Test
    fun anActiveTripCannotBeRemoved() = runBlocking {
        val (repo, d) = trips(active = true)
        val res = repo.removeTripTag("Goa")
        assertTrue(res is Result.Error)
        assertTrue((res as Result.Error).exception.message!!.contains("active trip"))
        assertFalse(d.rows["t1"]!!.isArchived)
    }

    @Test
    fun anEndedTripIsRemovedFromTheList() = runBlocking {
        val (repo, d) = trips(active = false)
        assertTrue(repo.removeTripTag("goa") is Result.Success)
        assertTrue(d.rows["t1"]!!.isArchived)
        assertEquals(emptyList<String>(), repo.getAllTripTags().map { it.name })
        assertTrue(repo.removeTripTag("Unknown") is Result.Error)
    }
}
