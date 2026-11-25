package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.domain.model.ReconcileOutcome
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A "Friend paid for me" debt is saved before it has a month. It must get one when it reaches the sheet, and a leftover
 * one without a month must not stay in the app after the sheet no longer holds it. Real repository, scripted Sheets
 * server, an injected clock and invented data.
 */
class UnplacedPayableTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val novemberNoon = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val augustNoon = ZonedDateTime.of(2026, 8, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun harness(confirmed: Boolean = true): SyncHarness {
        val h = SyncHarness(tabs = listOf("Nov26"))
        h.now = novemberNoon
        if (confirmed) h.preferences[PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID] = "test_sheet_id"
        return h
    }

    private fun unplaced(uuid: String, incurred: Long = novemberNoon - 60_000L) = PayableEntity(
        uuid = uuid, dateIncurredSerial = DateSerialUtils.toDateSerial(incurred), timeSerial = 0.5, paidBy = "Riya",
        whatFor = "Dinner", amountIOwe = 300.0, dateRepaidSerial = null, repaymentStatus = "Pending", tabName = "",
        sheetRowIndex = null
    )

    /** One Nov26 tab that holds only the payable rows given as uuid to amount. */
    private fun SyncHarness.serve(vararg payables: Pair<String, Double>) {
        val rows = payables.joinToString(",") { """[46000,0.5,"Riya","Dinner",${it.second},"","Pending"]""" }
        val ids = payables.joinToString(",") { "[\"${it.first}\"]" }
        val body = """{"valueRanges":[
            {"range":"'Nov26'!A4:L","majorDimension":"ROWS","values":[]},
            {"range":"'Nov26'!AD4:AD","majorDimension":"ROWS","values":[]},
            {"range":"'Nov26'!N4:U","majorDimension":"ROWS","values":[]},
            {"range":"'Nov26'!AE4:AE","majorDimension":"ROWS","values":[]},
            {"range":"'Nov26'!W4:AC","majorDimension":"ROWS","values":[$rows]},
            {"range":"'Nov26'!AF4:AF","majorDimension":"ROWS","values":[$ids]}
        ]}"""
        server.customRead = { call -> if (call.url.contains("values:batchGet")) body else null }
    }

    private suspend fun SyncHarness.reconcile(): ReconcileOutcome {
        val res = repo.reconcileWithSheetOutcome()
        assertTrue("reconcile must succeed: $res", res is Result.Success)
        return (res as Result.Success).data
    }

    @Test
    fun aDeliveredDebtRecordsItsMonthAndFollowsTheSheetAfterwards() = runBlocking {
        val h = harness()
        val saved = h.repo.logPayable("Riya", 300.0, "Dinner", novemberNoon - 60_000L)
        val id = (saved as Result.Success).data
        // The read back after sending fails, so no read ever sees the new row: only the delivery can record the month
        var wrote = false
        h.server.fail = { call, _ ->
            if (call.method != "GET") wrote = true
            if (wrote && call.method == "GET" && call.url.contains("values:batchGet")) {
                400 to SyncHarness.errorBody(400, "invalidArgument")
            } else {
                null
            }
        }

        h.repo.syncPendingTransactions()

        assertTrue("the delivery must have completed: ${h.qDao.queue.values}", h.qDao.queue.isEmpty())
        assertEquals("Nov26", h.pDao.storage[id]?.tabName)

        // Its row is then cleared in the sheet
        h.server.fail = { _, _ -> null }
        h.now += 11 * 60_000L
        h.serve()
        h.reconcile()
        assertNull(h.pDao.storage[id])
    }

    @Test
    fun aLeftoverDebtWithNoMonthIsRemovedAndCounted() = runBlocking {
        val h = harness()
        h.pDao.upsert(unplaced("pay_left"))
        h.serve()

        val out = h.reconcile()

        assertNull(h.pDao.storage["pay_left"])
        assertEquals(1, out.pruned)
    }

    @Test
    fun aDebtTheSheetHoldsIsKeptAndGetsItsMonth() = runBlocking {
        val h = harness()
        h.pDao.upsert(unplaced("pay_held"))
        h.serve("pay_held" to 300.0)

        assertEquals(0, h.reconcile().pruned)
        assertEquals("Nov26", h.pDao.storage["pay_held"]!!.tabName)
    }

    @Test
    fun aDebtStillInTheQueueIsKeptInEveryQueueState() = runBlocking {
        val h = harness()
        val states = listOf(
            "pay_waiting" to SyncQueueEntity.STATUS_PENDING,
            "pay_sending" to SyncQueueEntity.STATUS_IN_PROGRESS,
            "pay_failed" to SyncQueueEntity.STATUS_FAILED
        )
        for ((id, status) in states) {
            h.pDao.upsert(unplaced(id))
            h.qDao.insert(
                SyncQueueEntity(
                    transactionId = id, operationType = SyncQueueEntity.OP_LOG_PAYABLE, payloadJson = "{}",
                    status = status
                )
            )
        }
        h.serve()

        assertEquals(0, h.reconcile().pruned)
        for ((id, _) in states) assertNotNull("$id must be kept", h.pDao.storage[id])
    }

    @Test
    fun aDebtFromAMonthThatWasNotReadIsKept() = runBlocking {
        val h = harness()
        h.pDao.upsert(unplaced("pay_august", incurred = augustNoon))
        h.serve()

        assertEquals(0, h.reconcile().pruned)
        assertNotNull(h.pDao.storage["pay_august"])
    }

    @Test
    fun anUnconfirmedWorkbookRemovesNoDebt() = runBlocking {
        val h = harness(confirmed = false)
        h.pDao.upsert(unplaced("pay_left"))
        h.serve()

        assertEquals(0, h.reconcile().pruned)
        assertNotNull(h.pDao.storage["pay_left"])
    }

    @Test
    fun aWorkbookStillBeingRefilledRemovesNoDebt() = runBlocking {
        val h = harness()
        h.preferences[PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR] = "2026"
        h.pDao.upsert(unplaced("pay_left"))
        h.serve()

        assertEquals(0, h.reconcile().pruned)
        assertNotNull(h.pDao.storage["pay_left"])
    }

    @Test
    fun aFailedReadRemovesNoDebt() = runBlocking {
        val h = harness()
        h.pDao.upsert(unplaced("pay_left"))
        h.server.fail = { call, _ ->
            if (call.url.contains("values:batchGet")) 400 to SyncHarness.errorBody(400, "invalidArgument") else null
        }

        assertTrue(h.repo.reconcileWithSheetOutcome() is Result.Error)
        assertNotNull(h.pDao.storage["pay_left"])
    }

    @Test
    fun blankMonthWithEarlierMonthIncurredLogsDiagnostics() = runBlocking {
        Diagnostics.clear()
        val h = harness()
        h.pDao.upsert(unplaced("pay_august_blank", incurred = augustNoon))
        h.serve()

        h.reconcile()

        val reasons = Diagnostics.getRecords().map { it.reason }
        assertTrue("must record DEBT_NOT_IN_SHEET_READ: $reasons", reasons.contains("DEBT_NOT_IN_SHEET_READ tabs=Nov26"))
        assertTrue(
            "must record DEBT_NOT_IN_SHEET with recorded=none incurred=Aug26 queued=no status=pending gate=open: $reasons",
            reasons.contains("DEBT_NOT_IN_SHEET recorded=none incurred=Aug26 queued=no status=pending gate=open")
        )
    }

    @Test
    fun recordedMonthWhoseTabIsNotInWorkbookLogsDiagnostics() = runBlocking {
        Diagnostics.clear()
        val h = harness()
        h.pDao.upsert(unplaced("pay_sep_recorded", incurred = augustNoon).copy(tabName = "Sep26"))
        h.serve()

        h.reconcile()

        val reasons = Diagnostics.getRecords().map { it.reason }
        assertTrue("must record DEBT_NOT_IN_SHEET_READ: $reasons", reasons.contains("DEBT_NOT_IN_SHEET_READ tabs=Nov26"))
        assertTrue(
            "must record DEBT_NOT_IN_SHEET with recorded=Sep26 incurred=Aug26: $reasons",
            reasons.contains("DEBT_NOT_IN_SHEET recorded=Sep26 incurred=Aug26 queued=no status=pending gate=open")
        )
    }

    @Test
    fun queuedDebtLogsDiagnosticsWithQueuedYes() = runBlocking {
        Diagnostics.clear()
        val h = harness()
        h.pDao.upsert(unplaced("pay_queued_item", incurred = augustNoon))
        h.qDao.insert(
            SyncQueueEntity(
                transactionId = "pay_queued_item",
                operationType = SyncQueueEntity.OP_LOG_PAYABLE,
                payloadJson = "{}",
                status = SyncQueueEntity.STATUS_PENDING
            )
        )
        h.serve()

        h.reconcile()

        val reasons = Diagnostics.getRecords().map { it.reason }
        assertTrue(
            "must record queued=yes: $reasons",
            reasons.contains("DEBT_NOT_IN_SHEET recorded=none incurred=Aug26 queued=yes status=pending gate=open")
        )
    }

    @Test
    fun unconfirmedWorkbookLogsDiagnosticsWithGateUnconfirmed() = runBlocking {
        Diagnostics.clear()
        val h = harness(confirmed = false)
        h.pDao.upsert(unplaced("pay_unconfirmed_item", incurred = augustNoon))
        h.serve()

        h.reconcile()

        val reasons = Diagnostics.getRecords().map { it.reason }
        assertTrue(
            "must record gate=unconfirmed: $reasons",
            reasons.contains("DEBT_NOT_IN_SHEET recorded=none incurred=Aug26 queued=no status=pending gate=unconfirmed")
        )
    }

    @Test
    fun requeuePendingLogsDiagnosticsWithGateRequeue() = runBlocking {
        Diagnostics.clear()
        val h = harness()
        h.preferences[PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR] = "2026"
        h.pDao.upsert(unplaced("pay_requeue_item", incurred = augustNoon))
        h.serve()

        h.reconcile()

        val reasons = Diagnostics.getRecords().map { it.reason }
        assertTrue(
            "must record gate=requeue: $reasons",
            reasons.contains("DEBT_NOT_IN_SHEET recorded=none incurred=Aug26 queued=no status=pending gate=requeue")
        )
    }

    @Test
    fun debtTheSheetHoldsWritesNoDiagnosticLine() = runBlocking {
        Diagnostics.clear()
        val h = harness()
        h.pDao.upsert(unplaced("pay_held_item"))
        h.serve("pay_held_item" to 300.0)

        h.reconcile()

        val reasons = Diagnostics.getRecords().map { it.reason }
        assertTrue(
            "must write no DEBT_NOT_IN_SHEET lines when sheet holds debt: $reasons",
            reasons.none { it.startsWith("DEBT_NOT_IN_SHEET") }
        )
    }

    @Test
    fun diagnosticsLinesContainNoInventedNameAmountOrUuid() = runBlocking {
        Diagnostics.clear()
        val h = harness()
        val inventedUuid = "pay_secret_id_999"
        val inventedPerson = "UniqueFriendNameXYZ"
        val inventedAmount = 888.88
        val inventedWhat = "SecretPrivateDinner"
        h.pDao.upsert(
            PayableEntity(
                uuid = inventedUuid,
                dateIncurredSerial = DateSerialUtils.toDateSerial(augustNoon),
                timeSerial = 0.5,
                paidBy = inventedPerson,
                whatFor = inventedWhat,
                amountIOwe = inventedAmount,
                dateRepaidSerial = null,
                repaymentStatus = "Partial",
                tabName = "",
                sheetRowIndex = null
            )
        )
        h.serve()

        h.reconcile()

        val records = Diagnostics.getRecords()
        val debtLines = records.map { it.reason }.filter { it.startsWith("DEBT_NOT_IN_SHEET") }
        assertTrue("must have recorded at least one diagnostic line", debtLines.isNotEmpty())
        for (line in debtLines) {
            assertFalse("line must not contain uuid: $line", line.contains(inventedUuid))
            assertFalse("line must not contain friend name: $line", line.contains(inventedPerson))
            assertFalse("line must not contain amount: $line", line.contains("888"))
            assertFalse("line must not contain description: $line", line.contains(inventedWhat))
            assertFalse("line must not contain spreadsheet id: $line", line.contains("test_sheet_id"))
        }
        assertTrue("must reflect partial status: $debtLines", debtLines.any { it.contains("status=partial") })
    }

    @Test
    fun removalCountsAreRightForPruneAndDebtSync() = runBlocking {
        Diagnostics.clear()
        val h = harness()

        val txn = TransactionEntity(
            id = "txn_prune_1",
            timestamp = novemberNoon - 120_000L,
            dateSerial = DateSerialUtils.toDateSerial(novemberNoon - 120_000L),
            timeSerial = 0.5,
            account = "Cash",
            merchant = "Cafe",
            category = "Food",
            note = "",
            totalAmount = 150.0,
            myShare = 150.0,
            owedToMe = 0.0,
            companions = "Personal",
            status = TransactionStatus.PERSONAL.value,
            tripTag = "",
            sheetRowIndex = 4,
            syncState = SyncState.SYNCED.name,
            reviewState = ReviewState.CONFIRMED.name
        )
        h.tDao.insert(txn)
        h.pDao.upsert(unplaced("pay_unplaced_nov", incurred = novemberNoon - 60_000L))

        h.serve()
        h.reconcile()

        val reasons = Diagnostics.getRecords().map { it.reason }
        assertTrue(
            "SHEET_PRUNE must report expenses=1 income=0 debts=1: $reasons",
            reasons.contains("SHEET_PRUNE removed=2 expenses=1 income=0 debts=1")
        )

        Diagnostics.clear()
        h.pDao.upsert(unplaced("pay_placed_nov").copy(tabName = "Nov26"))
        h.serve()
        h.reconcile()

        val reasonsSync = Diagnostics.getRecords().map { it.reason }
        assertTrue(
            "SHEET_DEBT_SYNC must report removed=1: $reasonsSync",
            reasonsSync.contains("SHEET_DEBT_SYNC removed=1")
        )
    }
}
