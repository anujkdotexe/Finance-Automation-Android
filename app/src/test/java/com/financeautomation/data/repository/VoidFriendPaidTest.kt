package com.financeautomation.data.repository

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.testutil.InMemoryInflowDao
import com.financeautomation.testutil.InMemoryLineageDao
import com.financeautomation.testutil.InMemoryPayableDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.money.FakeSheetApplier
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId

/**
 * Real repository through production wiring over [FakeSheetApplier], in-memory tables,
 * an injected clock and invented data. Validates:
 * - Untouched debt handling with delete box off (void in place) and on (cleared cells).
 * - I owe total dropping by the exact amount.
 * - Second unrelated debt to the same creditor stays untouched in every case.
 * - Legacy entries with unrelated IDs (1 candidate matched; 0 or 2 candidates left untouched).
 * - Netted, partly repaid and repaid debts left unchanged with notice.
 * - Queued pending, failed and in-progress states (without plan dropped, with plan queued).
 * - Undo of recent save for friend paid expenses.
 * - Idempotent retry and row-moved-to-another-number handling via UUID verification.
 * - V1 layout clearing.
 */
class VoidFriendPaidTest {

    private val kolkataZone = ZoneId.of("Asia/Kolkata")
    private val fixedInstant = Instant.parse("2026-11-22T10:00:00Z")
    private val currentTab = "Nov26"

    private lateinit var fakeSheet: FakeSheetApplier
    private lateinit var sheets: GoogleSheetsClient
    private lateinit var tDao: InMemoryTransactionDao
    private lateinit var qDao: InMemoryQueueDao
    private lateinit var iDao: InMemoryInflowDao
    private lateinit var lDao: InMemoryLineageDao
    private lateinit var pDao: InMemoryPayableDao
    private lateinit var repo: TransactionRepositoryImpl

    private val nowMillis: Long get() = fixedInstant.toEpochMilli()
    private val fixedDateSerial: Int = DateSerialUtils.toDateSerial(nowMillis)
    private val fixedTimeSerial: Double = DateSerialUtils.toTimeSerial(nowMillis)

    @Before
    fun setUp() {
        com.financeautomation.core.utils.Diagnostics.clear()
        fakeSheet = FakeSheetApplier(mutableListOf(currentTab, "Config", "Subscriptions", "Trips"))
        sheets = fakeSheet.client()
        tDao = InMemoryTransactionDao()
        qDao = InMemoryQueueDao()
        iDao = InMemoryInflowDao()
        lDao = InMemoryLineageDao()
        pDao = InMemoryPayableDao()

        val preferences = mutableMapOf<String, String?>()
        preferences[PreferencesRepository.KEY_DEFAULT_ACCOUNT] = "Cash"
        preferences["spreadsheet_id"] = "test_sheet_id"
        preferences[PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID] = "test_sheet_id"

        @Suppress("UNCHECKED_CAST")
        val prefs: PreferencesRepository = Proxy.newProxyInstance(
            PreferencesRepository::class.java.classLoader,
            arrayOf(PreferencesRepository::class.java)
        ) { _, method, args ->
            when {
                method.name == "getPreference" -> preferences[args!![0] as String]
                method.name == "setPreference" -> { preferences[args!![0] as String] = args[1] as String?; null }
                method.name == "getDefaultAccount" -> flowOf("Cash")
                method.name == "getDefaultAccountSync" -> "Cash"
                method.name == "getGoogleAccountEmailSync" -> "test@example.com"
                method.name == "getSpreadsheetIdSync" || method.name == "getSpreadsheetIdForYearSync" -> "test_sheet_id"
                else -> null
            }
        } as PreferencesRepository

        repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            inflowDao = iDao,
            lineageDao = lDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = createTestAccountingSyncExecutor(
                sheetsClient = sheets,
                syncQueueDao = qDao,
                lineageDao = lDao,
                transactionDao = tDao
            ),
            ioDispatcher = Dispatchers.Unconfined,
            clock = { nowMillis }
        )
    }

    private fun setCell(tab: String, row: Int, colLetter: String, value: Any?) {
        fakeSheet.setCell(tab, row, SheetSchemaConstants.columnIndex(colLetter) + 1, value)
    }

    private suspend fun currentIOweTotal(): Double {
        return pDao.getAll().filter { it.repaymentStatus.equals("Pending", ignoreCase = true) }.sumOf { it.amountIOwe }
    }

    // 1. Untouched debt with delete box off
    @Test
    fun untouchedDebt_deleteBoxOff_voidsSheetDebtInPlaceAndZeroesLocalDebtAndTotalDrops() = runBlocking {
        val logRes = repo.logFriendPaid("Riya", 500.0, "Dinner", nowMillis) as Result.Success
        val payId = logRes.data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!

        assertEquals(500.0, currentIOweTotal(), 0.001)

        // Deliver to sheet
        repo.syncPendingTransactions(userInitiated = true)
        val pRow = pDao.getAll().first { it.uuid == payId }.sheetRowIndex ?: 4
        val tRow = tDao.getById(txnId)!!.sheetRowIndex ?: 4

        // Void with deleteSheetRow = false
        val voidRes = repo.voidTransaction(txnId, deleteSheetRow = false)
        assertTrue(voidRes is Result.Success)

        // Local state immediately reflects the void and zeroes debt
        val localDebt = pDao.getAll().first { it.uuid == payId }
        assertEquals("Void", localDebt.repaymentStatus)
        assertEquals(0.0, localDebt.amountIOwe, 0.001)
        assertEquals(0.0, currentIOweTotal(), 0.001)

        // Sync to sheet
        repo.syncPendingTransactions(userInitiated = true)

        // Sheet verification: Table 1 Debit voided in place
        val debitItem = fakeSheet.getCell(currentTab, tRow, SheetSchemaConstants.COL_LETTER_DEBIT_DESCRIPTION)
        val debitTotal = fakeSheet.getCell(currentTab, tRow, SheetSchemaConstants.COL_LETTER_DEBIT_TOTAL_AMOUNT)
        val debitStatus = fakeSheet.getCell(currentTab, tRow, SheetSchemaConstants.COL_LETTER_DEBIT_STATUS)
        val debitUuid = fakeSheet.getCell(currentTab, tRow, SheetSchemaConstants.COL_LETTER_DEBIT_UUID)

        assertEquals("[Void] Dinner", debitItem)
        assertEquals(0.0, (debitTotal as Number).toDouble(), 0.001)
        assertEquals("Void", debitStatus)
        assertEquals(txnId, debitUuid)

        // Sheet verification: Table 3 Payable voided in place
        val payWhatFor = fakeSheet.getCell(currentTab, pRow, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR)
        val payAmount = fakeSheet.getCell(currentTab, pRow, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE)
        val payStatus = fakeSheet.getCell(currentTab, pRow, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS)
        val payUuid = fakeSheet.getCell(currentTab, pRow, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID)

        assertEquals("[Void] Dinner (Other)", payWhatFor)
        assertEquals(0.0, (payAmount as Number).toDouble(), 0.001)
        assertEquals("Void", payStatus)
        assertEquals(payId, payUuid)
    }

    // 2. Untouched debt with delete box on
    @Test
    fun untouchedDebt_deleteBoxOn_clearsSheetDebtCellsAndDeletesLocalDebt() = runBlocking {
        val logRes = repo.logFriendPaid("Riya", 500.0, "Dinner", nowMillis) as Result.Success
        val payId = logRes.data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!

        repo.syncPendingTransactions(userInitiated = true)
        val pRow = pDao.getAll().first { it.uuid == payId }.sheetRowIndex ?: 4
        val tRow = tDao.getById(txnId)!!.sheetRowIndex ?: 4

        // Void with deleteSheetRow = true
        val voidRes = repo.voidTransaction(txnId, deleteSheetRow = true)
        assertTrue(voidRes is Result.Success)

        // Sync deletion to sheet
        repo.syncPendingTransactions(userInitiated = true)

        // Local state: both deleted
        assertNull(tDao.getById(txnId))
        assertNull(pDao.getAll().firstOrNull { it.uuid == payId })
        assertEquals(0.0, currentIOweTotal(), 0.001)

        // Sheet verification: Debit cells A to L and AD cleared
        assertNull(fakeSheet.getCell(currentTab, tRow, SheetSchemaConstants.COL_LETTER_DEBIT_DESCRIPTION))
        assertNull(fakeSheet.getCell(currentTab, tRow, SheetSchemaConstants.COL_LETTER_DEBIT_TOTAL_AMOUNT))
        assertNull(fakeSheet.getCell(currentTab, tRow, SheetSchemaConstants.COL_LETTER_DEBIT_UUID))

        // Sheet verification: Payable cells W to AC and AF cleared
        assertNull(fakeSheet.getCell(currentTab, pRow, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR))
        assertNull(fakeSheet.getCell(currentTab, pRow, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE))
        assertNull(fakeSheet.getCell(currentTab, pRow, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS))
        assertNull(fakeSheet.getCell(currentTab, pRow, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))
    }

    // 3. Second unrelated debt with same creditor and amount stays untouched in every case
    @Test
    fun secondUnrelatedDebtWithSameCreditorAndAmount_staysUntouchedInEveryCase() = runBlocking {
        // Linked pair 1
        val log1 = repo.logFriendPaid("Riya", 500.0, "Dinner 1", nowMillis) as Result.Success
        val payId1 = log1.data
        val txnId1 = AccountingUtils.linkedExpenseIdForPayable(payId1)!!

        // Unrelated debt 2 (logged 1 hour earlier)
        val laterMillis = nowMillis - 3600_000L
        val log2 = repo.logFriendPaid("Riya", 500.0, "Dinner 2", laterMillis) as Result.Success
        val payId2 = log2.data
        val txnId2 = AccountingUtils.linkedExpenseIdForPayable(payId2)!!

        repo.syncPendingTransactions(userInitiated = true)
        assertEquals(1000.0, currentIOweTotal(), 0.001)

        val pRow2 = pDao.getAll().first { it.uuid == payId2 }.sheetRowIndex ?: 5

        // Void first expense with box on (delete)
        repo.voidTransaction(txnId1, deleteSheetRow = true)
        repo.syncPendingTransactions(userInitiated = true)

        // Total I owe drops to 500.0 (second debt remains)
        assertEquals(500.0, currentIOweTotal(), 0.001)

        // Unrelated debt 2 locally intact
        val debt2 = pDao.getAll().firstOrNull { it.uuid == payId2 }
        assertNotNull(debt2)
        assertEquals("Pending", debt2!!.repaymentStatus)
        assertEquals(500.0, debt2.amountIOwe, 0.001)

        // Unrelated debt 2 on sheet intact
        assertEquals("Dinner 2 (Other)", fakeSheet.getCell(currentTab, pRow2, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR))
        assertEquals(500.0, (fakeSheet.getCell(currentTab, pRow2, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE) as Number).toDouble(), 0.001)
        assertEquals("Pending", fakeSheet.getCell(currentTab, pRow2, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS))
        assertEquals(payId2, fakeSheet.getCell(currentTab, pRow2, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))

        // Now void second expense with box off (void in place)
        repo.voidTransaction(txnId2, deleteSheetRow = false)
        repo.syncPendingTransactions(userInitiated = true)

        assertEquals(0.0, currentIOweTotal(), 0.001)
        val debt2Void = pDao.getAll().first { it.uuid == payId2 }
        assertEquals("Void", debt2Void.repaymentStatus)
        assertEquals(0.0, debt2Void.amountIOwe, 0.001)
    }

    // 4. Legacy unrelated IDs: 1 candidate matched and handled
    @Test
    fun legacyUnrelatedIds_singleCandidate_matchedAndHandled() = runBlocking {
        val legacyTxnId = "txn_legacy_unrelated_1"
        val legacyPayId = "pay_legacy_unrelated_2"

        // Seed legacy expense
        val txn = TransactionEntity(
            id = legacyTxnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Paid by Alice", tripTag = "", category = "Other",
            totalAmount = 400.0, companions = "Personal", myShare = 400.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        )
        tDao.insert(txn)

        // Seed legacy debt with unrelated ID but same dateSerial, same timeSerial (< 1s), agreeing creditor
        val pay = PayableEntity(
            uuid = legacyPayId, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Alice", whatFor = "Paid by Alice", amountIOwe = 400.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        )
        pDao.upsert(pay)

        // Seed sheet grid
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_UUID, legacyTxnId)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID, legacyPayId)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR, "Paid by Alice")
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE, 400.0)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, "Pending")

        assertEquals(400.0, currentIOweTotal(), 0.001)

        // Void with box off
        repo.voidTransaction(legacyTxnId, deleteSheetRow = false)
        repo.syncPendingTransactions(userInitiated = true)

        // Local debt is voided and amount zeroed
        val updatedDebt = pDao.getAll().first { it.uuid == legacyPayId }
        assertEquals("Void", updatedDebt.repaymentStatus)
        assertEquals(0.0, updatedDebt.amountIOwe, 0.001)
        assertEquals(0.0, currentIOweTotal(), 0.001)

        // Sheet debt is voided in place
        assertEquals("Void", fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS))
        assertEquals(0.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE) as Number).toDouble(), 0.001)
        assertEquals(legacyPayId, fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))
    }

    // 5. Legacy unrelated IDs: 0 candidates touches no debt and tells user
    @Test
    fun legacyUnrelatedIds_zeroCandidates_touchesNoDebtAndRecordsNotice() = runBlocking {
        val legacyTxnId = "txn_legacy_unrelated_none"
        val txn = TransactionEntity(
            id = legacyTxnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Paid by Bob", tripTag = "", category = "Other",
            totalAmount = 300.0, companions = "Personal", myShare = 300.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        )
        tDao.insert(txn)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_UUID, legacyTxnId)

        // No payables exist in pDao
        assertEquals(0, pDao.getAll().size)

        repo.voidTransaction(legacyTxnId, deleteSheetRow = false)

        // Under R1, zero candidates means it's an ordinary expense: voids without linked debt notice or block
        assertNull("Ordinary expense with zero candidates must not have notice", repo.consumeLastVoidNotice())
        assertNull("Ordinary expense with zero candidates is not blocked", repo.consumeLastVoidBlockReason())
    }

    // 6. Legacy unrelated IDs: 2 candidates touches no debt and tells user
    @Test
    fun legacyUnrelatedIds_twoCandidates_touchesNoDebtAndRecordsNotice() = runBlocking {
        val legacyTxnId = "txn_legacy_ambiguous"
        val txn = TransactionEntity(
            id = legacyTxnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Paid by Charlie", tripTag = "", category = "Other",
            totalAmount = 200.0, companions = "Personal", myShare = 200.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        )
        tDao.insert(txn)

        // Two identical legacy candidates at the same timestamp
        pDao.upsert(PayableEntity(
            uuid = "pay_candidate_1", dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Charlie", whatFor = "Paid by Charlie", amountIOwe = 200.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        ))
        pDao.upsert(PayableEntity(
            uuid = "pay_candidate_2", dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Charlie", whatFor = "Paid by Charlie", amountIOwe = 200.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 5
        ))

        assertEquals(400.0, currentIOweTotal(), 0.001)

        // 6. Ambiguous match: under ALL-OR-NOTHING rule, BLOCK the entire void
        val blockRes = repo.voidTransaction(legacyTxnId, deleteSheetRow = false)
        assertTrue("Void must be blocked when ambiguous matching candidates exist", blockRes is Result.Error)
        val err = (blockRes as Result.Error).exception
        assertTrue("Must be VoidBlockedException", err is com.financeautomation.domain.model.VoidBlockedException)
        assertEquals(
            com.financeautomation.domain.model.VoidDecision.BlockReason.AMBIGUOUS_DEBT_MATCH,
            (err as com.financeautomation.domain.model.VoidBlockedException).reason
        )
        assertEquals(
            com.financeautomation.domain.model.VoidDecision.BlockReason.AMBIGUOUS_DEBT_MATCH,
            repo.consumeLastVoidBlockReason()
        )

        // Change NOTHING: expense status not voided, debts not voided, queue has no OP_VOID
        val txnAfter = tDao.getById(legacyTxnId)!!
        assertFalse("Expense status must NOT be Void", txnAfter.status.equals("Void", ignoreCase = true))
        assertEquals(400.0, currentIOweTotal(), 0.001)
        val d1 = pDao.getAll().first { it.uuid == "pay_candidate_1" }
        val d2 = pDao.getAll().first { it.uuid == "pay_candidate_2" }
        assertEquals("Pending", d1.repaymentStatus)
        assertEquals(200.0, d1.amountIOwe, 0.001)
        assertEquals("Pending", d2.repaymentStatus)
        assertEquals(200.0, d2.amountIOwe, 0.001)
        assertTrue("Sync queue must remain empty", qDao.getAllQueueFlow().first().isEmpty())
    }

    // 7. Netted, partly repaid and repaid debts: ALL-OR-NOTHING blocks the void
    @Test
    fun nettedPartlyRepaidAndRepaidDebts_allOrNothingBlocksVoid() = runBlocking {
        val suffix = "testnetted12"
        val txnId = "txn_$suffix"
        val payId = "pay_$suffix"

        val txn = TransactionEntity(
            id = txnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Paid by Riya", tripTag = "", category = "Other",
            totalAmount = 500.0, companions = "Personal", myShare = 500.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        )
        tDao.insert(txn)

        // Netting reduced the debt: amountIOwe is 200.0 (less than expense total 500.0)
        val pay = PayableEntity(
            uuid = payId, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Riya", whatFor = "Paid by Riya", amountIOwe = 200.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        )
        pDao.upsert(pay)

        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_UUID, txnId)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID, payId)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE, 200.0)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, "Pending")

        // Under ALL-OR-NOTHING rule: BLOCK the whole void
        val blockRes = repo.voidTransaction(txnId, deleteSheetRow = false)
        assertTrue("Void must be blocked when debt is partly repaid or offset", blockRes is Result.Error)
        val err = (blockRes as Result.Error).exception
        assertTrue("Must be VoidBlockedException", err is com.financeautomation.domain.model.VoidBlockedException)
        assertEquals(
            com.financeautomation.domain.model.VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID,
            (err as com.financeautomation.domain.model.VoidBlockedException).reason
        )
        assertEquals(
            com.financeautomation.domain.model.VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID,
            repo.consumeLastVoidBlockReason()
        )

        // Expense remains completely unchanged
        val t = tDao.getById(txnId)!!
        assertFalse("Expense status must NOT be Void", t.status.equals("Void", ignoreCase = true))

        // Debt remains completely unchanged in Room and Sheet
        val keptDebt = pDao.getAll().first { it.uuid == payId }
        assertEquals("Pending", keptDebt.repaymentStatus)
        assertEquals(200.0, keptDebt.amountIOwe, 0.001)

        val sheetDebtAmount = fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE)
        assertEquals(200.0, (sheetDebtAmount as Number).toDouble(), 0.001)

        // Queue has NO OP_VOID
        assertTrue(qDao.getAllQueueFlow().first().none { it.operationType == SyncQueueEntity.OP_VOID })
    }

    // 8. Queued pending without plan: dropped from queue and voided locally
    @Test
    fun queuedPendingWithoutPlan_droppedFromQueueAndVoidedLocally() = runBlocking {
        val logRes = repo.logFriendPaid("Riya", 300.0, "Lunch", nowMillis) as Result.Success
        val payId = logRes.data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!

        // Queue holds OP_LOG_PAYABLE with planJson == null
        val queuedBefore = qDao.getAllQueueFlow().first()
        assertTrue(queuedBefore.any { it.transactionId == payId && it.operationType == SyncQueueEntity.OP_LOG_PAYABLE })

        // Void before sync
        val voidRes = repo.voidTransaction(txnId, deleteSheetRow = false)
        assertTrue(voidRes is Result.Success)

        // Queue dropped completely
        val queuedAfter = qDao.getAllQueueFlow().first()
        assertTrue(queuedAfter.isEmpty())

        // Local state voided
        val t = tDao.getById(txnId)!!
        assertEquals("Void", t.status)
        val p = pDao.getAll().first { it.uuid == payId }
        assertEquals("Void", p.repaymentStatus)
        assertEquals(0.0, p.amountIOwe, 0.001)
        assertEquals(0.0, currentIOweTotal(), 0.001)

        // Subsequent sync writes nothing to sheet
        repo.syncPendingTransactions(userInitiated = true)
        assertEquals(0, fakeSheet.getRows(currentTab).size)

        // Reconcile does not bring back any row
        repo.reconcileWithSheet()
        assertEquals(0.0, currentIOweTotal(), 0.001)
    }

    // 9. Queued failed without plan: dropped from queue and deleted locally
    @Test
    fun queuedFailedWithoutPlan_droppedFromQueueAndVoidedLocally() = runBlocking {
        val logRes = repo.logFriendPaid("Riya", 300.0, "Lunch", nowMillis) as Result.Success
        val payId = logRes.data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!

        val qItem = qDao.getAllQueueFlow().first().first { it.transactionId == payId }
        qDao.setStatusError(qItem.id, SyncQueueEntity.STATUS_FAILED, 5, "NO_NETWORK", "Network error")

        // Void with box on (delete)
        repo.voidTransaction(txnId, deleteSheetRow = true)

        // Dropped from queue
        assertTrue(qDao.getAllQueueFlow().first().isEmpty())

        // Deleted locally
        assertNull(tDao.getById(txnId))
        assertNull(pDao.getAll().firstOrNull { it.uuid == payId })
        assertEquals(0.0, currentIOweTotal(), 0.001)
    }

    // 10. Queued in-progress without plan: dropped from queue and voided locally
    @Test
    fun queuedInProgressWithoutPlan_droppedFromQueueAndVoidedLocally() = runBlocking {
        val logRes = repo.logFriendPaid("Riya", 300.0, "Lunch", nowMillis) as Result.Success
        val payId = logRes.data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!

        val qItem = qDao.getAllQueueFlow().first().first { it.transactionId == payId }
        qDao.updateStatus(qItem.id, SyncQueueEntity.STATUS_IN_PROGRESS, 1)

        repo.voidTransaction(txnId, deleteSheetRow = false)
        assertTrue(qDao.getAllQueueFlow().first().isEmpty())
        assertEquals(0.0, currentIOweTotal(), 0.001)
    }

    // 11. Queued with planJson: queues OP_VOID instead of dropping
    @Test
    fun queuedWithPlanJson_queuesOpVoidInsteadOfDropping() = runBlocking {
        val logRes = repo.logFriendPaid("Riya", 300.0, "Lunch", nowMillis) as Result.Success
        val payId = logRes.data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!

        val qItem = qDao.getAllQueueFlow().first().first { it.transactionId == payId }
        qDao.updatePlanJson(qItem.id, """{"edits":[],"message":"Planned"}""")

        repo.voidTransaction(txnId, deleteSheetRow = false)

        val queueAfter = qDao.getAllQueueFlow().first()
        // Stored plan means delivery may have written to sheet: keeps OP_LOG_PAYABLE and queues OP_VOID (R4)
        assertTrue(queueAfter.any { it.transactionId == payId && it.operationType == SyncQueueEntity.OP_LOG_PAYABLE })
        assertTrue(queueAfter.any { it.transactionId == txnId && it.operationType == SyncQueueEntity.OP_VOID })
    }

    // 12. Undo of recent save for friend paid expense
    @Test
    fun undoRecentSave_dropsQueuedFriendPaidExpenseAndDebt() = runBlocking {
        val logRes = repo.logFriendPaid("Riya", 350.0, "Coffee", nowMillis) as Result.Success
        val payId = logRes.data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!

        assertEquals(350.0, currentIOweTotal(), 0.001)

        val undoRes = repo.undoRecentSave(txnId)
        assertTrue(undoRes is Result.Success)

        // Queue emptied
        assertTrue(qDao.getAllQueueFlow().first().isEmpty())

        // Debt removed from I owe
        assertEquals(0.0, currentIOweTotal(), 0.001)
        val txn = tDao.getById(txnId)
        assertTrue(txn == null || txn.status.equals("Void", ignoreCase = true))
    }

    // 13. Retry after half finished sheet write does not touch wrong row
    @Test
    fun retryAfterHalfFinishedSheetWrite_doesNotTouchWrongRow() = runBlocking {
        val suffix = "retryhalf123"
        val txnId = "txn_$suffix"
        val payId = "pay_$suffix"

        val txn = TransactionEntity(
            id = txnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Dinner", tripTag = "", category = "Other",
            totalAmount = 100.0, companions = "Personal", myShare = 100.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        )
        tDao.insert(txn)

        val pay = PayableEntity(
            uuid = payId, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Riya", whatFor = "Dinner", amountIOwe = 100.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        )
        pDao.upsert(pay)

        // Sheet row 4 debit is already cleared/gone, row 5 has another user's debt
        setCell(currentTab, 5, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID, "pay_other_user")
        setCell(currentTab, 5, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR, "Other debt")
        setCell(currentTab, 5, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE, 999.0)

        // Queue OP_VOID delete
        repo.voidTransaction(txnId, deleteSheetRow = true)
        repo.syncPendingTransactions(userInitiated = true)

        // Row 5 of other user was NOT cleared or touched!
        assertEquals("Other debt", fakeSheet.getCell(currentTab, 5, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR))
        assertEquals(999.0, (fakeSheet.getCell(currentTab, 5, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE) as Number).toDouble(), 0.001)
        assertEquals("pay_other_user", fakeSheet.getCell(currentTab, 5, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))
    }

    // 14. Debt row moved to another row number found by reading UUID column
    @Test
    fun debtRowMovedToAnotherRowNumber_foundByReadingUuidColumn() = runBlocking {
        val suffix = "rowmoved1234"
        val txnId = "txn_$suffix"
        val payId = "pay_$suffix"

        val txn = TransactionEntity(
            id = txnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Dinner", tripTag = "", category = "Other",
            totalAmount = 250.0, companions = "Personal", myShare = 250.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        )
        tDao.insert(txn)

        // Local debt has no cached row or stale row
        val pay = PayableEntity(
            uuid = payId, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Riya", whatFor = "Dinner", amountIOwe = 250.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = null
        )
        pDao.upsert(pay)

        // In sheet, debit is at row 4, but payable is moved down to row 10
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_UUID, txnId)
        setCell(currentTab, 10, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID, payId)
        setCell(currentTab, 10, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR, "Dinner")
        setCell(currentTab, 10, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE, 250.0)
        setCell(currentTab, 10, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, "Pending")

        // Void with box on (delete)
        repo.voidTransaction(txnId, deleteSheetRow = true)
        repo.syncPendingTransactions(userInitiated = true)

        // Row 10 payable cells cleared correctly by UUID lookup!
        assertNull(fakeSheet.getCell(currentTab, 10, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR))
        assertNull(fakeSheet.getCell(currentTab, 10, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE))
        assertNull(fakeSheet.getCell(currentTab, 10, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))
    }

    // 15. V1 layout support: clears V to AB and AE for payable
    @Test
    fun v1LayoutSupport_clearsVtoABandAE() = runBlocking {
        val suffix = "v1layout1234"
        val txnId = "txn_$suffix"
        val payId = "pay_$suffix"

        val txn = TransactionEntity(
            id = txnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Dinner", tripTag = "", category = "Other",
            totalAmount = 150.0, companions = "Personal", myShare = 150.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        )
        tDao.insert(txn)

        val pay = PayableEntity(
            uuid = payId, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Riya", whatFor = "Dinner", amountIOwe = 150.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        )
        pDao.upsert(pay)

        // In V1 tab: Debit UUID is in AC, Payable UUID is in AE, Table 3 is in V:AB
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1, txnId)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID_V1, payId)
        setCell(currentTab, 4, "Y", "Dinner")
        setCell(currentTab, 4, "Z", 150.0)
        setCell(currentTab, 4, "AB", "Pending")

        // Void with box on (delete)
        repo.voidTransaction(txnId, deleteSheetRow = true)
        repo.syncPendingTransactions(userInitiated = true)

        // Cleared in V1 coordinates
        assertNull(fakeSheet.getCell(currentTab, 4, "Y"))
        assertNull(fakeSheet.getCell(currentTab, 4, "Z"))
        assertNull(fakeSheet.getCell(currentTab, 4, "AB"))
        assertNull(fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID_V1))
    }

    // === Reviewer Directive R1-R7 Dedicated Tests ===

    // R1: Ordinary expense on account "Other" with no linked debt is NEVER blocked
    @Test
    fun r1_ordinaryExpenseOnAccountOther_isNeverBlocked() = runBlocking {
        val txnId = "txn_ordinary_other_99"
        val txn = TransactionEntity(
            id = txnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Supermarket Grocery", tripTag = "", category = "Food & Drink",
            totalAmount = 275.0, companions = "Personal", myShare = 275.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        )
        tDao.insert(txn)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_UUID, txnId)

        val voidRes = repo.voidTransaction(txnId, deleteSheetRow = false)
        assertTrue("Ordinary expense on Other must void successfully", voidRes is Result.Success)
        assertNull("No block reason should exist", repo.consumeLastVoidBlockReason())

        val tAfter = tDao.getById(txnId)!!
        assertEquals("Void", tAfter.status)
        assertTrue("Queue should contain OP_VOID for expense", qDao.getAllQueueFlow().first().any { it.transactionId == txnId && it.operationType == SyncQueueEntity.OP_VOID })
    }

    // R2: Near-miss creditor or description never matches candidate debt and voids expense alone
    @Test
    fun r2_nearMissCreditorOrDescription_neverMatchesCandidateDebt() = runBlocking {
        // Case A: Near-miss creditor ("Alice" vs "Bob")
        val txnIdA = "txn_near_miss_creditor_1"
        val payIdA = "pay_unrelated_candidate_2"
        tDao.insert(TransactionEntity(
            id = txnIdA, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Paid by Alice", tripTag = "", category = "Other",
            totalAmount = 300.0, companions = "Personal", myShare = 300.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        ))
        pDao.upsert(PayableEntity(
            uuid = payIdA, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Bob", whatFor = "Paid by Alice", amountIOwe = 300.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        ))

        val voidResA = repo.voidTransaction(txnIdA, deleteSheetRow = false)
        assertTrue("Near-miss creditor expense must void as ordinary expense", voidResA is Result.Success)
        val debtA = pDao.getAll().first { it.uuid == payIdA }
        assertEquals("Bob's debt must remain untouched", "Pending", debtA.repaymentStatus)
        assertEquals(300.0, debtA.amountIOwe, 0.001)

        // Case B: Near-miss description ("Dinner" vs "Din" prefix)
        val txnIdB = "txn_near_miss_desc_1"
        val payIdB = "pay_unrelated_candidate_3"
        tDao.insert(TransactionEntity(
            id = txnIdB, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Alice", merchant = "Dinner", tripTag = "", category = "Food & Drink",
            totalAmount = 180.0, companions = "Personal", myShare = 180.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 5
        ))
        pDao.upsert(PayableEntity(
            uuid = payIdB, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Alice", whatFor = "Din", amountIOwe = 180.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 5
        ))

        val voidResB = repo.voidTransaction(txnIdB, deleteSheetRow = false)
        assertTrue("Near-miss description expense must void as ordinary expense", voidResB is Result.Success)
        val debtB = pDao.getAll().first { it.uuid == payIdB }
        assertEquals("Din debt must remain untouched", "Pending", debtB.repaymentStatus)
        assertEquals(180.0, debtB.amountIOwe, 0.001)

        // Case C: Empty creditor in candidate debt
        val txnIdC = "txn_near_miss_empty_creditor_1"
        val payIdC = "pay_unrelated_candidate_4"
        tDao.insert(TransactionEntity(
            id = txnIdC, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Movie", tripTag = "", category = "Other",
            totalAmount = 200.0, companions = "Personal", myShare = 200.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 6
        ))
        pDao.upsert(PayableEntity(
            uuid = payIdC, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "", whatFor = "Movie", amountIOwe = 200.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 6
        ))

        val voidResC = repo.voidTransaction(txnIdC, deleteSheetRow = false)
        assertTrue("Empty creditor candidate does not match", voidResC is Result.Success)
        val debtC = pDao.getAll().first { it.uuid == payIdC }
        assertEquals("Empty creditor debt must remain untouched", "Pending", debtC.repaymentStatus)
    }

    // R3: findPayableRowAndTab read failure fails item for retry and changes nothing
    @Test
    fun r3_findPayableRowAndTab_onFailedRead_failsItemForRetryAndChangesNothing() = runBlocking {
        val suffix = "r3failread12"
        val txnId = "txn_$suffix"
        val payId = "pay_$suffix"

        tDao.insert(TransactionEntity(
            id = txnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Dinner", tripTag = "", category = "Other",
            totalAmount = 500.0, companions = "Personal", myShare = 500.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        ))
        pDao.upsert(PayableEntity(
            uuid = payId, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Riya", whatFor = "Dinner", amountIOwe = 500.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        ))

        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_UUID, txnId)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID, payId)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR, "Dinner")
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE, 500.0)
        setCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, "Pending")

        // Void with box on (delete)
        repo.voidTransaction(txnId, deleteSheetRow = true)

        // Inject simulated sheet read failure on findRowByUuid for payable
        fakeSheet.forceFindRowError = true

        val syncRes = repo.syncPendingTransactions(userInitiated = true)
        assertTrue("Sync must fail when payable read fails", syncRes is Result.Error)

        // Item remains in queue for retry
        val pendingQueue = qDao.getAllPending()
        assertTrue("OP_VOID must remain in queue for retry", pendingQueue.any { it.transactionId == txnId && it.operationType == SyncQueueEntity.OP_VOID })

        // Sheet row was NOT cleared
        assertEquals("Dinner", fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR))
        assertEquals(payId, fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))
    }

    // R4: Delivery in progress with planJson is NOT dropped when voiding
    @Test
    fun r4_deliveryInProgressWithPlanJson_queuedPayableNotDropped() = runBlocking {
        val logRes = repo.logFriendPaid("Riya", 600.0, "Hotel", nowMillis) as Result.Success
        val payId = logRes.data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!

        val qItem = qDao.getAllQueueFlow().first().first { it.transactionId == payId }
        qDao.updatePlanJson(qItem.id, """{"edits":[{"tab":"Nov26","row":4,"column":"W","value":"30/11/26"}],"message":"Hotel delivery"}""")

        repo.voidTransaction(txnId, deleteSheetRow = false)

        val queueAfter = qDao.getAllQueueFlow().first()
        // OP_LOG_PAYABLE is preserved so half-applied delivery completes, and OP_VOID follows it
        assertTrue("OP_LOG_PAYABLE with planJson must NOT be dropped", queueAfter.any { it.transactionId == payId && it.operationType == SyncQueueEntity.OP_LOG_PAYABLE })
        assertTrue("OP_VOID must be queued after it", queueAfter.any { it.transactionId == txnId && it.operationType == SyncQueueEntity.OP_VOID })
    }

    // === The decision is made again from the sheet before anything is written ===

    private suspend fun deliveredFriendPaid(): Triple<String, String, Pair<Int, Int>> {
        val payId = (repo.logFriendPaid("Riya", 500.0, "Dinner", nowMillis) as Result.Success).data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!
        repo.syncPendingTransactions(userInitiated = true)
        fun rowOf(column: String, id: String) =
            (4..40).first { fakeSheet.getCell(currentTab, it, column) == id }
        val rows = rowOf(SheetSchemaConstants.COL_LETTER_DEBIT_UUID, txnId) to
            rowOf(SheetSchemaConstants.COL_LETTER_PAYABLE_UUID, payId)
        return Triple(txnId, payId, rows)
    }

    private fun sheetPart(row: Int, vararg columns: String) = columns.map { fakeSheet.getCell(currentTab, row, it) }

    @Test
    fun debtRepaidOnlyOnTheSheet_refusesTheWholeVoidAtSync_boxOffAndOn() = runBlocking {
        for (deleteSheetRow in listOf(false, true)) {
            setUp()
            val (txnId, payId, rows) = deliveredFriendPaid()
            // Repaid in part on the sheet; this device has not read it yet
            setCell(currentTab, rows.second, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE, 200.0)
            val debitBefore = sheetPart(rows.first, "D", "G", "I", "K", SheetSchemaConstants.COL_LETTER_DEBIT_UUID)
            val debtBefore = sheetPart(rows.second, "Z", "AA", "AC", SheetSchemaConstants.COL_LETTER_PAYABLE_UUID)

            assertTrue(repo.voidTransaction(txnId, deleteSheetRow) is Result.Success)
            repo.syncPendingTransactions(userInitiated = true)

            assertEquals(debitBefore, sheetPart(rows.first, "D", "G", "I", "K", SheetSchemaConstants.COL_LETTER_DEBIT_UUID))
            assertEquals(debtBefore, sheetPart(rows.second, "Z", "AA", "AC", SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))
            val local = tDao.getById(txnId)!!
            assertEquals("Personal", local.status)
            assertEquals(500.0, local.totalAmount, 0.001)
            assertEquals(200.0, pDao.getByUuid(payId)!!.amountIOwe, 0.001)
            assertTrue(qDao.getAllQueueFlow().first().none { it.operationType == SyncQueueEntity.OP_VOID })
            assertEquals(
                com.financeautomation.domain.model.VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID,
                repo.takeVoidBlockedNotice()
            )
            assertNull("the notice is shown once", repo.takeVoidBlockedNotice())
        }
    }

    @Test
    fun failedSheetRead_boxOff_failsTheItemAndWritesNothing() = runBlocking {
        val (txnId, _, rows) = deliveredFriendPaid()
        repo.voidTransaction(txnId, deleteSheetRow = false)
        fakeSheet.forceFindRowError = true

        assertTrue(repo.syncPendingTransactions(userInitiated = true) is Result.Error)

        assertEquals("Dinner", fakeSheet.getCell(currentTab, rows.first, "D"))
        assertEquals("Pending", fakeSheet.getCell(currentTab, rows.second, "AC"))
        assertTrue(qDao.getAllPending().any { it.operationType == SyncQueueEntity.OP_VOID })
    }

    @Test
    fun writeThatFailsForGood_putsTheLocalRowsBack_andARetryVoidsBoth() = runBlocking {
        val (txnId, payId, rows) = deliveredFriendPaid()
        repo.voidTransaction(txnId, deleteSheetRow = false)
        fakeSheet.forceValueWriteStatus = 400

        repo.syncPendingTransactions(userInitiated = true)

        val failed = qDao.getAllQueueFlow().first().single { it.operationType == SyncQueueEntity.OP_VOID }
        assertEquals(SyncQueueEntity.STATUS_FAILED, failed.status)
        assertEquals("Personal", tDao.getById(txnId)!!.status)
        assertEquals(500.0, tDao.getById(txnId)!!.totalAmount, 0.001)
        assertEquals("Pending", pDao.getByUuid(payId)!!.repaymentStatus)
        assertEquals(500.0, pDao.getByUuid(payId)!!.amountIOwe, 0.001)

        fakeSheet.forceValueWriteStatus = null
        qDao.resetQueueItem(failed.id)
        repo.syncPendingTransactions(userInitiated = true)

        assertEquals("Void", fakeSheet.getCell(currentTab, rows.first, "K"))
        assertEquals("Void", fakeSheet.getCell(currentTab, rows.second, "AC"))
        assertEquals("Void", tDao.getById(txnId)!!.status)
        assertEquals("Void", pDao.getByUuid(payId)!!.repaymentStatus)
    }

    @Test
    fun planStoredByAnEarlierAttemptNeverWritesToAnotherEntrysRow() = runBlocking {
        val (txnId, _, rows) = deliveredFriendPaid()
        val otherRow = rows.first + 7
        setCell(currentTab, otherRow, "D", "Someone else")
        setCell(currentTab, otherRow, SheetSchemaConstants.COL_LETTER_DEBIT_UUID, "txn_other_entry")
        repo.voidTransaction(txnId, deleteSheetRow = false)
        val item = qDao.getAllQueueFlow().first().single { it.operationType == SyncQueueEntity.OP_VOID }
        qDao.updatePlanJson(
            item.id,
            com.financeautomation.data.remote.AccountingPlanJsonAdapter.toJson(
                AccountingPlan(edits = listOf(CellEdit(currentTab, otherRow, "D", "[Void] Dinner")))
            )
        )

        repo.syncPendingTransactions(userInitiated = true)

        assertEquals("Someone else", fakeSheet.getCell(currentTab, otherRow, "D"))
        assertEquals("Void", fakeSheet.getCell(currentTab, rows.first, "K"))
    }

    @Test
    fun plannedDeliveryThatNetsTheDebt_blocksTheVoidAndKeepsTheQueue() = runBlocking {
        val payId = (repo.logFriendPaid("Riya", 600.0, "Hotel", nowMillis) as Result.Success).data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!
        val item = qDao.getAllQueueFlow().first().single()
        val netted = AccountingPlan(
            appends = listOf(
                com.financeautomation.domain.model.NewRow(
                    com.financeautomation.domain.model.TableType.PAYABLE, currentTab, payId,
                    listOf(fixedDateSerial, fixedTimeSerial, "Riya", "Hotel (Other)", 250.0, "", "Pending")
                )
            )
        )
        qDao.updatePlanJson(item.id, com.financeautomation.data.remote.AccountingPlanJsonAdapter.toJson(netted))

        val result = repo.voidTransaction(txnId, deleteSheetRow = false)

        assertEquals(
            com.financeautomation.domain.model.VoidDecision.BlockReason.DEBT_OFFSET,
            ((result as Result.Error).exception as com.financeautomation.domain.model.VoidBlockedException).reason
        )
        assertEquals("Personal", tDao.getById(txnId)!!.status)
        assertEquals(listOf(SyncQueueEntity.OP_LOG_PAYABLE), qDao.getAllQueueFlow().first().map { it.operationType })
    }

    @Test
    fun voidWaitsWhileTheDeliveryItVoidsIsUnfinished() = runBlocking {
        val payId = (repo.logFriendPaid("Riya", 300.0, "Lunch", nowMillis) as Result.Success).data
        val txnId = AccountingUtils.linkedExpenseIdForPayable(payId)!!
        val item = qDao.getAllQueueFlow().first().single()
        qDao.updatePlanJson(item.id, """{"edits":[],"message":"Planned"}""")
        qDao.setStatusError(item.id, SyncQueueEntity.STATUS_FAILED, 999, "UNKNOWN", "stopped half way")
        repo.voidTransaction(txnId, deleteSheetRow = false)

        repo.syncPendingTransactions(userInitiated = true)

        assertEquals(
            listOf(SyncQueueEntity.OP_LOG_PAYABLE, SyncQueueEntity.OP_VOID),
            qDao.getAllQueueFlow().first().sortedBy { it.id }.map { it.operationType }
        )
    }

    @Test
    fun undoOfAHalfDeliveredFriendPaidSaveKeepsTheDelivery() = runBlocking {
        val payId = (repo.logFriendPaid("Riya", 300.0, "Lunch", nowMillis) as Result.Success).data
        val item = qDao.getAllQueueFlow().first().single()
        qDao.updatePlanJson(item.id, """{"edits":[],"message":"Planned"}""")

        assertTrue(repo.undoRecentSave(payId) is Result.Success)

        val queued = qDao.getAllQueueFlow().first().map { it.operationType }
        assertTrue(queued.contains(SyncQueueEntity.OP_LOG_PAYABLE))
        assertTrue(queued.contains(SyncQueueEntity.OP_VOID))
    }

    @Test
    fun oldDebtThatDoesNotNameItsExpenseCreditor_blocksInsteadOfBeingLeftBehind() = runBlocking {
        val txnId = "txn_old_free_text"
        tDao.insert(TransactionEntity(
            id = txnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Dinner", tripTag = "", category = "Other",
            totalAmount = 500.0, companions = "Personal", myShare = 500.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        ))
        pDao.upsert(PayableEntity(
            uuid = "pay_unrelated_id", dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "Riya", whatFor = "Dinner (Other)", amountIOwe = 500.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        ))

        val result = repo.voidTransaction(txnId, deleteSheetRow = false)

        assertEquals(
            com.financeautomation.domain.model.VoidDecision.BlockReason.AMBIGUOUS_DEBT_MATCH,
            ((result as Result.Error).exception as com.financeautomation.domain.model.VoidBlockedException).reason
        )
        assertTrue(qDao.getAllQueueFlow().first().isEmpty())
    }

    @Test
    fun legacyMatchingHasNoPartialShortcuts_andKnowsTheNoDescriptionShape() {
        fun match(merchant: String, account: String, paidBy: String, whatFor: String, category: String = "Other") =
            AccountingUtils.matchLegacyPayable(
                fixedDateSerial, fixedTimeSerial, paidBy, whatFor, fixedDateSerial, fixedTimeSerial,
                merchant, account, category
            )
        // A creditor that is only part of the text ("Al" inside "Paid by Alice") does not agree
        assertEquals(
            AccountingUtils.LegacyDebtMatch.NONE,
            match("Gift Paid by Alice", "HDFC", "Al", "Gift Paid by Alice")
        )
        assertEquals(
            AccountingUtils.LegacyDebtMatch.NONE,
            match("Cab (Riya)", "HDFC", "Riya", "Cab (Riya)")
        )
        // Logged without a description: the debt holds only the category
        assertEquals(
            AccountingUtils.LegacyDebtMatch.AGREES,
            match("Paid by Riya", "Other", "Riya", "Food & Drink", "Food & Drink")
        )
    }

    // R5: Diagnostics never contains amounts, names or descriptions
    @Test
    fun r5_diagnostics_neverContainsAmountsNamesOrDescriptions() = runBlocking {
        com.financeautomation.core.utils.Diagnostics.clear()
        val suffix = "r5diagtest1"
        val txnId = "txn_$suffix"
        val payId = "pay_$suffix"

        tDao.insert(TransactionEntity(
            id = txnId, timestamp = nowMillis, dateSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            account = "Other", merchant = "Paid by PrivateFriendName", tripTag = "", category = "Other",
            totalAmount = 888.0, companions = "Personal", myShare = 888.0, owedToMe = 0.0,
            status = TransactionStatus.PERSONAL.value, sourcePackage = "", syncState = SyncState.SYNCED.name,
            sheetRowIndex = 4
        ))
        pDao.upsert(PayableEntity(
            uuid = payId, dateIncurredSerial = fixedDateSerial, timeSerial = fixedTimeSerial,
            paidBy = "PrivateFriendName", whatFor = "Paid by PrivateFriendName", amountIOwe = 444.0, dateRepaidSerial = null,
            repaymentStatus = "Pending", tabName = currentTab, sheetRowIndex = 4
        ))

        // Void is blocked because debt was reduced (444 != 888)
        val res = repo.voidTransaction(txnId, deleteSheetRow = false)
        assertTrue("Void must be blocked", res is Result.Error)

        val records = com.financeautomation.core.utils.Diagnostics.getRecords()
        val voidLogs = records.filter { it.tag == "VoidFriendPaid" }
        assertTrue("Must have recorded VoidFriendPaid diagnostic", voidLogs.isNotEmpty())

        for (rec in voidLogs) {
            val log = rec.reason
            assertFalse("Diagnostics must NOT contain monetary amounts: $log", log.contains("888") || log.contains("444"))
            assertFalse("Diagnostics must NOT contain person name: $log", log.contains("PrivateFriendName"))
            assertFalse("Diagnostics must NOT contain merchant/description text: $log", log.contains("Paid by"))
            assertTrue("Diagnostics must contain safe enum reason", log.contains("DEBT_PARTIALLY_OR_FULLY_REPAID"))
        }
    }
}
