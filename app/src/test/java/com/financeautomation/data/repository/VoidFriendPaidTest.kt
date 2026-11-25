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

        val notice = repo.consumeLastVoidNotice()
        assertEquals("Linked debt was left unchanged.", notice)
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

        repo.voidTransaction(legacyTxnId, deleteSheetRow = false)

        // Ambiguous match: touch NO debt
        assertEquals("Linked debt was left unchanged.", repo.consumeLastVoidNotice())
        assertEquals(400.0, currentIOweTotal(), 0.001)
        val d1 = pDao.getAll().first { it.uuid == "pay_candidate_1" }
        val d2 = pDao.getAll().first { it.uuid == "pay_candidate_2" }
        assertEquals("Pending", d1.repaymentStatus)
        assertEquals(200.0, d1.amountIOwe, 0.001)
        assertEquals("Pending", d2.repaymentStatus)
        assertEquals(200.0, d2.amountIOwe, 0.001)
    }

    // 7. Netted, partly repaid and repaid debts unchanged with notice
    @Test
    fun nettedPartlyRepaidAndRepaidDebts_remainUnchangedWithNotice() = runBlocking {
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

        // Voiding such an expense must still work for the expense and tell user linked debt was left unchanged
        repo.voidTransaction(txnId, deleteSheetRow = false)
        repo.syncPendingTransactions(userInitiated = true)

        assertEquals("Linked debt was left unchanged.", repo.consumeLastVoidNotice())

        // Debt remains completely unchanged
        val keptDebt = pDao.getAll().first { it.uuid == payId }
        assertEquals("Pending", keptDebt.repaymentStatus)
        assertEquals(200.0, keptDebt.amountIOwe, 0.001)

        val sheetDebtAmount = fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE)
        assertEquals(200.0, (sheetDebtAmount as Number).toDouble(), 0.001)
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
        // Stored plan means delivery may have written to sheet: queues OP_VOID
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
}
