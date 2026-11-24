package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.SubscriptionCycleDao
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SubscriptionCycleEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.domain.model.ReconcileOutcome
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.LivingBurn
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * The sheet is the source of truth and Room is a cache: a synced entry the sheet no longer holds is removed from the
 * app after a complete read, and never in the cases where the read cannot be trusted. Real repository, in-memory
 * tables, a scripted Sheets server, an injected clock and invented data.
 */
class SheetClearPruneTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val novemberNoon = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun harness(cycleDao: SubscriptionCycleDao? = null): SyncHarness {
        val h = SyncHarness(tabs = listOf("Nov26"), cycleDao = cycleDao)
        h.now = novemberNoon
        return h
    }

    /** A workbook that already acknowledged this device's rows. */
    private fun SyncHarness.confirmWorkbook(id: String = "test_sheet_id") {
        preferences[PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID] = id
    }

    private suspend fun SyncHarness.saveSynced(
        id: String,
        amount: Double = 111.0,
        companions: String = "Personal",
        owed: Double = 0.0,
        status: TransactionStatus = TransactionStatus.PERSONAL,
        row: Int = 4,
        ageMillis: Long = 60_000L,
        syncState: SyncState = SyncState.SYNCED,
        reviewState: ReviewState = ReviewState.CONFIRMED,
        merchant: String = "Tea"
    ): Transaction {
        val t = expense(id, merchant = merchant, amount = amount, ageMillis = ageMillis).copy(
            companions = companions, myShare = amount - owed, owedToMe = owed, status = status,
            syncState = syncState, reviewState = reviewState, sheetRowIndex = row
        )
        tDao.insert(TransactionEntity.fromDomain(t))
        return t
    }

    private fun debitJson(t: Transaction): String =
        """[46000,0.5,"SBI","${t.merchant}","","${t.category}",${t.totalAmount},"${t.companions}",${t.myShare},${t.owedToMe},"${t.status.value}",""]"""

    /** One month tab: the rows given, with uuids in the hidden id columns. */
    private fun snapshot(
        debits: List<Transaction> = emptyList(),
        inflows: List<Pair<String, Double>> = emptyList(),
        payables: List<Triple<String, String, Double>> = emptyList()
    ): String {
        val tab = "Nov26"
        val debitRows = debits.joinToString(",") { debitJson(it) }
        val debitIds = debits.joinToString(",") { "[\"${it.id}\"]" }
        val inflowRows = inflows.joinToString(",") { """[46000,0.5,"SBI","Salary","Asha","Pay",${it.second},""]""" }
        val inflowIds = inflows.joinToString(",") { "[\"${it.first}\"]" }
        val payableRows = payables.joinToString(",") { """[46000,0.5,"${it.second}","Dinner",${it.third},"","Pending"]""" }
        val payableIds = payables.joinToString(",") { "[\"${it.first}\"]" }
        return """{"valueRanges":[
            {"range":"'$tab'!A4:L","majorDimension":"ROWS","values":[$debitRows]},
            {"range":"'$tab'!AD4:AD","majorDimension":"ROWS","values":[$debitIds]},
            {"range":"'$tab'!N4:U","majorDimension":"ROWS","values":[$inflowRows]},
            {"range":"'$tab'!AE4:AE","majorDimension":"ROWS","values":[$inflowIds]},
            {"range":"'$tab'!W4:AC","majorDimension":"ROWS","values":[$payableRows]},
            {"range":"'$tab'!AF4:AF","majorDimension":"ROWS","values":[$payableIds]}
        ]}"""
    }

    private fun SyncHarness.serve(
        debits: List<Transaction> = emptyList(),
        inflows: List<Pair<String, Double>> = emptyList(),
        payables: List<Triple<String, String, Double>> = emptyList()
    ) {
        val body = snapshot(debits, inflows, payables)
        server.customRead = { call -> if (call.url.contains("values:batchGet")) body else null }
    }

    private suspend fun SyncHarness.reconcile(): ReconcileOutcome {
        val res = repo.reconcileWithSheetOutcome()
        assertTrue("reconcile must succeed: $res", res is Result.Success)
        return (res as Result.Success).data
    }

    private fun payable(uuid: String, paidBy: String, amount: Double) = PayableEntity(
        uuid = uuid, dateIncurredSerial = 46000, timeSerial = 0.5, paidBy = paidBy, whatFor = "Dinner",
        amountIOwe = amount, dateRepaidSerial = null, repaymentStatus = "Pending", tabName = "Nov26",
        sheetRowIndex = 4
    )

    private fun kabirReceivable(rows: Collection<TransactionEntity>): Double =
        rows.map { it.toDomain() }
            .flatMap { AccountingUtils.calculateReceivablesForTransaction(it) }
            .filter { it.key == AccountingUtils.normalizePersonName("Kabir") }
            .sumOf { it.amount }

    // (a) and (h): the reported bug

    @Test
    fun aSyncedRowClearedInTheSheetIsRemovedWithItsPayableAndTheFriendBalanceIsCorrected() = runBlocking {
        val h = harness()
        val keep = h.saveSynced("txn_keep", amount = 111.0, row = 4)
        val split = h.saveSynced(
            "txn_split", amount = 900.0, companions = "Kabir", owed = 450.0,
            status = TransactionStatus.PENDING, row = 5, merchant = "Dinner"
        )
        h.pDao.upsert(payable("pay_riya", "Riya", 300.0))
        h.pDao.upsert(payable("pay_gone", "Riya", 125.0))

        // Pass one: the sheet holds everything, so the workbook is acknowledged and nothing is removed
        h.serve(debits = listOf(keep, split), payables = listOf(Triple("pay_riya", "Riya", 300.0), Triple("pay_gone", "Riya", 125.0)))
        val first = h.reconcile()
        assertEquals(0, first.pruned)
        assertEquals("test_sheet_id", h.preferences[PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID])
        assertEquals(450.0, kabirReceivable(h.tDao.rows.values), 0.001)

        // Pass two: the user cleared the Dinner row and one payable in the sheet
        h.serve(debits = listOf(keep), payables = listOf(Triple("pay_riya", "Riya", 300.0)))
        val second = h.reconcile()

        assertEquals(1, second.pruned)
        assertNull("the cleared row must leave the app", h.tDao.rows["txn_split"])
        assertNotNull(h.tDao.rows["txn_keep"])
        assertNull("its payable is rebuilt from the sheet", h.pDao.storage["pay_gone"])
        assertNotNull(h.pDao.storage["pay_riya"])
        assertEquals("Kabir no longer owes anything for the removed row", 0.0, kabirReceivable(h.tDao.rows.values), 0.001)

        // Living burn comes only from the shared definition and now counts only what remains
        val window = LivingBurn.monthWindow(2026, 11)
        val burn = LivingBurn.total(h.tDao.rows.values.map { it.toDomain() }, window, emptySet())
        assertEquals(111.0, burn, 0.001)

        val notice = h.repo.takeSheetPruneNotice()
        assertEquals(1, notice.pruned)
        assertFalse(notice.manyMissing)
        assertEquals("the message is shown once", 0, h.repo.takeSheetPruneNotice().pruned)
    }

    /** Written against the old behaviour: fails on main (the cleared row stays), passes with the prune. */
    @Test
    fun aRowClearedInTheSheetNoLongerShowsInTheApp() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        val a = h.saveSynced("txn_a", amount = 111.0, row = 4)
        val b = h.saveSynced("txn_b", amount = 900.0, row = 5)
        h.serve(debits = listOf(a))
        assertTrue(h.repo.reconcileWithSheet() is Result.Success)
        assertNotNull(h.tDao.rows["txn_a"])
        assertNull("the row cleared in the sheet must not stay in the app", h.tDao.rows[b.id])
    }

    // (b) rows that are not settled are never removed

    @Test
    fun pendingFailedQueuedAndReviewRowsAreKept() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        h.saveSynced("txn_pending", row = 4, syncState = SyncState.PENDING)
        h.saveSynced("txn_failed", row = 5, syncState = SyncState.FAILED)
        h.saveSynced("txn_review", row = 6, syncState = SyncState.PENDING, reviewState = ReviewState.NEEDS_REVIEW)
        h.saveSynced("txn_noindex", syncState = SyncState.SYNCED).let {
            h.tDao.insert(TransactionEntity.fromDomain(it.copy(id = "txn_noindex", sheetRowIndex = null)))
        }
        for ((id, status) in listOf(
            "txn_q_pending" to SyncQueueEntity.STATUS_PENDING,
            "txn_q_progress" to SyncQueueEntity.STATUS_IN_PROGRESS,
            "txn_q_failed" to SyncQueueEntity.STATUS_FAILED
        )) {
            h.saveSynced(id, row = 7)
            h.qDao.insert(
                SyncQueueEntity(
                    transactionId = id, operationType = SyncQueueEntity.OP_UPDATE, payloadJson = id, status = status,
                    retryCount = if (status == SyncQueueEntity.STATUS_FAILED) 999 else 0
                )
            )
        }
        h.saveSynced("txn_settled", row = 8)
        h.serve(debits = emptyList())

        val out = h.reconcile()

        assertEquals("only the settled, unqueued row goes", 1, out.pruned)
        assertNull(h.tDao.rows["txn_settled"])
        for (id in listOf("txn_pending", "txn_failed", "txn_review", "txn_noindex", "txn_q_pending", "txn_q_progress", "txn_q_failed")) {
            assertNotNull("$id must be kept", h.tDao.rows[id])
        }
        assertEquals("queue untouched", 3, h.qDao.queue.size)
    }

    // (c) a read that failed or was skipped removes nothing

    @Test
    fun aFailedReadAndACooldownSkipRemoveNothing() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        h.saveSynced("txn_a", row = 4)
        h.saveSynced("txn_b", row = 5)

        h.server.fail = { call, _ -> if (call.url.contains("values:batchGet")) 400 to SyncHarness.errorBody(400, "invalidArgument") else null }
        val failed = h.repo.reconcileWithSheetOutcome()
        assertTrue(failed is Result.Error)
        assertEquals(2, h.tDao.rows.size)
        assertEquals(0, h.repo.takeSheetPruneNotice().pruned)
    }

    @Test
    fun aCooldownSkipRemovesNothingAndTheNextCompleteReadDoes() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        h.saveSynced("txn_a", row = 4)
        h.saveSynced("txn_b", row = 5)
        h.serve(debits = emptyList())

        // Use up the ten minute window with a read that fails
        h.server.fail = { call, _ -> if (call.url.contains("values:batchGet")) 400 to SyncHarness.errorBody(400, "invalidArgument") else null }
        assertTrue(h.repo.reconcileAllMonths() is Result.Error)
        h.server.fail = { _, _ -> null }

        val skipped = h.repo.reconcileAllMonths()
        assertTrue("second call inside ten minutes is skipped", skipped is Result.Error)
        assertEquals("a skipped reconcile prunes nothing", 2, h.tDao.rows.size)

        h.now += 11 * 60_000L
        assertTrue(h.repo.reconcileAllMonths() is Result.Success)
        assertEquals("a complete read after the window prunes", 0, h.tDao.rows.size)
    }

    // (d) a replaced workbook is never pruned against

    @Test
    fun aReplacedWorkbookPrunesNothingAndTheLocalDataIsRequeued() = runBlocking {
        val h = harness()
        h.confirmWorkbook("old_workbook_id")
        h.saveSynced("txn_a", row = 4)
        h.saveSynced("txn_b", row = 5)
        h.iDao.insert(InflowEntity("cred_a", novemberNoon - 1000, 900.0, "SBI", "Salary", "Asha", "Pay", status = "CONFIRMED"))
        h.serve(debits = emptyList())

        val out = h.reconcile()
        assertEquals(0, out.pruned)
        assertEquals(2, h.tDao.rows.size)
        assertNotNull(h.iDao.storage["cred_a"])
        assertEquals("the marker still names the workbook that acknowledged the rows", "old_workbook_id",
            h.preferences[PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID])

        h.repo.requeueYearForNewWorkbook(2026)
        assertEquals(SyncState.PENDING.name, h.tDao.rows["txn_a"]!!.syncState)
        assertEquals(SyncState.PENDING.name, h.tDao.rows["txn_b"]!!.syncState)
        assertTrue(h.qDao.queue.values.any { it.transactionId == "txn_a" && it.operationType == SyncQueueEntity.OP_INSERT })
        assertTrue(h.qDao.queue.values.any { it.transactionId == "cred_a" })

        // And the requeued rows survive further reconciles until they are delivered
        assertEquals(0, h.reconcile().pruned)
        assertEquals(2, h.tDao.rows.size)
    }

    @Test
    fun aPendingRequeueBlocksPruningEvenForTheConfirmedWorkbook() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        h.preferences[PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR] = "2026"
        h.saveSynced("txn_a", row = 4)
        h.serve(debits = emptyList())
        assertEquals(0, h.reconcile().pruned)
        assertNotNull(h.tDao.rows["txn_a"])
    }

    @Test
    fun aNewWorkbookThatHoldsSomeRowsIsAcknowledgedAndPrunedFromTheNextPass() = runBlocking {
        val h = harness()
        h.confirmWorkbook("old_workbook_id")
        val a = h.saveSynced("txn_a", row = 4)
        h.saveSynced("txn_b", row = 5)
        h.serve(debits = listOf(a))

        assertEquals("this pass must not prune", 0, h.reconcile().pruned)
        assertNotNull(h.tDao.rows["txn_b"])
        assertEquals("test_sheet_id", h.preferences[PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID])

        assertEquals("the next pass runs against an acknowledged workbook", 1, h.reconcile().pruned)
        assertNull(h.tDao.rows["txn_b"])
    }

    @Test
    fun aBlankWorkbookWithNoMarkerNeverPrunes() = runBlocking {
        val h = harness()
        h.saveSynced("txn_a", row = 4)
        h.serve(debits = emptyList())
        assertEquals(0, h.reconcile().pruned)
        assertNotNull(h.tDao.rows["txn_a"])
    }

    // (e) and (f) the safety brake

    @Test
    fun missingThirtyOfFortyIsHeldBackAndFlagged() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        val rows = (1..40).map { h.saveSynced("txn_$it", amount = 100.0 + it, row = 3 + it) }
        h.serve(debits = rows.take(10))
        Diagnostics.clear()

        val out = h.reconcile()

        assertEquals(0, out.pruned)
        assertTrue(out.manyMissing)
        assertEquals(40, h.tDao.rows.size)
        assertTrue(Diagnostics.getRecords().any { it.tag == "TransactionRepository" && it.reason.startsWith("SHEET_PRUNE_HELD_BACK") })
        val notice = h.repo.takeSheetPruneNotice()
        assertTrue(notice.manyMissing)
        assertEquals(0, notice.pruned)

        // The sheet is restored: the flag clears
        h.serve(debits = rows)
        assertFalse(h.reconcile().manyMissing)
        assertFalse(h.repo.takeSheetPruneNotice().manyMissing)
    }

    @Test
    fun theBrakeNeedsMoreThanHalfAndMoreThanTwentyRows() = runBlocking {
        // 20 of 30 missing: more than half but not more than 20, so it is removed
        val a = harness()
        a.confirmWorkbook()
        val aRows = (1..30).map { a.saveSynced("a_$it", row = 3 + it) }
        a.serve(debits = aRows.take(10))
        assertEquals(20, a.reconcile().pruned)
        assertEquals(10, a.tDao.rows.size)

        // 21 of 50 missing: more than 20 but not more than half, so it is removed
        val b = harness()
        b.confirmWorkbook()
        val bRows = (1..50).map { b.saveSynced("b_$it", row = 3 + it) }
        b.serve(debits = bRows.take(29))
        assertEquals(21, b.reconcile().pruned)

        // 21 of 40 missing: both limits crossed, held back
        val c = harness()
        c.confirmWorkbook()
        val cRows = (1..40).map { c.saveSynced("c_$it", row = 3 + it) }
        c.serve(debits = cRows.take(19))
        val held = c.reconcile()
        assertEquals(0, held.pruned)
        assertTrue(held.manyMissing)
    }

    @Test
    fun twoRowsMissingOfTenAreRemovedDirectly() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        val rows = (1..10).map { h.saveSynced("txn_$it", amount = 111.0 + it, row = 3 + it) }
        h.serve(debits = rows.take(8))

        val out = h.reconcile()

        assertEquals(2, out.pruned)
        assertFalse(out.manyMissing)
        assertEquals(setOf("txn_9", "txn_10"), rows.map { it.id }.filter { h.tDao.rows[it] == null }.toSet())
        assertEquals(8, h.tDao.rows.size)
    }

    // (g) voided rows

    @Test
    fun aVoidedRowStaysWhileTheSheetHoldsItAndGoesWhenItIsMissing() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        fun voided(id: String, row: Int) = TransactionEntity.fromDomain(
            h.expense(id, merchant = "[Void] Tea", amount = 0.0).copy(
                status = TransactionStatus.VOID, syncState = SyncState.VOID, reviewState = ReviewState.CONFIRMED,
                sheetRowIndex = row, myShare = 0.0, owedToMe = 0.0, totalAmount = 0.0
            )
        )
        h.tDao.insert(voided("void_kept", 4))
        h.tDao.insert(voided("void_gone", 5))
        val live = h.saveSynced("txn_live", row = 6)
        h.serve(debits = listOf(h.tDao.rows["void_kept"]!!.toDomain(), live))

        val out = h.reconcile()

        assertEquals(1, out.pruned)
        assertNotNull("void history kept while the sheet still has it", h.tDao.rows["void_kept"])
        assertNull("a void row missing from the sheet is removed like any other", h.tDao.rows["void_gone"])
        assertNotNull(h.tDao.rows["txn_live"])
    }

    @Test
    fun aVoidWaitingInTheQueueIsKept() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        h.saveSynced("txn_a", row = 4)
        assertTrue(h.repo.voidTransaction("txn_a") is Result.Success)
        assertEquals(1, h.qDao.queue.size)
        h.serve(debits = emptyList())

        assertEquals(0, h.reconcile().pruned)
        assertNotNull(h.tDao.rows["txn_a"])
    }

    // income

    @Test
    fun anIncomeRowClearedInTheSheetIsRemovedAndAQueuedOneIsKept() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        fun inflow(id: String, amount: Double) =
            InflowEntity(id, novemberNoon - 1000, amount, "SBI", "Salary", "Asha", "Pay", status = "CONFIRMED")
        h.iDao.insert(inflow("cred_kept", 900.0))
        h.iDao.insert(inflow("cred_gone", 111.0))
        h.iDao.insert(inflow("cred_queued", 250.0))
        h.qDao.insert(SyncQueueEntity(transactionId = "cred_queued", operationType = SyncQueueEntity.OP_LOG_CREDIT, payloadJson = "{}"))
        h.preferences[SettledInflowMarker.key("cred_gone")] = "1"
        h.serve(inflows = listOf("cred_kept" to 900.0))

        val out = h.reconcile()

        assertEquals(1, out.pruned)
        assertNotNull(h.iDao.storage["cred_kept"])
        assertNull(h.iDao.storage["cred_gone"])
        assertNotNull(h.iDao.storage["cred_queued"])
        assertNull("the settled marker of the removed income goes with it", h.preferences[SettledInflowMarker.key("cred_gone")])
        assertEquals(1, h.qDao.queue.size)
    }

    // linked data and scope

    @Test
    fun aPaidSubscriptionCycleThatPointedAtARemovedRowIsClosedWithoutALink() = runBlocking {
        val cycles = ConcurrentHashMap<String, SubscriptionCycleEntity>()
        val cycleDao = Proxy.newProxyInstance(
            SubscriptionCycleDao::class.java.classLoader, arrayOf(SubscriptionCycleDao::class.java)
        ) { _, m, args ->
            when (m.name) {
                "releaseTransactions" -> {
                    @Suppress("UNCHECKED_CAST") val ids = (args!![0] as List<String>).toSet()
                    val at = args[1] as Long
                    cycles.values.filter { it.transactionId in ids }.forEach {
                        cycles[it.id] = it.copy(state = "SKIPPED", transactionId = null, updatedAt = at)
                    }
                    Unit
                }
                else -> throw UnsupportedOperationException("unexpected ${m.name}")
            }
        } as SubscriptionCycleDao
        val h = harness(cycleDao)
        h.confirmWorkbook()
        h.saveSynced("txn_sub", amount = 199.0, row = 4)
        h.saveSynced("txn_other", amount = 111.0, row = 5)
        cycles["c1"] = SubscriptionCycleEntity("c1", "sub_a", 20000, "PAID", "txn_sub", 0, 1L)
        cycles["c2"] = SubscriptionCycleEntity("c2", "sub_b", 20001, "PAID", "txn_other", 0, 1L)
        h.serve(debits = listOf(h.tDao.rows["txn_other"]!!.toDomain()))

        assertEquals(1, h.reconcile().pruned)

        assertEquals("SKIPPED", cycles["c1"]!!.state)
        assertNull(cycles["c1"]!!.transactionId)
        assertEquals("PAID", cycles["c2"]!!.state)
        assertEquals("txn_other", cycles["c2"]!!.transactionId)
    }

    @Test
    fun rowsInATabThatWasNotReadAreLeftAlone() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        val august = ZonedDateTime.of(2026, 8, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        h.saveSynced("txn_aug", row = 4, ageMillis = novemberNoon - august)
        h.saveSynced("txn_nov", row = 5)
        h.serve(debits = emptyList())

        assertEquals(1, h.reconcile().pruned)
        assertNotNull("August was not part of this read", h.tDao.rows["txn_aug"])
        assertNull(h.tDao.rows["txn_nov"])
    }

    @Test
    fun aRowThatBecameQueuedAfterTheReadBeganIsNotRemoved() = runBlocking {
        val h = harness()
        h.confirmWorkbook()
        h.saveSynced("txn_edit", row = 4)
        h.saveSynced("txn_plain", row = 5)
        h.serve(debits = emptyList())
        // The user edits the row while the read is in flight: the edit queues it and marks it pending
        h.server.customRead = { call ->
            if (call.url.contains("values:batchGet")) {
                runBlocking {
                    val row = h.tDao.rows["txn_edit"]!!
                    h.tDao.insert(row.copy(syncState = SyncState.PENDING.name, category = "Travel"))
                    h.qDao.insert(SyncQueueEntity(transactionId = "txn_edit", operationType = SyncQueueEntity.OP_UPDATE, payloadJson = "txn_edit"))
                }
                snapshot()
            } else null
        }

        assertEquals(1, h.reconcile().pruned)
        assertEquals("Travel", h.tDao.rows["txn_edit"]!!.category)
        assertNull(h.tDao.rows["txn_plain"])
    }
}
