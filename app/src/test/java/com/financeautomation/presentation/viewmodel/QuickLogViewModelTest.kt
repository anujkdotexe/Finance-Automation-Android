package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class QuickLogViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeTransactionRepo : TransactionRepository {
        val inserted = mutableListOf<Transaction>()
        override fun getTransactions(): Flow<List<Transaction>> = flowOf(inserted)
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = inserted.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            inserted.add(transaction)
            return Result.Success(Unit)
        }
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            val idx = inserted.indexOfFirst { it.id == transaction.id }
            if (idx >= 0) inserted[idx] = transaction
            return Result.Success(Unit)
        }
        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
    }

    private class FakePeopleRepo : PeopleRepository {
        override fun getCompanions(): Flow<List<Companion>> = flowOf(listOf(Companion("1", "Alice"), Companion("2", "Bob")))
        override suspend fun getAllCompanions(): List<Companion> = listOf(Companion("1", "Alice"), Companion("2", "Bob"))
        override suspend fun getCompanionById(id: String): Companion? = null
        override suspend fun getCompanionByName(name: String): Companion? = null
        override suspend fun insertCompanion(companion: Companion) {}
        override suspend fun updateCompanion(companion: Companion) {}
        override suspend fun updateCompanionName(id: String, newName: String) {}
        override suspend fun deleteCompanion(companion: Companion) {}
        override suspend fun deleteCompanionById(id: String) {}
    }

    private class FakeCategoryRepo : CategoryRepository {
        override fun getCategories(includeArchived: Boolean): Flow<List<Category>> = flowOf(listOf(Category("1", "Food & Drink")))
        override suspend fun getAllCategories(includeArchived: Boolean): List<Category> = emptyList()
        override suspend fun getActiveCategories(): List<Category> = emptyList()
        override suspend fun getCategoryById(id: String): Category? = null
        override suspend fun getCategoryByName(name: String): Category? = null
        override suspend fun insertCategory(category: Category) {}
        override suspend fun updateCategory(category: Category) {}
        override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean) {}
        override suspend fun archiveCategory(id: String) {}
        override suspend fun unarchiveCategory(id: String) {}
        override suspend fun deleteCategory(id: String) {}
        override suspend fun reorderCategories(categoryIds: List<String>) {}
    }

    private class FakeAccountRepo : AccountRepository {
        override fun getAccounts(includeArchived: Boolean): Flow<List<Account>> = flowOf(listOf(Account("1", "Cash", isDefault = true)))
        override suspend fun getAllAccounts(includeArchived: Boolean): List<Account> = listOf(Account("1", "Cash", isDefault = true))
        override suspend fun getAccountById(id: String): Account? = null
        override suspend fun getAccountByName(name: String): Account? = null
        override suspend fun getDefaultAccount(): Account? = Account("1", "Cash", isDefault = true)
        override suspend fun insertAccount(account: Account) {}
        override suspend fun updateAccount(account: Account) {}
        override suspend fun setDefaultAccount(id: String) {}
        override suspend fun archiveAccount(id: String) {}
        override suspend fun unarchiveAccount(id: String) {}
        override suspend fun deleteAccount(id: String) {}
        override suspend fun reorderAccounts(accountIds: List<String>) {}
    }

    private class FakeTripTagRepo : TripTagRepository {
        override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> = flowOf(emptyList())
        override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> = emptyList()
        override suspend fun getActiveTripTags(): List<TripTag> = emptyList()
        override fun getActiveTag(): Flow<TripTag?> = flowOf(null)
        override suspend fun getActiveTagSync(): TripTag? = null
        override suspend fun getTagById(id: String): TripTag? = null
        override suspend fun getTagByName(name: String): TripTag? = null
        override suspend fun insertTripTag(name: String): TripTag = TripTag("1", name)
        override suspend fun updateTripTag(tripTag: TripTag) {}
        override suspend fun setActiveTag(id: String?) {}
        override suspend fun archiveTripTag(id: String) {}
        override suspend fun unarchiveTripTag(id: String) {}
        override suspend fun deleteTripTag(id: String) {}
    }

    private class FakePendingCaptureDao : PendingCaptureDao {
        val captures = mutableMapOf<String, PendingCaptureEntity>()
        override suspend fun insert(capture: PendingCaptureEntity) { captures[capture.id] = capture }
        override suspend fun update(capture: PendingCaptureEntity) { captures[capture.id] = capture }
        override suspend fun getById(id: String): PendingCaptureEntity? = captures[id]
        override suspend fun getByNotificationKey(key: String): PendingCaptureEntity? = captures.values.lastOrNull { it.notificationKey == key }
        override suspend fun getByState(state: String): List<PendingCaptureEntity> = captures.values.filter { it.state == state }
        override fun getWaitingCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(emptyList())
        override fun getNeedsReviewCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(emptyList())
        override fun getNewAccountCapturesFlow(): Flow<List<PendingCaptureEntity>> = flowOf(emptyList())
        override suspend fun getWaitingCaptures(): List<PendingCaptureEntity> = emptyList()
        override suspend fun getExpiredWaitingCaptures(cutoffTime: Long): List<PendingCaptureEntity> = emptyList()
        override suspend fun getAwaitingSuccessCaptures(): List<PendingCaptureEntity> = emptyList()
        override suspend fun findSimilarByAmountAndTime(amount: Double, minTime: Long, maxTime: Long): List<PendingCaptureEntity> = emptyList()
        override suspend fun updateState(id: String, state: String, updatedAt: Long) {}
        override suspend fun resetShownToWaiting(updatedAt: Long): Int = 0
        override suspend fun deleteById(id: String) { captures.remove(id) }
        override suspend fun pruneOldCompletedCaptures(cutoffTime: Long) {}
    }

    private class FakeTestSyncQueueDao : SyncQueueDao {
        val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
        private val idGen = AtomicLong(1)
        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override suspend fun insert(item: SyncQueueEntity): Long {
            val id = if (item.id == 0L) idGen.getAndIncrement() else item.id
            queue[id] = item.copy(id = id)
            return id
        }
        override suspend fun update(item: SyncQueueEntity) { queue[item.id] = item }
        override suspend fun delete(item: SyncQueueEntity) { queue.remove(item.id) }
        override suspend fun deleteById(id: Long) { queue.remove(id) }
        override suspend fun deleteByTransactionId(transactionId: String) {}
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = null
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {}
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {}
        override suspend fun countPendingOperations(opType: String): Int = 0
        override suspend fun updatePlanJson(id: Long, planJson: String?) {}
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
    }

    @Test
    fun testOnConfirmAndSave_logsNewExpenseAndMarksCaptureDone() = runTest {
        val tRepo = FakeTransactionRepo()
        val pDao = FakePendingCaptureDao()
        val qDao = FakeTestSyncQueueDao()
        val capture = PendingCaptureEntity(
            id = "cap_save_test",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 120.0,
            payee = "Tea Stall",
            inferredAccount = "Cash",
            timestamp = 1730000000000L,
            kind = "DEBIT",
            flags = "",
            state = "WAITING",
            notificationKey = "key_save",
            contentHash = "hash_save",
            updatedAt = 1730000000000L
        )
        pDao.insert(capture)

        val vm = QuickLogViewModel(
            transactionRepository = tRepo,
            peopleRepository = FakePeopleRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepo(),
            tripTagRepository = FakeTripTagRepo(),
            pendingCaptureDaoProvider = { pDao },
            syncQueueDaoProvider = { qDao },
            ioDispatcher = testDispatcher
        )

        advanceUntilIdle()

        vm.loadCapture("cap_save_test")
        advanceUntilIdle()

        var savedMessage: String? = null
        vm.onConfirmAndSave { msg -> savedMessage = msg }
        advanceUntilIdle()

        assertNotNull(savedMessage)
        assertTrue(savedMessage!!.contains("120.00"))
        assertEquals(1, tRepo.inserted.size)
        val insertedTxn = tRepo.inserted.first()
        assertEquals(120.0, insertedTxn.totalAmount, 0.001)
        assertEquals("Tea Stall", insertedTxn.merchant)
        // Timestamp must propagate from capture
        assertEquals(1730000000000L, insertedTxn.timestamp)
    }
}
