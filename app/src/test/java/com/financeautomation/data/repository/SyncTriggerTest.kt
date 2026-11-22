package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import com.financeautomation.testutil.InMemoryInflowDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every save path must ask for a sync, and the app must schedule the periodic sync at start. */
class SyncTriggerTest {

    private val tDao = InMemoryTransactionDao()
    private val qDao = InMemoryQueueDao()
    private val iDao = InMemoryInflowDao()
    private val pDao = com.financeautomation.testutil.InMemoryPayableDao()
    private var triggers = 0

    private val repo = TransactionRepositoryImpl(
        transactionDao = tDao,
        syncQueueDao = qDao,
        payableDao = pDao,
        inflowDao = iDao,
        accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
        onTriggerSync = { triggers++ },
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun expense(id: String = "txn_1") = Transaction(
        id = id,
        timestamp = System.currentTimeMillis() - 60_000L,
        dateSerial = 46000,
        timeSerial = 0.5,
        account = "SBI",
        merchant = "Tea",
        tripTag = "",
        category = "Food & Drink",
        totalAmount = 40.0,
        companions = "Personal",
        myShare = 40.0,
        owedToMe = 0.0,
        status = TransactionStatus.PERSONAL,
        sourcePackage = "manual",
        reviewState = ReviewState.CONFIRMED,
        syncState = SyncState.PENDING,
        sheetRowIndex = null,
        isPossibleDuplicate = false
    )

    @Test
    fun savingAnExpenseRequestsASync() = runBlocking {
        assertTrue(repo.insertTransaction(expense()) is Result.Success)
        assertEquals(1, triggers)
    }

    @Test
    fun editingAnExpenseRequestsASync() = runBlocking {
        repo.insertTransaction(expense())
        triggers = 0
        assertTrue(repo.updateTransaction(expense().copy(merchant = "Coffee")) is Result.Success)
        assertEquals(1, triggers)
    }

    @Test
    fun voidingAnExpenseRequestsASync() = runBlocking {
        repo.insertTransaction(expense())
        triggers = 0
        assertTrue(repo.voidTransaction("txn_1") is Result.Success)
        assertEquals(1, triggers)
    }

    @Test
    fun creditPayableAndRepaymentRequestASync() = runBlocking {
        val ts = System.currentTimeMillis() - 1000L
        repo.logCredit("Asha", 100.0, emptyList(), "SBI", "Income", "gift", ts)
        repo.logPayable("Asha", 100.0, "lunch", ts)
        repo.logRepayment("Asha", 50.0, null, ts)
        assertEquals(3, triggers)
    }

    @Test
    fun aFailedSaveDoesNotRequestASync() = runBlocking {
        val future = expense().copy(timestamp = System.currentTimeMillis() + 3_600_000L)
        assertTrue(repo.insertTransaction(future) is Result.Error)
        assertEquals(0, triggers)
    }

    @Test
    fun aBrokenSyncRequestNeverFailsTheSave() = runBlocking {
        val broken = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            accountingSyncExecutor = createTestAccountingSyncExecutor(syncQueueDao = qDao, transactionDao = tDao),
            onTriggerSync = { throw IllegalStateException("WorkManager is not initialised") },
            ioDispatcher = Dispatchers.Unconfined
        )
        assertTrue(broken.insertTransaction(expense("txn_2")) is Result.Success)
        assertEquals(1, qDao.queue.values.count { it.transactionId == "txn_2" })
    }

    @Test
    fun theContainerUsesTheWorkManagerWorkerNotAnInProcessCoroutine() {
        val container = File("src/main/java/com/financeautomation/di/AppContainer.kt").readText()
        assertTrue(container.contains("SyncTransactionsWorker.enqueueExpedited(applicationContext)"))
        assertEquals(0, Regex("onTriggerSync = \\{\\s*CoroutineScope").findAll(container).count())
    }

    @Test
    fun applicationStartSchedulesThePeriodicSync() {
        val app = File("src/main/java/com/financeautomation/FinanceApplication.kt").readText()
        assertTrue(app.contains("SyncTransactionsWorker.enqueuePeriodic(applicationContext)"))
        val worker = File("src/main/java/com/financeautomation/service/worker/SyncTransactionsWorker.kt").readText()
        assertTrue(worker.contains("ExistingPeriodicWorkPolicy.KEEP"))
    }
}
