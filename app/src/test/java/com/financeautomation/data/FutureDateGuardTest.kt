package com.financeautomation.data

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.TimeGuardConstants
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.usecase.LogExpenseUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Every save path must reject a future timestamp and accept now and the past. */
class FutureDateGuardTest {

    private val calls = mutableListOf<String>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> recordingDao(type: Class<T>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            calls.add(method.name)
            // No stored entry: an update reads the row it is made on
            if (method.name == "getById") null else when (method.returnType) {
                java.lang.Long.TYPE -> 1L
                java.lang.Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                kotlinx.coroutines.flow.Flow::class.java -> kotlinx.coroutines.flow.flowOf(emptyList<Any>())
                List::class.java -> emptyList<Any>()
                else -> kotlin.Unit
            }
        } as T

    private fun repo(inMemPayableDao: PayableDao = com.financeautomation.testutil.InMemoryPayableDao()) = TransactionRepositoryImpl(
        transactionDao = recordingDao(TransactionDao::class.java),
        syncQueueDao = recordingDao(SyncQueueDao::class.java),
        payableDao = inMemPayableDao,
        accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(),
        ioDispatcher = Dispatchers.Unconfined
    )

    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun txn(ts: Long) = Transaction(
        id = "txn_guard_$ts",
        timestamp = ts,
        dateSerial = 1,
        timeSerial = 0.0,
        account = "Cash",
        merchant = "Test",
        tripTag = "",
        category = "Food & Drink",
        totalAmount = 10.0,
        companions = "Personal",
        myShare = 10.0,
        owedToMe = 0.0,
        status = TransactionStatus.PERSONAL,
        sourcePackage = "manual",
        reviewState = ReviewState.CONFIRMED,
        syncState = SyncState.PENDING,
        sheetRowIndex = null,
        isPossibleDuplicate = false
    )

    private fun assertFutureError(r: Result<*>) {
        assertTrue("expected error but was $r", r is Result.Error)
        assertEquals(TimeGuardConstants.FUTURE_DATE_MESSAGE, (r as Result.Error).message)
    }

    @Test
    fun insertTransactionGuard() = runBlocking {
        val r = repo()
        assertFutureError(r.insertTransaction(txn(System.currentTimeMillis() + day)))
        assertTrue(calls.none { it == "insert" })
        assertTrue(r.insertTransaction(txn(System.currentTimeMillis())) is Result.Success)
        assertTrue(r.insertTransaction(txn(System.currentTimeMillis() - day)) is Result.Success)
    }

    @Test
    fun updateTransactionGuard() = runBlocking {
        val r = repo()
        assertFutureError(r.updateTransaction(txn(System.currentTimeMillis() + day)))
        assertTrue(calls.none { it == "update" })
        assertTrue(r.updateTransaction(txn(System.currentTimeMillis())) is Result.Success)
        assertTrue(r.updateTransaction(txn(System.currentTimeMillis() - day)) is Result.Success)
    }

    @Test
    fun logCreditGuard() = runBlocking {
        val r = repo()
        fun call(ts: Long) = runBlocking { r.logCredit("A", 5.0, emptyList(), "Cash", "Income", "", ts) }
        assertFutureError(call(System.currentTimeMillis() + day))
        assertTrue(call(System.currentTimeMillis()) is Result.Success)
        assertTrue(call(System.currentTimeMillis() - day) is Result.Success)
    }

    @Test
    fun logPayableGuard() = runBlocking {
        val r = repo()
        fun call(ts: Long) = runBlocking { r.logPayable("A", 5.0, "x", ts) }
        assertFutureError(call(System.currentTimeMillis() + day))
        assertTrue(call(System.currentTimeMillis()) is Result.Success)
        assertTrue(call(System.currentTimeMillis() - day) is Result.Success)
    }

    @Test
    fun logRepaymentGuard() = runBlocking {
        val r = repo()
        r.logPayable("A", 20.0, "Debt", System.currentTimeMillis() - day)
        fun call(ts: Long) = runBlocking { r.logRepayment("A", 5.0, null, ts) }
        assertFutureError(call(System.currentTimeMillis() + day))
        assertTrue(call(System.currentTimeMillis()) is Result.Success)
        assertTrue(call(System.currentTimeMillis() - day) is Result.Success)
    }

    @Test
    fun logExpenseUseCaseGuard() = runBlocking {
        val useCase = LogExpenseUseCase(repo())
        fun call(ts: Long) = runBlocking {
            useCase(amount = 10.0, merchant = "Tea", category = "Food & Drink",
                account = CategoryConstants.ACCOUNT_OTHER, timestamp = ts)
        }
        assertFutureError(call(System.currentTimeMillis() + day))
        assertTrue(call(System.currentTimeMillis()) is Result.Success)
        assertTrue(call(System.currentTimeMillis() - day) is Result.Success)
    }
}
