package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LogPayableRequest
import com.financeautomation.domain.model.TableType
import com.financeautomation.testutil.InMemoryPayableDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * P2-E Owner Decision Test Suite:
 * When a friend pays for something that is the owner's, the owner's share is a real expense.
 * - Raises living burn in the month of the purchase, in the chosen category.
 * - Repaying the friend in a later month adds 0 to living burn.
 * - Bilateral debt netting changes Owed to me and I owe only, without altering living burn.
 * - Refund inflows still reduce living burn once.
 * - Sheet sync appends both Table 1 (Debit) and Table 3 (Payable) rows matching the app.
 */
class FriendPaidLivingBurnTest {

    private val octWindow = LivingBurn.monthWindow(2026, 10)
    private val inOct = octWindow.first + 86_400_000L * 5
    private val novWindow = LivingBurn.monthWindow(2026, 11)
    private val inNov = novWindow.first + 86_400_000L * 5

    @Test
    fun burnRisesByTheShareInPurchaseMonth() = runBlocking {
        val h = SyncHarness(tabs = listOf("Nov26"))

        // Charlie paid 300 for owner's lunch in Food & Drink
        val res = h.repo.logPayable("Charlie", 300.0, "Lunch", inNov, "Food & Drink")
        assertTrue("logPayable must succeed", res is com.financeautomation.core.model.Result.Success<*>)

        val txns = h.tDao.rows.values.map { it.toDomain() }
        assertEquals("Must insert 1 local transaction for the purchase", 1, txns.size)
        val txn = txns.first()
        assertEquals(300.0, txn.totalAmount, 0.001)
        assertEquals(300.0, txn.myShare, 0.001)
        assertEquals(0.0, txn.owedToMe, 0.001)
        assertEquals("Food & Drink", txn.category)
        assertEquals(ReviewState.CONFIRMED, txn.reviewState)
        assertEquals(TransactionStatus.PERSONAL, txn.status)

        // Living burn in Nov 2026 rises by 300.0 in Food & Drink
        val burn = LivingBurn.total(txns, novWindow, emptySet())
        assertEquals(300.0, burn, 0.001)

        val byCat = LivingBurn.byCategory(txns, novWindow, emptySet())
        assertEquals(300.0, byCat["Food & Drink"] ?: 0.0, 0.001)
    }

    @Test
    fun repaymentInLaterMonthAddsZeroToLivingBurn() = runBlocking {
        val h = SyncHarness(tabs = listOf("Oct26", "Nov26"))

        // Oct 2026: Charlie paid 300 for lunch
        h.repo.logPayable("Charlie", 300.0, "Lunch", inOct, "Food & Drink")

        // Nov 2026: Owner repays Charlie 300
        val repayRes = h.repo.logRepayment("Charlie", 300.0, null, inNov)
        assertTrue("logRepayment must succeed", repayRes is com.financeautomation.core.model.Result.Success<*>)

        val allTxns = h.tDao.rows.values.map { it.toDomain() }

        // Oct 2026 burn is 300.0
        val octBurn = LivingBurn.total(allTxns, octWindow, emptySet())
        assertEquals(300.0, octBurn, 0.001)

        // Nov 2026 burn is 0.0 (repayment adds nothing to burn)
        val novBurn = LivingBurn.total(allTxns, novWindow, emptySet())
        assertEquals(0.0, novBurn, 0.001)
    }

    @Test
    fun nettingChangesOwedToMeAndIOweOnlyWithoutChangingBurn() = runBlocking {
        val h = SyncHarness(tabs = listOf("Nov26"))

        // Earlier in Nov: Owner paid 200 for a team meal with Charlie (100 owner share, 100 owed by Charlie)
        val earlierTxn = h.expense("txn_earlier").copy(
            timestamp = inNov - 3600_000L,
            merchant = "Coffee",
            category = "Food & Drink",
            totalAmount = 200.0,
            companions = "Charlie",
            myShare = 100.0,
            owedToMe = 100.0,
            status = TransactionStatus.PENDING,
            reviewState = ReviewState.CONFIRMED
        )
        h.tDao.insert(com.financeautomation.data.local.entity.TransactionEntity.fromDomain(earlierTxn))

        // Before friend paid: Nov living burn = 100.0
        var txns = h.tDao.rows.values.map { it.toDomain() }
        assertEquals(100.0, LivingBurn.total(txns, novWindow, emptySet()), 0.001)

        // Charlie paid 300 for owner
        h.repo.logPayable("Charlie", 300.0, "Team lunch", inNov, "Food & Drink")

        txns = h.tDao.rows.values.map { it.toDomain() }
        // Living burn must be 400.0 (100.0 earlier share + 300.0 friend paid share)
        val burnBeforeNetting = LivingBurn.total(txns, novWindow, emptySet())
        assertEquals(400.0, burnBeforeNetting, 0.001)

        // Execute netting: 100 offset against earlier debit
        val nettingUseCase = NettingUseCase()
        val allDebits = listOf(
            LedgerDebit(
                tab = "Nov26",
                row = 4,
                uuid = earlierTxn.id,
                dateSerial = earlierTxn.dateSerial,
                timeSerial = earlierTxn.timeSerial,
                account = earlierTxn.account,
                item = earlierTxn.merchant,
                tripTag = "",
                category = earlierTxn.category,
                total = earlierTxn.totalAmount,
                companions = earlierTxn.companions,
                myShare = earlierTxn.myShare,
                owed = earlierTxn.owedToMe,
                status = earlierTxn.status.value
            )
        )
        val nettingRes = nettingUseCase.nettingOnPayable(
            creditor = "Charlie",
            amount = 300.0,
            debits = allDebits,
            settleOneDebtFn = { row, paid, _, _ ->
                SettlementWaterfallUseCase().settleOneDebt(row, paid, "Charlie", false)
            }
        )

        // Netting offsets 100, leaving 200 net owed to Charlie
        assertEquals(100.0, nettingRes.offsetTotal, 0.001)
        assertEquals(200.0, nettingRes.netAmount, 0.001)

        // Even after netting modifies debt statuses, living burn is still exactly 400.0
        assertEquals(400.0, LivingBurn.total(txns, novWindow, emptySet()), 0.001)
    }

    @Test
    fun refundsStillReduceBurnOnceWithFriendPaidExpense() = runBlocking {
        val h = SyncHarness(tabs = listOf("Nov26"))

        // Charlie paid 300 for owner's clothes
        h.repo.logPayable("Charlie", 300.0, "Shirt", inNov, "Shopping")

        val txns = h.tDao.rows.values.map { it.toDomain() }
        val refund = Inflow(
            id = "inf_refund_1",
            timestamp = inNov + 3600_000L,
            amount = 50.0,
            account = "SBI",
            inflowType = "Refund",
            source = "Vendor",
            note = "Returned shirt discount",
            status = "CONFIRMED"
        )

        // Living burn is 300 - 50 = 250
        val burn = LivingBurn.total(txns, novWindow, emptySet(), listOf(refund))
        assertEquals(250.0, burn, 0.001)
    }

    @Test
    fun sheetAppendsBothDebitAndPayableRowsForFriendPaid() = runBlocking {
        val server = ScriptedSheetsServer(listOf("Nov26"))
        server.customRead = { call ->
            if (call.url.contains("values:batchGet")) {
                """{"valueRanges":[
                    {"range":"'Nov26'!A4:K","majorDimension":"ROWS","values":[]},
                    {"range":"'Nov26'!AC4:AC","majorDimension":"ROWS","values":[]},
                    {"range":"'Nov26'!M4:T","majorDimension":"ROWS","values":[]},
                    {"range":"'Nov26'!AD4:AD","majorDimension":"ROWS","values":[]},
                    {"range":"'Nov26'!V4:AB","majorDimension":"ROWS","values":[]},
                    {"range":"'Nov26'!AE4:AE","majorDimension":"ROWS","values":[]}
                ]}"""
            } else null
        }

        val qDao = InMemoryQueueDao()
        val tDao = InMemoryTransactionDao()
        val pDao = InMemoryPayableDao()

        val executor = createTestAccountingSyncExecutor(
            sheetsClient = server.client(),
            syncQueueDao = qDao,
            transactionDao = tDao,
            payableDao = pDao
        )

        val req = LogPayableRequest(
            requestId = "pay_req_1",
            creditor = "Charlie",
            amount = 300.0,
            whatFor = "Dinner",
            timestamp = inNov,
            category = "Food & Drink",
            transactionId = "txn_friend_1"
        )
        val queueItem = SyncQueueEntity(
            id = 1L,
            transactionId = "pay_req_1",
            operationType = SyncQueueEntity.OP_LOG_PAYABLE,
            payloadJson = AccountingRequestJsonAdapter.payableRequestToJson(req)
        )
        qDao.insert(queueItem)

        val syncRes = executor.executeLogPayable(queueItem, "test_sheet_id", "Nov26")
        assertTrue("executeLogPayable must succeed", syncRes is com.financeautomation.core.model.Result.Success<*>)

        // Check batchUpdate calls posted to sheet: must contain both Table 1 (debit A4:K4) and Table 3 (payable V4:AB4)
        val batchCalls = server.calls.filter { it.method == "POST" && it.url.contains("values:batchUpdate") }
        val debitCall = batchCalls.firstOrNull { it.body.contains("'Nov26'!A4:K4") }
        assertNotNull("Must post batchUpdate for Table 1 debit row", debitCall)

        val debitBody = Json.parseToJsonElement(debitCall!!.body).jsonObject
        val debitDataArray = debitBody["data"]!!.jsonArray
        val debitRangeObj = debitDataArray.first { it.jsonObject["range"]?.jsonPrimitive?.content == "'Nov26'!A4:K4" }.jsonObject
        val debitValues = debitRangeObj["values"]!!.jsonArray[0].jsonArray
        assertEquals("Other", debitValues[2].jsonPrimitive.content) // Account
        assertEquals("Dinner", debitValues[3].jsonPrimitive.content) // Merchant/Item
        assertEquals("Food & Drink", debitValues[5].jsonPrimitive.content) // Category
        assertEquals(300.0, debitValues[6].jsonPrimitive.content.toDouble(), 0.001) // Total
        assertEquals("Personal", debitValues[7].jsonPrimitive.content) // Companions
        assertEquals(300.0, debitValues[8].jsonPrimitive.content.toDouble(), 0.001) // MyShare
        assertEquals(0.0, debitValues[9].jsonPrimitive.content.toDouble(), 0.001) // OwedToMe

        val payableCall = batchCalls.firstOrNull { it.body.contains("'Nov26'!V4:AB4") }
        assertNotNull("Must post batchUpdate for Table 3 payable row", payableCall)

        val payableBody = Json.parseToJsonElement(payableCall!!.body).jsonObject
        val payableDataArray = payableBody["data"]!!.jsonArray
        val payableRangeObj = payableDataArray.first { it.jsonObject["range"]?.jsonPrimitive?.content == "'Nov26'!V4:AB4" }.jsonObject
        val payableValues = payableRangeObj["values"]!!.jsonArray[0].jsonArray
        assertEquals("Charlie", payableValues[2].jsonPrimitive.content) // PaidBy
        assertEquals("Dinner (Food & Drink)", payableValues[3].jsonPrimitive.content) // WhatFor with category
        assertEquals(300.0, payableValues[4].jsonPrimitive.content.toDouble(), 0.001) // AmountIOwe
        assertEquals("Pending", payableValues[6].jsonPrimitive.content) // Status
    }

    @Test
    fun categoryDefaultsToOtherWhenBlank() = runBlocking {
        val h = SyncHarness(tabs = listOf("Nov26"))
        h.repo.logPayable("Charlie", 150.0, "Misc", inNov, "   ")

        val txns = h.tDao.rows.values.map { it.toDomain() }
        assertEquals(1, txns.size)
        assertEquals(CategoryConstants.CATEGORY_OTHER, txns.first().category)
    }
}
