package com.financeautomation.data

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SplitMode
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.local.entity.TripTagEntity
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.data.repository.TripTagRepositoryImpl
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.LogExpenseUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class TripModeLeakTest {

    private class FakeTripTagDao : TripTagDao {
        val tags = ConcurrentHashMap<String, TripTagEntity>()
        private val activeFlow = MutableStateFlow<TripTagEntity?>(null)

        override fun getTripTagsFlow(): Flow<List<TripTagEntity>> = flowOf(tags.values.toList())
        override fun getActiveTripTagsFlow(): Flow<List<TripTagEntity>> = flowOf(tags.values.filter { it.isActive }.toList())
        override suspend fun getAllTripTags(): List<TripTagEntity> = tags.values.toList()
        override suspend fun getActiveTripTags(): List<TripTagEntity> = tags.values.filter { it.isActive }
        override suspend fun getById(id: String): TripTagEntity? = tags[id]
        override suspend fun getByName(name: String): TripTagEntity? = tags.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun getActiveTag(): TripTagEntity? = tags.values.firstOrNull { it.isActive && !it.isArchived }
        override fun getActiveTagFlow(): Flow<TripTagEntity?> = activeFlow.asStateFlow()

        override suspend fun clearActiveFlags(updatedAt: Long) {
            tags.values.filter { it.isActive }.forEach { tag ->
                tags[tag.id] = tag.copy(isActive = false, updatedAt = updatedAt, dirty = 1)
            }
            activeFlow.value = null
        }

        override suspend fun setActiveFlag(id: String, updatedAt: Long) {
            clearActiveFlags(updatedAt)
            tags[id]?.let { tag ->
                val updated = tag.copy(isActive = true, updatedAt = updatedAt, dirty = 1)
                tags[id] = updated
                activeFlow.value = updated
            }
        }

        override suspend fun count(): Int = tags.size
        override suspend fun insert(tag: TripTagEntity) {
            tags[tag.id] = tag
            if (tag.isActive) activeFlow.value = tag
        }
        override suspend fun insertAll(tags: List<TripTagEntity>) { tags.forEach { insert(it) } }
        override suspend fun update(tag: TripTagEntity) {
            tags[tag.id] = tag
            if (tag.isActive) activeFlow.value = tag
        }
        override suspend fun delete(tag: TripTagEntity) { tags.remove(tag.id) }
        override suspend fun deleteById(id: String) { tags.remove(id) }
        override suspend fun archiveTag(id: String, updatedAt: Long) {
            tags[id]?.let { tags[id] = it.copy(isArchived = true, isActive = false, updatedAt = updatedAt, dirty = 1) }
        }
        override suspend fun unarchiveTag(id: String, updatedAt: Long) {
            tags[id]?.let { tags[id] = it.copy(isArchived = false, updatedAt = updatedAt, dirty = 1) }
        }
        override suspend fun getDirtyTripTags(): List<TripTagEntity> = tags.values.filter { it.dirty == 1 }
        override suspend fun markClean(ids: List<String>) {
            ids.forEach { id -> tags[id]?.let { tags[id] = it.copy(dirty = 0) } }
        }
    }

    private class SimplePreferencesRepo : PreferencesRepository {
        var activeTrip: String? = null
        override fun getSpreadsheetId(): Flow<String?> = flowOf("test_id")
        override suspend fun getSpreadsheetIdSync(): String? = "test_id"
        override suspend fun setSpreadsheetId(id: String) {}
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf("test_id")
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = "test_id"
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {}
        override fun getDefaultAccount(): Flow<String> = flowOf("SBI")
        override suspend fun getDefaultAccountSync(): String = "SBI"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(activeTrip)
        override suspend fun setActiveTrip(trip: String?) { activeTrip = trip }
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
        override suspend fun getGoogleAccountEmailSync(): String? = null
        override suspend fun setGoogleAccountEmail(email: String?) {}
        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(5)
        override suspend fun getSettleDelaySecondsSync(): Int = 5
        override suspend fun setSettleDelaySeconds(seconds: Int) {}
        override suspend fun getOrCreateDeviceId(): String = "dev_123"
        override suspend fun getDeviceIdSync(): String? = "dev_123"
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

    private class SimpleTransactionDao : TransactionDao {
        val txns = ConcurrentHashMap<String, TransactionEntity>()
        override fun getTransactionsFlow(): Flow<List<TransactionEntity>> = flowOf(txns.values.toList())
        override fun getNeedsReviewFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override fun getConfirmedUnsyncedFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override fun getAccountTransactionCounts(from: Long, to: Long): kotlinx.coroutines.flow.Flow<List<com.financeautomation.data.local.dao.AccountTransactionCount>> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun getById(id: String): TransactionEntity? = txns[id]
        override suspend fun getBySheetRowIndex(rowIndex: Int): TransactionEntity? = txns.values.firstOrNull { it.sheetRowIndex == rowIndex }
        override suspend fun getAllBySheetRowIndex(rowIndex: Int): List<TransactionEntity> = txns.values.filter { it.sheetRowIndex == rowIndex }
        override suspend fun getRecentSimilar(minTimestamp: Long, maxTimestamp: Long, minAmount: Double, maxAmount: Double): List<TransactionEntity> = emptyList()
        override suspend fun insert(transaction: TransactionEntity) { txns[transaction.id] = transaction }
        override suspend fun insertAll(transactions: List<TransactionEntity>) { transactions.forEach { insert(it) } }
        override suspend fun update(transaction: TransactionEntity) { txns[transaction.id] = transaction }
        override suspend fun delete(transaction: TransactionEntity) { txns.remove(transaction.id) }
        override suspend fun deleteById(id: String) { txns.remove(id) }
        override suspend fun voidTransaction(id: String, voidTitle: String) {}
        override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {
            txns[id]?.let { txns[id] = it.copy(syncState = syncState, sheetRowIndex = sheetRowIndex ?: it.sheetRowIndex) }
        }
        override suspend fun updateReviewState(id: String, reviewState: String, category: String?) {
            txns[id]?.let { txns[id] = it.copy(reviewState = reviewState) }
        }
        override suspend fun updateAccountName(oldName: String, newName: String): Int = 0
        override suspend fun updateCategoryName(oldName: String, newName: String): Int = 0
        override suspend fun updateCompanionName(oldName: String, newName: String): Int = 0
    }

    private class SimpleSyncQueueDao : SyncQueueDao {
        val items = ConcurrentHashMap<Long, SyncQueueEntity>()
        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override suspend fun insert(item: SyncQueueEntity): Long {
            items[item.id] = item
            return item.id
        }
        override suspend fun update(item: SyncQueueEntity) { items[item.id] = item }
        override suspend fun delete(item: SyncQueueEntity) { items.remove(item.id) }
        override suspend fun deleteById(id: Long) { items.remove(id) }
        override suspend fun getById(id: Long): SyncQueueEntity? = items[id]
        override suspend fun deleteByTransactionId(transactionId: String) { items.values.removeIf { it.transactionId == transactionId } }
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = items.values.firstOrNull { it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = items.values.filter { it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun recoverRetryableFailedItems(): Int = 0
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = items.values.filter { it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {
            items[id]?.let { items[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement) }
        }
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {
            items[id]?.let { items[id] = it.copy(status = status, retryCount = retryCount) }
        }
        override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {
            items[id]?.let { items[id] = it.copy(status = status, retryCount = retryCount, lastErrorKind = kind, lastErrorDetail = detail) }
        }
        override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {
            items[id]?.let { items[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement, lastErrorKind = kind, lastErrorDetail = detail) }
        }
        override suspend fun resetQueueItem(id: Long) {
            items[id]?.let { items[id] = it.copy(status = SyncQueueEntity.STATUS_PENDING, retryCount = 0, lastErrorKind = null, lastErrorDetail = null) }
        }
        override suspend fun countPendingOperations(opType: String): Int = 0
        override suspend fun updatePlanJson(id: Long, planJson: String?) { items[id]?.let { items[id] = it.copy(planJson = planJson) } }
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
    }

    @Test
    fun testStartTripLogExpenseEndTripLogExpenseSecondRowHasEmptyTagAndConfigInactive() = runBlocking {
        val tripDao = FakeTripTagDao()
        val prefsRepo = SimplePreferencesRepo()
        val tDao = SimpleTransactionDao()
        val qDao = SimpleSyncQueueDao()

        val tripRepo = TripTagRepositoryImpl(
            tripTagDao = tripDao,
            preferencesRepository = prefsRepo
        )

        val txnRepo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            preferencesRepository = prefsRepo,
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor()
        )

        val logExpenseUseCase = LogExpenseUseCase(txnRepo)

        // 1. Insert and Start Trip: "GoaTrip"
        val insertedTag = tripRepo.insertTripTag("GoaTrip")
        tripRepo.setActiveTag(insertedTag.id)

        // Verify active in repo and prefs
        val activeTag1 = tripRepo.getActiveTagSync()
        assertNotNull(activeTag1)
        assertEquals("GoaTrip", activeTag1!!.name)
        assertEquals("GoaTrip", prefsRepo.activeTrip)

        // 2. Log first expense during trip: "Scuba"
        val exp1Res = logExpenseUseCase.invoke(
            amount = 2500.0,
            merchant = "Scuba",
            category = "Activities",
            splitMode = SplitMode.JUST_ME,
            tripTag = tripRepo.getActiveTagSync()?.name ?: ""
        )
        assertTrue(exp1Res is Result.Success)
        val txn1 = (exp1Res as Result.Success).data
        assertEquals("GoaTrip", txn1.tripTag)

        // 3. End Trip
        tripRepo.setActiveTag(null)

        // Verify trip is deactivated in both Room DAO and preferences
        val activeTag2 = tripRepo.getActiveTagSync()
        assertNull("Active tag must be null after ending trip", activeTag2)
        assertNull("Preferences active trip must be null after ending trip", prefsRepo.activeTrip)

        // Verify TripTagEntity in DAO is marked dirty = 1 with isActive = false so ConfigSync pushes it
        val dirtyTags = tripDao.getDirtyTripTags()
        assertTrue("Deactivated tag must be dirty so ConfigSync pushes it to Config tab", dirtyTags.any { it.name == "GoaTrip" && !it.isActive && it.dirty == 1 })

        // 4. Log second expense after ending trip (offline): "Water"
        val currentActiveTrip = tripRepo.getActiveTagSync()?.name ?: ""
        val exp2Res = logExpenseUseCase.invoke(
            amount = 20.0,
            merchant = "Water",
            category = "Food & Drink",
            splitMode = SplitMode.JUST_ME,
            tripTag = currentActiveTrip
        )
        assertTrue(exp2Res is Result.Success)
        val txn2 = (exp2Res as Result.Success).data
        assertEquals("Second expense must have empty trip tag", "", txn2.tripTag)

        // Both transactions saved in DAO
        val savedTxn1 = tDao.getById(txn1.id)
        val savedTxn2 = tDao.getById(txn2.id)

        assertNotNull(savedTxn1)
        assertNotNull(savedTxn2)

        assertEquals("GoaTrip", savedTxn1!!.tripTag)
        assertEquals("", savedTxn2!!.tripTag)
    }
}
