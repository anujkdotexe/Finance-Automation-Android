package com.financeautomation.data.repository

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.model.VoidBlockedException
import com.financeautomation.domain.model.VoidDecision
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.money.FakeSheetApplier
import com.financeautomation.testutil.InMemoryInflowDao
import com.financeautomation.testutil.InMemoryLineageDao
import com.financeautomation.testutil.InMemoryPayableDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * Voiding an entry whose debt was carried into a later month. The debt is logged, delivered and carried through the
 * production repository, executor and Sheets client over an in-memory workbook, with an injected clock.
 */
class CarriedDebtVoidTest {

    private val now = Instant.parse("2026-11-22T10:00:00Z").toEpochMilli()
    private val october = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli()
    private val sheet = FakeSheetApplier(mutableListOf("Oct26", "Nov26", "Config", "Subscriptions", "Trips"))
    private val tDao = InMemoryTransactionDao()
    private val qDao = InMemoryQueueDao()
    private val pDao = InMemoryPayableDao()
    private val repo: TransactionRepositoryImpl

    init {
        val preferences = mutableMapOf<String, String?>()

        @Suppress("UNCHECKED_CAST")
        val prefs: PreferencesRepository = Proxy.newProxyInstance(
            PreferencesRepository::class.java.classLoader,
            arrayOf(PreferencesRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getPreference" -> preferences[args!![0] as String]
                "setPreference" -> { preferences[args!![0] as String] = args[1] as String?; null }
                "getDefaultAccount" -> flowOf("Cash")
                "getDefaultAccountSync" -> "Cash"
                "getGoogleAccountEmailSync" -> "test@example.com"
                "getSpreadsheetIdSync", "getSpreadsheetIdForYearSync" -> "test_sheet_id"
                else -> null
            }
        } as PreferencesRepository

        val sheets = sheet.client()
        val lDao = InMemoryLineageDao()
        repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            inflowDao = InMemoryInflowDao(),
            lineageDao = lDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = AccountingSyncExecutor(
                googleSheetsClient = sheets,
                googleDriveClient = GoogleDriveClient(OkHttpClient(), null),
                syncQueueDao = qDao,
                lineageDao = lDao,
                transactionDao = tDao,
                payableDao = pDao,
                clock = { now }
            ),
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now }
        )
    }

    private suspend fun syncAll() {
        // The first run delivers and queues the catch up carry, the second one runs it
        repeat(2) { repo.syncPendingTransactions(userInitiated = true) }
    }

    private fun cell(tab: String, row: Int, column: String): Any? = sheet.getCell(tab, row, column)

    private fun rowWith(tab: String, column: String, value: String): Int =
        (4..40).first { cell(tab, it, column) == value }

    private fun copyRow(tab: String, column: String): Int =
        (4..40).first { cell(tab, it, column)?.toString()?.startsWith("[Carried from Oct26]") == true }

    private fun snapshotOfSheet(): Map<String, String> = listOf("Oct26", "Nov26").associateWith { tab ->
        (4..12).joinToString("\n") { row -> sheet.getRow(tab, row).toString() }
    }

    private data class Carried(val expenseId: String, val originalRow: Int, val copyRow: Int)

    private suspend fun carriedFriendPaid(): Carried {
        val payId = (repo.logFriendPaid("Riya", 500.0, "Dinner", october) as Result.Success).data
        syncAll()
        return Carried(
            AccountingUtils.linkedExpenseIdForPayable(payId)!!,
            rowWith("Oct26", SheetSchemaConstants.COL_LETTER_PAYABLE_UUID, payId),
            copyRow("Nov26", "Z")
        )
    }

    private suspend fun carriedSplit(): Carried {
        val expense = Transaction(
            id = "txn_split_oct", timestamp = october, dateSerial = DateSerialUtils.toDateSerial(october),
            timeSerial = DateSerialUtils.toTimeSerial(october), account = "Cash", merchant = "Villa",
            category = "Travel", totalAmount = 1000.0, companions = "Asha", myShare = 400.0, owedToMe = 600.0,
            status = TransactionStatus.PENDING, reviewState = ReviewState.CONFIRMED, syncState = SyncState.PENDING
        )
        assertTrue(repo.insertTransaction(expense) is Result.Success)
        syncAll()
        return Carried(
            expense.id,
            rowWith("Oct26", SheetSchemaConstants.COL_LETTER_DEBIT_UUID, expense.id),
            copyRow("Nov26", "D")
        )
    }

    private suspend fun iOwe(): Double = pDao.getPending().sumOf { it.amountIOwe }

    private suspend fun othersOwe(): Double = tDao.getTransactionsFlow().first()
        .filter { it.status == TransactionStatus.PENDING.value }.sumOf { it.owedToMe }

    private suspend fun valueWritesWhile(block: suspend () -> Unit): List<String> {
        val before = sheet.calls.size
        block()
        return sheet.calls.drop(before)
            .filter { it.method == "POST" && (it.url.contains("values:batchUpdate") || it.url.contains("values:batchClear")) }
            .map { it.body }
    }

    @Test
    fun anUntouchedCarriedDebtIsVoidedWithItsExpenseInOneWrite() = runBlocking {
        val c = carriedFriendPaid()
        assertEquals(500.0, iOwe(), 0.001)

        assertTrue(repo.voidTransaction(c.expenseId, deleteSheetRow = false) is Result.Success)
        val writes = valueWritesWhile { repo.syncPendingTransactions(userInitiated = true) }

        assertEquals("one write carries every change", 1, writes.size)
        val expenseRow = rowWith("Oct26", SheetSchemaConstants.COL_LETTER_DEBIT_UUID, c.expenseId)
        assertEquals("Void", cell("Oct26", expenseRow, "K"))
        assertEquals("Void", cell("Oct26", c.originalRow, "AC"))
        assertEquals("Void", cell("Nov26", c.copyRow, "AC"))
        assertEquals(0.0, cell("Nov26", c.copyRow, "AA").toString().toDouble(), 0.001)
        assertEquals(0.0, iOwe(), 0.001)
        assertNull(repo.takeVoidBlockedNotice())
    }

    @Test
    fun theDeleteBoxClearsEveryRowOfAnUntouchedCarriedDebt() = runBlocking {
        val c = carriedFriendPaid()

        repo.voidTransaction(c.expenseId, deleteSheetRow = true)
        repo.syncPendingTransactions(userInitiated = true)

        listOf("Oct26" to c.originalRow, "Nov26" to c.copyRow).forEach { (tab, row) ->
            assertNull(cell(tab, row, "Z"))
            assertNull(cell(tab, row, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID))
        }
        assertNull(tDao.getById(c.expenseId))
        assertEquals(0.0, iOwe(), 0.001)
    }

    private suspend fun assertRefused(expenseId: String, reason: VoidDecision.BlockReason, sheetBefore: Map<String, String>) {
        assertEquals("the sheet is not changed", sheetBefore, snapshotOfSheet())
        assertEquals(reason, repo.takeVoidBlockedNotice())
        assertTrue(tDao.getById(expenseId)!!.status != TransactionStatus.VOID.value)
        assertTrue(qDao.getAllQueueFlow().first().none { it.operationType == SyncQueueEntity.OP_VOID })
    }

    @Test
    fun aCarriedCopyRepaidInPartOnTheSheetRefusesTheWholeVoid() = runBlocking {
        val c = carriedFriendPaid()
        sheet.setCell("Nov26", c.copyRow, SheetSchemaConstants.columnIndex("AA") + 1, 200.0)
        sheet.setCell("Nov26", c.copyRow, SheetSchemaConstants.columnIndex("AC") + 1, "Partial")
        val before = snapshotOfSheet()

        repo.voidTransaction(c.expenseId, deleteSheetRow = false)
        repo.syncPendingTransactions(userInitiated = true)

        assertRefused(c.expenseId, VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID, before)
    }

    @Test
    fun aCarriedDebtWhoseCopyCannotBeFoundIsRefused() = runBlocking {
        val c = carriedFriendPaid()
        // As when the debt went on into another year's workbook: no copy follows the carried row here
        sheet.setCell("Nov26", c.copyRow, SheetSchemaConstants.columnIndex("Z") + 1, "Something unrelated")
        val before = snapshotOfSheet()

        repo.voidTransaction(c.expenseId, deleteSheetRow = true)
        repo.syncPendingTransactions(userInitiated = true)

        assertRefused(c.expenseId, VoidDecision.BlockReason.DEBT_CARRIED_FORWARD, before)
    }

    @Test
    fun aRepaymentThisDeviceRecordedOnTheCopyBlocksAtOnce() = runBlocking {
        val c = carriedFriendPaid()
        assertTrue(repo.logRepayment("Riya", 100.0, null, now) is Result.Success)

        val result = repo.voidTransaction(c.expenseId, deleteSheetRow = false)

        assertEquals(
            VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID,
            ((result as Result.Error).exception as VoidBlockedException).reason
        )
    }

    @Test
    fun aFailedReadOfTheLaterTabsChangesNothingAndIsRetried() = runBlocking {
        val c = carriedFriendPaid()
        repo.voidTransaction(c.expenseId, deleteSheetRow = false)
        val before = snapshotOfSheet()
        sheet.forceFindRowError = true

        assertTrue(repo.syncPendingTransactions(userInitiated = true) is Result.Error)

        assertEquals(before, snapshotOfSheet())
        assertTrue(qDao.getAllPending().any { it.operationType == SyncQueueEntity.OP_VOID })
    }

    @Test
    fun anUntouchedCarriedReceivableIsVoidedOnBothRows() = runBlocking {
        val c = carriedSplit()

        repo.voidTransaction(c.expenseId, deleteSheetRow = false)
        repo.syncPendingTransactions(userInitiated = true)

        assertEquals("Void", cell("Oct26", c.originalRow, "K"))
        assertEquals("Void", cell("Nov26", c.copyRow, "K"))
        assertEquals(0.0, cell("Nov26", c.copyRow, "J").toString().toDouble(), 0.001)
        assertEquals(0.0, othersOwe(), 0.001)
    }

    @Test
    fun aCarriedReceivablePartlySettledOnTheCopyRefusesTheWholeVoid() = runBlocking {
        val c = carriedSplit()
        sheet.setCell("Nov26", c.copyRow, SheetSchemaConstants.columnIndex("J") + 1, 250.0)
        val before = snapshotOfSheet()

        repo.voidTransaction(c.expenseId, deleteSheetRow = false)
        repo.syncPendingTransactions(userInitiated = true)

        assertRefused(c.expenseId, VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID, before)
    }
}
