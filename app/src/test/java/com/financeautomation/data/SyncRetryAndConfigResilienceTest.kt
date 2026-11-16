package com.financeautomation.data

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.remote.ConfigSheetManager
import com.financeautomation.data.remote.EmptyOrMissingConfigTabException
import com.financeautomation.data.remote.GoogleDriveApiException
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsApiException
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.usecase.FakeSubscriptionRepository
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.domain.usecase.PersonBalance
import com.financeautomation.domain.usecase.SubscriptionActionUseCase
import com.financeautomation.domain.usecase.SubscriptionYesOutcome
import com.financeautomation.domain.usecase.testSubscription
import com.financeautomation.testutil.RecordingTransactionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class SyncRetryAndConfigResilienceTest {

    @Test
    fun personBalanceDetectsPersonInMultiCompanionCustomSplit() {
        // Multi-companion custom split: total 1000, user share 400, owed 600, companions: "Rahul, Priya"
        val txn = Transaction(
            id = "txn_custom_1",
            timestamp = 1713600000000L,
            dateSerial = 45400,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Dinner",
            category = "Dining Out",
            totalAmount = 1000.0,
            myShare = 400.0,
            owedToMe = 600.0,
            status = TransactionStatus.PENDING,
            companions = "Rahul, Priya"
        )
        val transactions = listOf(txn)

        val rahulHasBalance = PersonBalance.hasOpenBalance("Rahul", transactions, emptyList())
        val priyaHasBalance = PersonBalance.hasOpenBalance("Priya", transactions, emptyList())
        val unrelatedHasBalance = PersonBalance.hasOpenBalance("Amit", transactions, emptyList())

        assertTrue("Rahul must have open balance in multi-companion custom split", rahulHasBalance)
        assertTrue("Priya must have open balance in multi-companion custom split", priyaHasBalance)
        assertFalse("Amit should not have open balance", unrelatedHasBalance)
    }

    @Test
    fun concurrentSubscriptionYesIsAtomicAndLogsOnlyOnce() = runBlocking {
        val recorder = RecordingTransactionRepository()
        val repo = FakeSubscriptionRepository()
        repo.addSubscription(testSubscription(id = "sub_spotify", amount = 119.0))
        val cycle = repo.addCycle("sub_spotify", 20000L)

        val useCase = SubscriptionActionUseCase(
            repository = repo,
            logExpenseUseCase = LogExpenseUseCase(recorder.repository),
            createCapture = { _, _ -> "cap_1" }
        )

        // Simulate 2 duplicate broadcasts arriving simultaneously
        val d1 = async { useCase.yes(cycle.id, 1713600000000L) }
        val d2 = async { useCase.yes(cycle.id, 1713600000000L) }
        val outcomes = awaitAll(d1, d2)

        val loggedCount = outcomes.count { it is SubscriptionYesOutcome.Logged }
        val resolvedCount = outcomes.count { it is SubscriptionYesOutcome.AlreadyResolved }

        assertEquals("Exactly one Yes call should log an expense", 1, loggedCount)
        assertEquals("The duplicate Yes call should return AlreadyResolved", 1, resolvedCount)
        assertEquals("Repository must have exactly one logged transaction", 1, recorder.inserted.size)
    }

    @Test
    fun driveLookupErrorReturnsErrorAndNeverCreatesSpreadsheet() = runBlocking {
        var createWasCalled = false
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            if (url.contains("/files?q=")) {
                // Search fails with 500 Internal Server Error
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("""{"error":{"code":500,"message":"Drive error"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else if (req.method == "POST" && url.contains("/files")) {
                createWasCalled = true
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"id":"new_sheet_id"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "tok"
            override fun refreshAccessToken(): String = "tok"
        }
        val driveClient = GoogleDriveClient(client, tokenProvider)

        val result = driveClient.findOrCreateSpreadsheet("folder_1", "Finance2026", 2026)

        assertTrue("Lookup error must surface as Result.Error, got $result", result is Result.Error)
        assertFalse("Spreadsheet must NEVER be created on a lookup error", createWasCalled)
    }

    @Test
    fun configReadErrorWithConfigInMessageDoesNotThrowEmptyOrMissingConfigTabException() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            // 500 Server Error whose error text happens to contain the word "Config"
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("""{"error":{"code":500,"message":"Config server storage backend timeout"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "tok"
            override fun refreshAccessToken(): String = "tok"
        }
        val sheetsClient = GoogleSheetsClient(client, tokenProvider)
        val configManager = ConfigSheetManager(sheetsClient, client, tokenProvider)

        val result = configManager.readConfig("sheet_123")

        assertTrue("Read failure should be Result.Error", result is Result.Error)
        val exception = (result as Result.Error).exception
        assertFalse(
            "Server error mentioning Config must NOT be classified as EmptyOrMissingConfigTabException, was: $exception",
            exception is EmptyOrMissingConfigTabException
        )
    }

    @Test
    fun transientNetworkFailureDoesNotIncrementRetryCountAndRecoverResetsFailedItems() = runBlocking {
        val queueDao = com.financeautomation.testutil.InMemoryQueueDao()
        val item1 = com.financeautomation.data.local.entity.SyncQueueEntity(
            id = 1L,
            transactionId = "tx_1",
            operationType = com.financeautomation.data.local.entity.SyncQueueEntity.OP_INSERT,
            payloadJson = "{}",
            status = com.financeautomation.data.local.entity.SyncQueueEntity.STATUS_PENDING,
            retryCount = 2,
            lastErrorKind = null
        )
        queueDao.insert(item1)

        // Mark transient failure
        queueDao.setStatusError(1L, com.financeautomation.data.local.entity.SyncQueueEntity.STATUS_FAILED, 2, com.financeautomation.core.utils.SyncErrorKind.NO_NETWORK.name, "Unable to resolve host")
        val itemAfterFail = queueDao.getById(1L)!!
        assertEquals("Transient error must keep retryCount at 2 (not incremented)", 2, itemAfterFail.retryCount)
        assertEquals(com.financeautomation.data.local.entity.SyncQueueEntity.STATUS_FAILED, itemAfterFail.status)

        // Now connectivity returns -> automatic recovery
        val recovered = queueDao.recoverRetryableFailedItems()
        assertEquals(1, recovered)

        val itemAfterRecover = queueDao.getById(1L)!!
        assertEquals("Recovered item must have STATUS_PENDING", com.financeautomation.data.local.entity.SyncQueueEntity.STATUS_PENDING, itemAfterRecover.status)
    }

    @Test
    fun recoverRetryableFailedItemsRestrictsToTransientOnlyAndLeavesUnknownAndDiscardedIntact() = runBlocking {
        val queueDao = com.financeautomation.testutil.InMemoryQueueDao()
        val items = listOf(
            com.financeautomation.data.local.entity.SyncQueueEntity(id = 1L, transactionId = "t1", operationType = "APPEND_TRANSACTION", payloadJson = "{}", status = "FAILED", retryCount = 8, lastErrorKind = "NO_NETWORK"),
            com.financeautomation.data.local.entity.SyncQueueEntity(id = 2L, transactionId = "t2", operationType = "APPEND_TRANSACTION", payloadJson = "{}", status = "FAILED", retryCount = 3, lastErrorKind = "RATE_LIMITED"),
            com.financeautomation.data.local.entity.SyncQueueEntity(id = 3L, transactionId = "t3", operationType = "APPEND_TRANSACTION", payloadJson = "{}", status = "FAILED", retryCount = 1, lastErrorKind = "SERVER_BUSY"),
            com.financeautomation.data.local.entity.SyncQueueEntity(id = 4L, transactionId = "t4", operationType = "APPEND_TRANSACTION", payloadJson = "{}", status = "FAILED", retryCount = 2, lastErrorKind = "UNKNOWN"),
            com.financeautomation.data.local.entity.SyncQueueEntity(id = 5L, transactionId = "t5", operationType = "APPEND_TRANSACTION", payloadJson = "{}", status = "FAILED", retryCount = 1, lastErrorKind = "SHEET_CHANGED"),
            com.financeautomation.data.local.entity.SyncQueueEntity(id = 6L, transactionId = "t6", operationType = "APPEND_TRANSACTION", payloadJson = "{}", status = "FAILED", retryCount = 1, lastErrorKind = "AUTH_EXPIRED"),
            com.financeautomation.data.local.entity.SyncQueueEntity(id = 7L, transactionId = "t7", operationType = "APPEND_TRANSACTION", payloadJson = "{}", status = "DISCARDED", retryCount = 0, lastErrorKind = "USER_DISCARDED")
        )
        items.forEach { queueDao.insert(it) }

        val recoveredCount = queueDao.recoverRetryableFailedItems()
        assertEquals("Only the 3 transient failure items should be recovered", 3, recoveredCount)

        assertEquals("NO_NETWORK recovered to PENDING", "PENDING", queueDao.getById(1L)!!.status)
        assertEquals("RATE_LIMITED recovered to PENDING", "PENDING", queueDao.getById(2L)!!.status)
        assertEquals("SERVER_BUSY recovered to PENDING", "PENDING", queueDao.getById(3L)!!.status)
        assertEquals("UNKNOWN stays FAILED", "FAILED", queueDao.getById(4L)!!.status)
        assertEquals("SHEET_CHANGED stays FAILED", "FAILED", queueDao.getById(5L)!!.status)
        assertEquals("AUTH_EXPIRED stays FAILED", "FAILED", queueDao.getById(6L)!!.status)
        assertEquals("User-discarded stays DISCARDED", "DISCARDED", queueDao.getById(7L)!!.status)
    }

    @Test
    fun renamingAccountCategoryAndCompanionUpdatesTransactions() = runBlocking {
        val txnDao = com.financeautomation.testutil.InMemoryTransactionDao()
        val txn = com.financeautomation.data.local.entity.TransactionEntity(
            id = "t1",
            timestamp = 1000L,
            dateSerial = 45000,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Store",
            category = "Groceries",
            totalAmount = 500.0,
            companions = "Rahul, Priya",
            myShare = 250.0,
            owedToMe = 250.0,
            status = "Pending"
        )
        txnDao.insert(txn)

        // Rename Account
        val accUpdated = txnDao.updateAccountName("HDFC", "HDFC Bank")
        assertEquals(1, accUpdated)
        assertEquals("HDFC Bank", txnDao.getById("t1")!!.account)

        // Rename Category
        val catUpdated = txnDao.updateCategoryName("Groceries", "Household Supplies")
        assertEquals(1, catUpdated)
        assertEquals("Household Supplies", txnDao.getById("t1")!!.category)

        // Rename Companion
        val compUpdated = txnDao.updateCompanionName("Rahul", "Rahul Sharma")
        assertEquals(1, compUpdated)
        assertEquals("Rahul Sharma, Priya", txnDao.getById("t1")!!.companions)
    }
}
