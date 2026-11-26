package com.financeautomation.data.repository

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * A repayment dated in December that reaches the sheet after the new year's carry, and a repayment that matches
 * nothing. Real repository, executor and Sheets client over two in-memory workbooks with an injected clock.
 * Invented data.
 */
class RepaymentAcrossYearsTest {

    private val now = Instant.parse("2027-01-05T10:00:00Z").toEpochMilli()
    private val paid = Instant.parse("2026-12-30T10:00:00Z").toEpochMilli()
    private val owedSince = DateSerialUtils.toDateSerial(Instant.parse("2026-12-10T10:00:00Z").toEpochMilli())

    private val old = FakeSheetApplier(mutableListOf("Dec26", "Config", "Subscriptions", "Trips"))
    private val new = FakeSheetApplier(mutableListOf("Jan27", "Config", "Subscriptions", "Trips"))
    private var newYearAnswers = true

    private val qDao = InMemoryQueueDao()
    private val pDao = InMemoryPayableDao()
    private val repo: TransactionRepositoryImpl

    init {
        val ids = mapOf(2026 to "workbook_2026", 2027 to "workbook_2027")
        val tokens = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (!request.url.toString().contains("workbook_2027")) {
                old.serve(request)
            } else if (newYearAnswers) {
                new.serve(request)
            } else {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(500).message("Error")
                    .body("""{"error":{"code":500}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }).build()
        val preferences = mutableMapOf<String, String?>()

        @Suppress("UNCHECKED_CAST")
        val prefs: PreferencesRepository = Proxy.newProxyInstance(
            PreferencesRepository::class.java.classLoader,
            arrayOf(PreferencesRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getPreference" -> preferences[args!![0] as String]
                "setPreference" -> { preferences[args!![0] as String] = args[1] as String?; null }
                "getGoogleAccountEmailSync" -> "test@example.com"
                "getSpreadsheetIdForYearSync" -> ids[args!![0] as Int]
                "getSpreadsheetIdSync" -> ids[2027]
                "getDefaultAccount" -> flowOf("Cash")
                "getDefaultAccountSync" -> "Cash"
                else -> null
            }
        } as PreferencesRepository

        val sheets = GoogleSheetsClient(http, tokens)
        val tDao = InMemoryTransactionDao()
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
                googleDriveClient = GoogleDriveClient(http, tokens),
                syncQueueDao = qDao,
                lineageDao = lDao,
                transactionDao = tDao,
                payableDao = pDao,
                nextWorkbookIdProvider = { year -> ids[year] },
                clock = { now }
            ),
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now }
        )
    }

    private fun column(letters: String): Int = SheetSchemaConstants.columnIndex(letters) + 1

    private fun FakeSheetApplier.seedPayable(
        tab: String, row: Int, uuid: String, whatFor: String, amount: Double, status: String
    ) {
        listOf<Any>(owedSince, 0.5, "Riya", whatFor, amount, "", status)
            .forEachIndexed { index, value -> setCell(tab, row, column("W") + index, value) }
        setCell(tab, row, column("AF"), uuid)
    }

    private fun FakeSheetApplier.cell(tab: String, row: Int, letters: String): String =
        getCell(tab, row, letters)?.toString().orEmpty()

    private fun FakeSheetApplier.contents(): String =
        grid.keys.sorted().joinToString { tab -> grid.getValue(tab).keys.sorted().joinToString { getRow(tab, it).toString() } }

    private suspend fun localDebt(uuid: String, whatFor: String, amount: Double, tab: String) = pDao.upsert(
        PayableEntity(
            uuid = uuid, dateIncurredSerial = owedSince, timeSerial = 0.5, paidBy = "Riya", whatFor = whatFor,
            amountIOwe = amount, dateRepaidSerial = null, repaymentStatus = "Pending", tabName = tab, sheetRowIndex = 4
        )
    )

    /** The new year's carry ran elsewhere; this device still shows the December row as open. */
    private suspend fun carriedIntoTheNewYear() {
        old.seedPayable("Dec26", 4, "pay_dec", "Tickets", 500.0, "Carried Fwd")
        new.seedPayable("Jan27", 4, "pay_jan", "[Carried from Dec26] Tickets", 500.0, "Pending")
        localDebt("pay_dec", "Tickets", 500.0, "Dec26")
    }

    private suspend fun sync() = repo.syncPendingTransactions(userInitiated = true)

    private suspend fun queue(): List<SyncQueueEntity> = qDao.getAllQueueFlow().first().sortedBy { it.id }

    private suspend fun iOwe(): Double = pDao.getPending().sumOf { it.amountIOwe }

    private fun excessRows(): Int = new.grid.getValue("Jan27").keys.filter { it >= 4 }
        .count { new.cell("Jan27", it, "AD").isNotBlank() }

    @Test
    fun aDecemberRepaymentGoesToTheDebtCarriedIntoTheNewYear() = runBlocking {
        carriedIntoTheNewYear()
        val oldBefore = old.contents()
        repo.logRepayment("Riya", 200.0, null, paid)

        sync()

        assertEquals(300.0, new.cell("Jan27", 4, "AA").toDouble(), 0.001)
        assertEquals(oldBefore, old.contents())
        assertEquals(300.0, iOwe(), 0.001)
        assertEquals("Carried Fwd", pDao.getByUuid("pay_dec")!!.repaymentStatus)
        assertTrue(queue().isEmpty())
    }

    @Test
    fun aSavedPlanForTheNewYearIsSentAgainThereAndItsNewRowIsAddedOnce() = runBlocking {
        carriedIntoTheNewYear()
        val oldBefore = old.contents()
        repo.logRepayment("Riya", 600.0, null, paid)
        // The first write adds the excess row, the second one is the repayment itself
        new.failValueWrite(2, applied = false)
        sync()
        assertTrue(new.faultWasHit())

        sync()

        assertEquals(0.0, new.cell("Jan27", 4, "AA").toDouble(), 0.001)
        assertEquals("Repaid", new.cell("Jan27", 4, "AC"))
        assertEquals(1, excessRows())
        assertEquals(oldBefore, old.contents())
        assertTrue(queue().isEmpty())
    }

    private suspend fun assertNoOpenDebt() {
        val item = queue().single { it.operationType == SyncQueueEntity.OP_LOG_REPAYMENT }
        assertEquals(SyncQueueEntity.STATUS_FAILED, item.status)
        assertEquals(SyncErrorKind.NO_OPEN_DEBT.name, item.lastErrorKind)
        assertFalse("no name is kept in the error", item.lastErrorDetail.orEmpty().contains("Riya"))
    }

    @Test
    fun aDebtThatIsNewThisYearIsNeverPaidByALastYearRepayment() = runBlocking {
        new.seedPayable("Jan27", 4, "pay_new", "Groceries", 500.0, "Pending")
        localDebt("pay_new", "Groceries", 500.0, "Jan27")
        val before = new.contents()
        repo.logRepayment("Riya", 200.0, null, paid)

        sync()

        assertEquals(before, new.contents())
        assertNoOpenDebt()
    }

    @Test
    fun aRepaymentWithNothingOwedAnywhereFailsWithoutNamingAnyone() = runBlocking {
        localDebt("pay_gone", "Tickets", 500.0, "Dec26")
        repo.logRepayment("Riya", 200.0, null, paid)

        sync()

        assertNoOpenDebt()
    }

    @Test
    fun aNewYearSheetThatCannotBeReadIsRetriedNotRefused() = runBlocking {
        carriedIntoTheNewYear()
        repo.logRepayment("Riya", 200.0, null, paid)
        newYearAnswers = false

        sync()

        assertEquals(SyncQueueEntity.STATUS_PENDING, queue().single().status)

        newYearAnswers = true
        sync()

        assertEquals(300.0, new.cell("Jan27", 4, "AA").toDouble(), 0.001)
        assertTrue(queue().isEmpty())
    }

    @Test
    fun aSavedDecemberPlanWhoseDebtThenMovedToTheNewYearWritesNothing() = runBlocking {
        old.seedPayable("Dec26", 4, "pay_dec", "Tickets", 500.0, "Pending")
        localDebt("pay_dec", "Tickets", 500.0, "Dec26")
        repo.logRepayment("Riya", 200.0, null, paid)
        old.failValueWrite(1, applied = false)
        sync()
        assertTrue(old.faultWasHit())
        // The new year's carry then runs elsewhere
        old.setCell("Dec26", 4, column("AC"), "Carried Fwd")
        new.seedPayable("Jan27", 4, "pay_jan", "[Carried from Dec26] Tickets", 500.0, "Pending")
        val oldBefore = old.contents()
        val newBefore = new.contents()

        sync()

        assertEquals(oldBefore, old.contents())
        assertEquals(newBefore, new.contents())
        assertEquals(SyncErrorKind.PARTLY_SENT.name, queue().single().lastErrorKind)
    }

    @Test
    fun anEmptyPlanSavedByAnOlderBuildIsPlannedAgain() = runBlocking {
        old.seedPayable("Dec26", 4, "pay_dec", "Tickets", 500.0, "Pending")
        localDebt("pay_dec", "Tickets", 500.0, "Dec26")
        repo.logRepayment("Riya", 200.0, null, paid)
        qDao.updatePlanJson(queue().single().id, """{"edits":[],"appends":[],"settled":[]}""")

        sync()

        assertEquals(300.0, old.cell("Dec26", 4, "AA").toDouble(), 0.001)
        assertTrue(queue().isEmpty())
    }
}
