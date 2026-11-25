package com.financeautomation.data.repository

import com.financeautomation.core.model.SyncState
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.money.FakeSheetApplier
import com.financeautomation.testutil.InMemoryInflowDao
import com.financeautomation.testutil.InMemoryLineageDao
import com.financeautomation.testutil.InMemoryPayableDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Rows whose date names another month than the tab that holds them, such as carried copies. They must not be pruned,
 * and must be looked up where they really are. Real repository and Sheets client, in-memory workbooks.
 */
class RowOutsideItsDateTabTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun millis(year: Int, month: Int, day: Int) =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private inner class Harness(val now: Long, val workbooks: Map<Int, FakeSheetApplier>, confirmedYear: Int) {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        private val ids = workbooks.keys.associateWith { "workbook_$it" }
        private val preferences = mutableMapOf<String, String?>(
            PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID to "workbook_$confirmedYear"
        )
        private val tokens = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }
        private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            val year = ids.entries.first { url.contains(it.value) }.key
            workbooks.getValue(year).serve(chain.request())
        }).build()

        @Suppress("UNCHECKED_CAST")
        private val prefs: PreferencesRepository = Proxy.newProxyInstance(
            PreferencesRepository::class.java.classLoader,
            arrayOf(PreferencesRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getPreference" -> preferences[args!![0] as String]
                "setPreference" -> { preferences[args!![0] as String] = args[1] as String?; null }
                "getGoogleAccountEmailSync" -> "test@example.com"
                "getSpreadsheetIdForYearSync" -> ids[args!![0] as Int]
                "getSpreadsheetIdSync" -> ids[ids.keys.max()]
                "getDefaultAccount" -> flowOf("Cash")
                "getDefaultAccountSync" -> "Cash"
                else -> null
            }
        } as PreferencesRepository

        private val sheets = GoogleSheetsClient(http, tokens)

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = InMemoryPayableDao(),
            inflowDao = InMemoryInflowDao(),
            lineageDao = InMemoryLineageDao(),
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = AccountingSyncExecutor(
                googleSheetsClient = sheets,
                googleDriveClient = GoogleDriveClient(http, tokens),
                syncQueueDao = qDao,
                lineageDao = InMemoryLineageDao(),
                transactionDao = tDao,
                clock = { now }
            ),
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now }
        )
    }

    private fun FakeSheetApplier.seedDebit(tab: String, row: Int, uuid: String?, date: Long, item: String) {
        listOf(DateSerialUtils.toDateSerial(date), 0.5, "Cash", item, "", "Food & Drink", 500.0, "Riya", 0.0, 500.0, "Pending")
            .forEachIndexed { index, value -> setCell(tab, row, index + 1, value) }
        if (uuid != null) setCell(tab, row, 30, uuid)
    }

    private fun synced(id: String, date: Long, item: String, row: Int) = TransactionEntity(
        id = id, timestamp = date, dateSerial = DateSerialUtils.toDateSerial(date), timeSerial = 0.5,
        account = "Cash", merchant = item, category = "Food & Drink", totalAmount = 500.0, myShare = 0.0,
        owedToMe = 500.0, companions = "Riya", status = "Pending", syncState = SyncState.SYNCED.name,
        sheetRowIndex = row
    )

    @Test
    fun aRowWithoutAnIdDatedInAnEarlierMonthSurvivesTheNextReconcile() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Oct26", "Nov26"))
        sheet.seedDebit("Nov26", 4, null, millis(2026, 10, 12), "[Carried from Oct26] Dinner")
        val h = Harness(millis(2026, 11, 20), mapOf(2026 to sheet), 2026)

        h.repo.reconcileWithSheet()
        assertNotNull(h.tDao.getById("sheet_Nov26_4"))
        h.repo.reconcileWithSheet()

        assertNotNull("the sheet still holds the row", h.tDao.getById("sheet_Nov26_4"))
    }

    @Test
    fun aRowHeldByATabOutsideTheReadIsNotPruned() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Aug26", "Oct26", "Nov26"))
        // Delivered to August, then re-dated to November: the row stays in the August tab, which is not read
        sheet.seedDebit("Aug26", 4, "txn_moved", millis(2026, 11, 2), "Dinner")
        val h = Harness(millis(2026, 11, 20), mapOf(2026 to sheet), 2026)
        h.tDao.insert(synced("txn_moved", millis(2026, 11, 2), "Dinner", 4))
        h.tDao.insert(synced("txn_really_gone", millis(2026, 11, 3), "Cab", 9))

        h.repo.reconcileWithSheet()

        assertNotNull(h.tDao.getById("txn_moved"))
        assertNull("a row no tab holds is still removed", h.tDao.getById("txn_really_gone"))
    }

    @Test
    fun voidingACopyDatedLastYearWritesToThisYearsWorkbook() = runBlocking {
        val previous = FakeSheetApplier(mutableListOf("Dec26"))
        val current = FakeSheetApplier(mutableListOf("Jan27"))
        current.seedDebit("Jan27", 4, "txn_copy", millis(2026, 12, 12), "[Carried from Dec26] Dinner")
        val h = Harness(millis(2027, 1, 8), mapOf(2026 to previous, 2027 to current), 2027)
        h.tDao.insert(synced("txn_copy", millis(2026, 12, 12), "[Carried from Dec26] Dinner", 4))

        h.repo.voidTransaction("txn_copy")
        h.repo.syncPendingTransactions(userInitiated = true)

        assertEquals("Void", current.getCell("Jan27", 4, "K"))
        assertEquals("no month tab of last year is created in this year's workbook", listOf("Jan27"), current.tabs.toList())
        assertEquals(0, h.qDao.queue.size)
    }
}
