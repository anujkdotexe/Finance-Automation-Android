package com.financeautomation.data.repository

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.NettingUseCase
import com.financeautomation.domain.usecase.SettlementWaterfallUseCase
import com.financeautomation.money.FakeSheetApplier
import com.financeautomation.testutil.InMemoryInflowDao
import com.financeautomation.testutil.InMemoryLineageDao
import com.financeautomation.testutil.InMemoryPayableDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * The app and the sheet must repay the same debt. Real repository, executor and Sheets client over an in-memory
 * workbook whose row order differs from the date order, an injected clock and invented data.
 */
class RepaymentOrderParityTest {

    private val now = Instant.parse("2026-11-22T10:00:00Z").toEpochMilli()
    private val tab = "Nov26"
    private val sheet = FakeSheetApplier(mutableListOf(tab, "Config", "Subscriptions", "Trips"))
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
            accountingSyncExecutor = createTestAccountingSyncExecutor(
                sheetsClient = sheets, syncQueueDao = qDao, lineageDao = lDao, transactionDao = tDao, payableDao = pDao
            ),
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now }
        )
    }

    private fun rowOf(uuid: String): Int =
        (4..40).first { sheet.getCell(tab, it, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID) == uuid }

    private fun sheetAmount(uuid: String): Double =
        sheet.getCell(tab, rowOf(uuid), SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE).toString().toDouble()

    private suspend fun appAmount(uuid: String): Double = pDao.getByUuid(uuid)!!.amountIOwe

    @Test
    fun appAndSheetRepayTheOldestDebtEvenWhenItsRowComesLater() = runBlocking {
        // Logged and delivered newest first, so the older debt sits on the later row
        val newer = (repo.logFriendPaid("Riya", 500.0, "Cab", now - 3_600_000L) as Result.Success).data
        repo.syncPendingTransactions(userInitiated = true)
        val older = (repo.logFriendPaid("Riya", 500.0, "Dinner", now - 3 * 86_400_000L) as Result.Success).data
        repo.syncPendingTransactions(userInitiated = true)
        assertTrue(rowOf(older) > rowOf(newer))

        assertTrue(repo.logRepayment("Riya", 200.0, null, now) is Result.Success)
        repo.syncPendingTransactions(userInitiated = true)

        assertEquals(300.0, appAmount(older), 0.001)
        assertEquals(500.0, appAmount(newer), 0.001)
        assertEquals(300.0, sheetAmount(older), 0.001)
        assertEquals(500.0, sheetAmount(newer), 0.001)
    }

    private suspend fun seedDebt(uuid: String, row: Int, dateSerial: Int?, whatFor: String) {
        val first = SheetSchemaConstants.columnIndex("W") + 1
        listOf<Any?>(dateSerial, if (dateSerial == null) null else 0.5, "Riya", whatFor, 100.0, null, "Pending")
            .forEachIndexed { index, value -> if (value != null) sheet.setCell(tab, row, first + index, value) }
        sheet.setCell(tab, row, SheetSchemaConstants.columnIndex(SheetSchemaConstants.COL_LETTER_PAYABLE_UUID) + 1, uuid)
        pDao.upsert(
            PayableEntity(
                uuid = uuid, dateIncurredSerial = dateSerial, timeSerial = if (dateSerial == null) null else 0.5,
                paidBy = "Riya", whatFor = whatFor, amountIOwe = 100.0, dateRepaidSerial = null,
                repaymentStatus = "Pending", tabName = tab, sheetRowIndex = row
            )
        )
    }

    @Test
    fun appAndSheetBothRepayADatedDebtBeforeAnUndatedOne() = runBlocking {
        seedDebt("pay_undated", 4, null, "No date")
        seedDebt("pay_dated", 5, DateSerialUtils.toDateSerial(now) - 2, "Dated")

        repo.logRepayment("Riya", 100.0, null, now)
        repo.syncPendingTransactions(userInitiated = true)

        assertEquals(0.0, appAmount("pay_dated"), 0.001)
        assertEquals(100.0, appAmount("pay_undated"), 0.001)
        assertEquals(0.0, sheetAmount("pay_dated"), 0.001)
        assertEquals(100.0, sheetAmount("pay_undated"), 0.001)
    }

    private fun payable(uuid: String, row: Int, dateSerial: Int) = LedgerPayable(
        tab = tab, row = row, uuid = uuid, dateIncurredSerial = dateSerial, timeSerial = 0.5, paidBy = "Riya",
        whatFor = uuid, amountOwed = 100.0, dateRepaidSerial = null, status = "Pending"
    )

    private fun debit(uuid: String, row: Int, dateSerial: Int) = LedgerDebit(
        tab = tab, row = row, uuid = uuid, dateSerial = dateSerial, timeSerial = 0.5, account = "Cash", item = uuid,
        tripTag = "", category = "Other", total = 200.0, companions = "Riya", myShare = 100.0, owed = 100.0,
        status = "Pending"
    )

    @Test
    fun nettingANewReceivableOffsetsTheOldestDebtFirst() {
        val result = NettingUseCase().nettingOnDebit(
            "Riya", 60.0, 46350, listOf(payable("pay_newer", 4, 46340), payable("pay_older", 5, 46300))
        )

        assertEquals(setOf("pay_older"), result.edits.map { it.uuid }.toSet())
    }

    @Test
    fun nettingANewDebtSettlesTheOldestReceivableFirst() {
        val settlement = SettlementWaterfallUseCase()
        val result = NettingUseCase().nettingOnPayable(
            "Riya", 60.0, listOf(debit("txn_newer", 4, 46340), debit("txn_older", 5, 46300))
        ) { row, paid, settler, allowFull -> settlement.settleOneDebt(row, paid, settler, allowFull) }

        assertEquals(setOf("txn_older"), result.edits.map { it.uuid }.toSet())
    }
}
