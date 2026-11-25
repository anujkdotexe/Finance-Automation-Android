package com.financeautomation.data.repository

import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.money.FakeSheetApplier
import com.financeautomation.service.worker.MonthlyRolloverWorker
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The scheduled carry through the real worker, repository, executor and Sheets client, over in-memory workbooks and
 * an injected clock. Covers months in which no carry ran, the year boundary, a half finished carry and the daily run.
 */
class MissedMonthCarryTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(year: Int, month: Int, day: Int) = ZonedDateTime.of(year, month, day, 9, 0, 0, 0, zone)

    private inner class Harness(var now: ZonedDateTime, val workbooks: Map<Int, FakeSheetApplier>) {
        val qDao = InMemoryQueueDao()
        val preferences = mutableMapOf<String, String?>()
        private val ids = workbooks.keys.associateWith { "workbook_$it" }
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
                "getSpreadsheetIdSync" -> ids[now.year]
                "getDefaultAccount" -> flowOf("Cash")
                "getDefaultAccountSync" -> "Cash"
                else -> null
            }
        } as PreferencesRepository

        private val sheets = GoogleSheetsClient(http, tokens)
        private val tDao = InMemoryTransactionDao()
        private val lDao = InMemoryLineageDao()

        val repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = InMemoryPayableDao(),
            inflowDao = InMemoryInflowDao(),
            lineageDao = lDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            accountingSyncExecutor = AccountingSyncExecutor(
                googleSheetsClient = sheets,
                googleDriveClient = GoogleDriveClient(http, tokens),
                syncQueueDao = qDao,
                lineageDao = lDao,
                transactionDao = tDao,
                previousWorkbookIdProvider = { year -> ids[year] },
                clock = { now.toInstant().toEpochMilli() }
            ),
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now.toInstant().toEpochMilli() }
        )

        suspend fun runWorker() = MonthlyRolloverWorker.executeRollover(
            now, repo, { repo.syncPendingTransactions(userInitiated = true) }
        )

        fun calls(): Int = workbooks.values.sumOf { it.calls.size }
    }

    private fun FakeSheetApplier.seedDebit(tab: String, row: Int, uuid: String, item: String, owed: Double, status: String = "Pending") {
        listOf(46240, 0.5, "Cash", item, "", "Food & Drink", owed * 2, "Riya", owed, owed, status)
            .forEachIndexed { index, value -> setCell(tab, row, index + 1, value) }
        setCell(tab, row, columnNumber("AD"), uuid)
    }

    private fun FakeSheetApplier.seedPayable(tab: String, row: Int, uuid: String, whatFor: String, amount: Double) {
        listOf(46240, 0.5, "Asha", whatFor, amount, "", "Pending")
            .forEachIndexed { index, value -> setCell(tab, row, columnNumber("W") + index, value) }
        setCell(tab, row, columnNumber("AF"), uuid)
    }

    private fun columnNumber(letters: String): Int = letters.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) }

    /** Amounts of every open receivable in a workbook, by description. */
    private fun FakeSheetApplier.openDebits(): List<Pair<String, String>> = grid.keys.flatMap { tab ->
        grid.getValue(tab).keys.filter { it >= 4 }.mapNotNull { row ->
            if (getCell(tab, row, "K") == "Pending") tab to getCell(tab, row, "D").toString() else null
        }
    }

    @Test
    fun debtsLeftTwoAndThreeMonthsBackAreMovedIntoTheCurrentMonth() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Aug26", "Sep26", "Nov26"))
        sheet.seedDebit("Aug26", 4, "txn_aug", "Dinner", 500.0)
        sheet.seedDebit("Sep26", 4, "txn_sep", "Cab", 120.0)
        sheet.seedPayable("Aug26", 4, "pay_aug", "Tickets", 300.0)
        val h = Harness(at(2026, 11, 20), mapOf(2026 to sheet))

        h.runWorker()

        assertEquals(
            listOf("Nov26" to "[Carried from Aug26] Dinner", "Nov26" to "[Carried from Sep26] Cab"),
            sheet.openDebits().sortedBy { it.second }
        )
        assertEquals("Carried Fwd", sheet.getCell("Aug26", 4, "K"))
        assertEquals("Carried Fwd", sheet.getCell("Sep26", 4, "K"))
        assertEquals("Carried Fwd", sheet.getCell("Aug26", 4, "AC"))
        assertEquals(
            listOf("[Carried from Aug26] Tickets"),
            sheet.grid.getValue("Nov26").keys.filter { it >= 4 }.mapNotNull { sheet.getCell("Nov26", it, "Z") }
        )
        assertTrue(h.qDao.queue.isEmpty())
    }

    @Test
    fun afterTheCarryReachedTheSheetLaterDailyRunsQueueAndReadNothing() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Oct26", "Nov26"))
        sheet.seedDebit("Oct26", 4, "txn_oct", "Dinner", 500.0)
        val h = Harness(at(2026, 11, 5), mapOf(2026 to sheet))
        h.runWorker()
        assertEquals("2026-11", h.preferences[PreferencesRepository.KEY_CARRY_DONE_MONTH])
        val callsAfterFirstRun = h.calls()

        h.now = at(2026, 11, 6)
        h.runWorker()
        h.now = at(2026, 11, 30)
        h.runWorker()

        assertEquals(callsAfterFirstRun, h.calls())
        assertTrue(h.qDao.queue.isEmpty())
    }

    @Test
    fun theNextMonthRunsAgain() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Oct26", "Nov26", "Dec26"))
        sheet.seedDebit("Oct26", 4, "txn_oct", "Dinner", 500.0)
        val h = Harness(at(2026, 11, 5), mapOf(2026 to sheet))
        h.runWorker()

        h.now = at(2026, 12, 9)
        h.runWorker()

        assertEquals(listOf("Dec26" to "[Carried from Nov26] Dinner"), sheet.openDebits())
    }

    @Test
    fun aCarryThatWroteTheCopyButNotTheMarkIsFinishedWithoutASecondCopy() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Oct26", "Nov26"))
        sheet.seedDebit("Oct26", 4, "txn_oct", "Dinner", 500.0)
        sheet.seedDebit("Nov26", 4, "txn_copy", "[Carried from Oct26] Dinner", 500.0)
        val h = Harness(at(2026, 11, 3), mapOf(2026 to sheet))

        h.runWorker()
        h.runWorker()

        assertEquals(listOf("Nov26" to "[Carried from Oct26] Dinner"), sheet.openDebits())
        assertEquals("Carried Fwd", sheet.getCell("Oct26", 4, "K"))
    }

    @Test
    fun oneCopyNeverStandsForTwoIdenticalDebts() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Oct26", "Nov26"))
        sheet.seedDebit("Oct26", 4, "txn_one", "Tea", 40.0)
        sheet.seedDebit("Oct26", 5, "txn_two", "Tea", 40.0)
        sheet.seedDebit("Nov26", 4, "txn_copy", "[Carried from Oct26] Tea", 40.0)
        val h = Harness(at(2026, 11, 3), mapOf(2026 to sheet))

        h.runWorker()

        assertEquals(2, sheet.openDebits().size)
        assertTrue(sheet.openDebits().all { it.first == "Nov26" })
    }

    @Test
    fun monthsMissedAcrossTheYearBoundaryAreCaughtUpInOrder() = runBlocking {
        val previous = FakeSheetApplier(mutableListOf("Sep26", "Oct26"))
        previous.seedDebit("Sep26", 4, "txn_sep", "Dinner", 500.0)
        val current = FakeSheetApplier(mutableListOf("Jan27", "Mar27"))
        val h = Harness(at(2027, 3, 10), mapOf(2026 to previous, 2027 to current))

        h.runWorker()

        assertTrue("nothing stays open in last year's workbook", previous.openDebits().isEmpty())
        assertEquals("Carried Fwd", previous.getCell("Sep26", 4, "K"))
        assertEquals("exactly one open copy, in the current month", listOf("Mar27" to "[Carried from Jan27] Dinner"), current.openDebits())
        assertEquals("2027", h.preferences[PreferencesRepository.KEY_CARRY_YEAR_OPENED])
        assertEquals("2027-03", h.preferences[PreferencesRepository.KEY_CARRY_DONE_MONTH])

        h.now = at(2027, 3, 11)
        val callsBefore = h.calls()
        h.runWorker()
        assertEquals(callsBefore, h.calls())
    }

    @Test
    fun aFirstYearUserGetsNoJanuaryCarryThatCouldOnlyFail() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Jan26"))
        val h = Harness(at(2026, 1, 5), mapOf(2026 to sheet))

        val result = h.repo.planMonthlyCarry(h.now)

        assertEquals(emptyList<Pair<String, String>>(), result)
    }

    @Test
    fun aFailedCarryDoesNotCountAsDone() = runBlocking {
        val sheet = FakeSheetApplier(mutableListOf("Oct26", "Nov26"))
        sheet.seedDebit("Oct26", 4, "txn_oct", "Dinner", 500.0)
        sheet.forceFindRowError = true
        val h = Harness(at(2026, 11, 5), mapOf(2026 to sheet))

        h.runWorker()

        assertEquals(null, h.preferences[PreferencesRepository.KEY_CARRY_DONE_MONTH])
        assertEquals(listOf("Nov26" to "Oct26"), h.repo.planMonthlyCarry(at(2026, 11, 6)))
    }
}
