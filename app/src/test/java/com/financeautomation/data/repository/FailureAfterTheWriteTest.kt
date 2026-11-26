package com.financeautomation.data.repository

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * Something fails after the amounts have already reached the sheet: the date formatting, or the local database.
 * Real repository, executor and Sheets client over an in-memory workbook with an injected clock. Invented data.
 */
class FailureAfterTheWriteTest {

    /** The in-memory table, able to fail one chosen kind of call once. */
    private class BreakableTransactionDao(val inner: InMemoryTransactionDao) : TransactionDao by inner {
        var breakNextUpdate = false
        var breakNextSyncState = false
        var breaks = 0

        override suspend fun update(transaction: TransactionEntity) {
            if (breakNextUpdate) {
                breakNextUpdate = false
                breaks++
                throw IllegalStateException("database is locked")
            }
            inner.update(transaction)
        }

        override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {
            if (breakNextSyncState) {
                breakNextSyncState = false
                breaks++
                throw IllegalStateException("database is locked")
            }
            inner.updateSyncState(id, syncState, sheetRowIndex)
        }
    }

    private val now = Instant.parse("2026-11-22T10:00:00Z").toEpochMilli()
    private val then = now - 3_600_000L
    private val sheet = FakeSheetApplier(mutableListOf("Nov26", "Config", "Subscriptions", "Trips"))
    private val tDao = BreakableTransactionDao(InMemoryTransactionDao())
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

    private fun column(letters: String): Int = SheetSchemaConstants.columnIndex(letters) + 1

    private fun cell(row: Int, letters: String): String = sheet.getCell("Nov26", row, letters)?.toString().orEmpty()

    private fun rows(): List<Int> = sheet.grid.getValue("Nov26").keys.filter { it >= 4 }.sorted()

    private suspend fun queue(): List<SyncQueueEntity> = qDao.getAllQueueFlow().first()

    private suspend fun sync() = repo.syncPendingTransactions(userInitiated = true)

    /** A debt the user owes, on the sheet and on this device. */
    private suspend fun seedIOwe(person: String, owed: Double) {
        val serial = DateSerialUtils.toDateSerial(then)
        listOf<Any>(serial, 0.4, person, "Tickets", owed).forEachIndexed { index, value ->
            sheet.setCell("Nov26", 4, column("W") + index, value)
        }
        sheet.setCell("Nov26", 4, column("AC"), "Pending")
        sheet.setCell("Nov26", 4, column("AF"), "pay_nov")
        pDao.upsert(
            PayableEntity(
                uuid = "pay_nov", dateIncurredSerial = serial, timeSerial = 0.4, paidBy = person,
                whatFor = "Tickets", amountIOwe = owed, dateRepaidSerial = null, repaymentStatus = "Pending",
                tabName = "Nov26", sheetRowIndex = 4
            )
        )
    }

    /** A split where Asha owes 200 while the user owes Asha 300, so the insert also reduces the debt. */
    private suspend fun nettingSplit(): Transaction {
        seedIOwe("Asha", 300.0)
        val expense = Transaction(
            id = "txn_netting", timestamp = then, dateSerial = DateSerialUtils.toDateSerial(then),
            timeSerial = DateSerialUtils.toTimeSerial(then), account = "Cash", merchant = "Lunch",
            category = "Food & Drink", totalAmount = 300.0, companions = "Asha", myShare = 100.0, owedToMe = 200.0,
            status = TransactionStatus.PENDING, reviewState = ReviewState.CONFIRMED, syncState = SyncState.PENDING
        )
        repo.insertTransaction(expense)
        return expense
    }

    @Test
    fun aRepaymentWhoseDateFormattingFailsIsStillDeliveredOnce() = runBlocking {
        seedIOwe("Riya", 500.0)
        repo.logRepayment("Riya", 100.0, null, then)
        sheet.failFormatting = true

        sync()

        assertTrue("the formatting was tried", sheet.failedFormattings > 0)
        assertEquals(400.0, cell(4, "AA").toDouble(), 0.001)
        assertTrue(queue().isEmpty())

        sync()
        assertEquals(400.0, cell(4, "AA").toDouble(), 0.001)
    }

    @Test
    fun aLocalFailureAfterASettlementIsWrittenDoesNotSendItAgain() = runBlocking {
        listOf<Any>(
            DateSerialUtils.toDateSerial(then), 0.4, "Cash", "Villa", "", "Travel", 1000.0, "Asha", 400.0, 600.0,
            "Pending"
        ).forEachIndexed { index, value -> sheet.setCell("Nov26", 4, index + 1, value) }
        sheet.setCell("Nov26", 4, column("AD"), "txn_villa")
        tDao.insert(
            TransactionEntity(
                id = "txn_villa", timestamp = then, dateSerial = DateSerialUtils.toDateSerial(then), timeSerial = 0.4,
                account = "Cash", merchant = "Villa", category = "Travel", totalAmount = 1000.0, companions = "Asha",
                myShare = 400.0, owedToMe = 600.0, status = "Pending", reviewState = ReviewState.CONFIRMED.name,
                syncState = SyncState.SYNCED.name, sheetRowIndex = 4
            )
        )
        repo.logCredit("Asha", 250.0, emptyList(), "Cash", "Friend settlement", "Part", then)
        tDao.breakNextUpdate = true

        sync()

        assertEquals(1, tDao.breaks)
        assertEquals(350.0, cell(4, "J").toDouble(), 0.001)
        assertEquals(1, rows().count { cell(it, "T").isNotBlank() })
        assertTrue(queue().isEmpty())
        // The read that follows the sync brings the local row back in step
        assertEquals(350.0, tDao.getById("txn_villa")!!.owedToMe, 0.001)
    }

    @Test
    fun aNettingInsertThatFailsLocallyAfterItsWritesIsFinishedByARetryWithoutWritingTwice() = runBlocking {
        val expense = nettingSplit()
        tDao.breakNextSyncState = true

        sync()

        assertEquals(1, tDao.breaks)
        assertEquals(100.0, cell(4, "AA").toDouble(), 0.001)
        val failed = queue().single()

        qDao.resetQueueItem(failed.id)
        sync()

        assertEquals(100.0, cell(4, "AA").toDouble(), 0.001)
        assertEquals(1, rows().count { cell(it, "AD") == expense.id })
        assertEquals(SyncState.SYNCED.name, tDao.getById(expense.id)!!.syncState)
        assertTrue(queue().isEmpty())
    }
}
