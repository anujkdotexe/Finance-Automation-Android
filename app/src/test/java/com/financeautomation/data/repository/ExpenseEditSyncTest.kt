package com.financeautomation.data.repository

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.domain.model.EditBlockReason
import com.financeautomation.domain.model.EditBlockedException
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.money.FakeSheetApplier
import com.financeautomation.presentation.viewmodel.DiscardWarning
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
 * Editing a delivered expense: what reaches the sheet, and what is refused because the sheet moved on. Real
 * repository, executor and Sheets client over an in-memory workbook with an injected clock. Invented data.
 */
class ExpenseEditSyncTest {

    private val now = Instant.parse("2026-11-22T10:00:00Z").toEpochMilli()
    private val then = now - 3_600_000L
    private val sheet = FakeSheetApplier(mutableListOf("Nov26", "Config", "Subscriptions", "Trips"))
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

    private val id = "txn_edit_one"

    private fun justMe(total: Double = 300.0) = Transaction(
        id = id, timestamp = then, dateSerial = DateSerialUtils.toDateSerial(then),
        timeSerial = DateSerialUtils.toTimeSerial(then), account = "Cash", merchant = "Lunch",
        category = "Food & Drink", totalAmount = total, companions = "Personal", myShare = total, owedToMe = 0.0,
        status = TransactionStatus.PERSONAL, reviewState = ReviewState.CONFIRMED, syncState = SyncState.PENDING
    )

    private fun split(total: Double = 300.0, share: Double = 100.0) = justMe(total).copy(
        companions = "Asha", myShare = share, owedToMe = total - share, status = TransactionStatus.PENDING
    )

    private suspend fun sync() = repo.syncPendingTransactions(userInitiated = true)

    private suspend fun delivered(expense: Transaction) {
        assertTrue(repo.insertTransaction(expense) is Result.Success)
        sync()
        assertTrue("the insert was delivered", queue().isEmpty())
    }

    /** The local row as a screen would load it. */
    private fun local(): Transaction = tDao.rows.getValue(id).toDomain()

    private suspend fun edit(change: (Transaction) -> Transaction): Result<Unit> =
        repo.updateTransaction(change(local()).copy(syncState = SyncState.PENDING))

    private suspend fun queue(): List<SyncQueueEntity> = qDao.getAllQueueFlow().first()

    private fun row(): Int = sheet.grid.getValue("Nov26").keys.filter { it >= 4 }
        .single { sheet.getCell("Nov26", it, SheetSchemaConstants.COL_LETTER_DEBIT_UUID) == id }

    private fun text(column: String): String = sheet.getCell("Nov26", row(), column)?.toString().orEmpty()

    private fun number(column: String): Double = text(column).toDouble()

    private fun put(column: String, value: Any) =
        sheet.setCell("Nov26", row(), SheetSchemaConstants.columnIndex(column) + 1, value)

    private fun cells(): String = sheet.getRow("Nov26", row()).toString()

    private suspend fun assertDelivered() = assertTrue("nothing is left: ${queue()}", queue().isEmpty())

    private suspend fun assertRefused(kind: SyncErrorKind, before: String) {
        assertEquals("nothing is written", before, cells())
        val item = queue().single()
        assertEquals(SyncQueueEntity.STATUS_FAILED, item.status)
        assertEquals(kind.name, item.lastErrorKind)
    }

    // ---- Ordinary edits reach the sheet in full

    @Test
    fun justMeChangedToASplitWritesWhatIsOwed() = runBlocking {
        delivered(justMe())

        edit { it.copy(companions = "Asha", myShare = 100.0, owedToMe = 200.0, status = TransactionStatus.PENDING) }
        sync()

        assertEquals("Asha", text("H"))
        assertEquals(100.0, number("I"), 0.001)
        assertEquals(200.0, number("J"), 0.001)
        assertEquals("Pending", text("K"))
        assertEquals(200.0, local().owedToMe, 0.001)
        assertDelivered()
    }

    @Test
    fun aHigherTotalRaisesWhatIsOwed() = runBlocking {
        delivered(split())

        edit { it.copy(totalAmount = 450.0, myShare = 150.0, owedToMe = 300.0) }
        sync()

        assertEquals(450.0, number("G"), 0.001)
        assertEquals(150.0, number("I"), 0.001)
        assertEquals(300.0, number("J"), 0.001)
        assertEquals(300.0, local().owedToMe, 0.001)
        assertDelivered()
    }

    @Test
    fun aLowerTotalLowersWhatIsOwed() = runBlocking {
        delivered(split())

        edit { it.copy(totalAmount = 150.0, myShare = 50.0, owedToMe = 100.0) }
        sync()

        assertEquals(150.0, number("G"), 0.001)
        assertEquals(100.0, number("J"), 0.001)
        assertDelivered()
    }

    @Test
    fun aBlankOwedCellOnAPersonalRowDoesNotHoldTheOwedAmountAtZero() = runBlocking {
        delivered(justMe())
        put("J", "")

        edit { it.copy(companions = "Asha", myShare = 100.0, owedToMe = 200.0, status = TransactionStatus.PENDING) }
        sync()

        assertEquals(200.0, number("J"), 0.001)
        assertEquals(200.0, local().owedToMe, 0.001)
        assertDelivered()
    }

    // ---- The sheet moved on: money and status are kept

    private fun carriedOnSheet() = put("K", "Carried Fwd")

    private fun partlyRepaidOnSheet() = put("J", 80.0)

    private fun settledOnSheet() {
        put("J", 0.0)
        put("K", "Settled")
    }

    private fun voidedOnSheet() {
        put("D", "[Void] Lunch")
        put("G", 0.0)
        put("I", 0.0)
        put("J", 0.0)
        put("K", "Void")
    }

    private fun categoryOnlyEditKeeps(changeOnSheet: () -> Unit) = runBlocking {
        delivered(split())
        changeOnSheet()
        val money = listOf("D", "G", "H", "I", "J", "K").map { text(it) }

        edit { it.copy(category = "Travel") }
        sync()

        assertEquals("Travel", text("F"))
        assertEquals(money, listOf("D", "G", "H", "I", "J", "K").map { text(it) })
        assertEquals(text("J").toDouble(), local().owedToMe, 0.001)
        assertDelivered()
    }

    @Test
    fun aCategoryEditOnACarriedRowKeepsItCarried() = categoryOnlyEditKeeps(::carriedOnSheet)

    @Test
    fun aCategoryEditOnAPartlyRepaidRowKeepsWhatIsStillOwed() = categoryOnlyEditKeeps(::partlyRepaidOnSheet)

    @Test
    fun aCategoryEditOnASettledRowKeepsItSettled() = categoryOnlyEditKeeps(::settledOnSheet)

    @Test
    fun aCategoryEditOnAVoidedRowKeepsItVoid() = categoryOnlyEditKeeps(::voidedOnSheet)

    private fun amountEditIsRefused(kind: SyncErrorKind, changeOnSheet: () -> Unit) = runBlocking {
        delivered(split())
        changeOnSheet()
        val before = cells()

        edit { it.copy(totalAmount = 450.0, myShare = 150.0, owedToMe = 300.0) }
        sync()

        assertRefused(kind, before)
        assertEquals("the phone shows the sheet", text("G").toDouble(), local().totalAmount, 0.001)
        assertEquals(text("J").toDoubleOrNull() ?: 0.0, local().owedToMe, 0.001)
    }

    @Test
    fun anAmountEditOfACarriedRowIsRefused() = amountEditIsRefused(SyncErrorKind.EDIT_CARRIED, ::carriedOnSheet)

    @Test
    fun anAmountEditOfAPartlyRepaidRowIsRefused() =
        amountEditIsRefused(SyncErrorKind.EDIT_SHEET_CHANGED, ::partlyRepaidOnSheet)

    @Test
    fun anAmountEditOfASettledRowIsRefused() =
        amountEditIsRefused(SyncErrorKind.EDIT_SHEET_CHANGED, ::settledOnSheet)

    @Test
    fun anAmountEditOfAVoidedRowIsRefused() = amountEditIsRefused(SyncErrorKind.EDIT_SHEET_CHANGED, ::voidedOnSheet)

    @Test
    fun anAmountEditIsRefusedWhenTheOwedCellWasClearedOnTheSheet() =
        amountEditIsRefused(SyncErrorKind.EDIT_SHEET_CHANGED) { put("J", "") }

    @Test
    fun aDescriptionEditOfACarriedRowIsRefused() = runBlocking {
        delivered(split())
        carriedOnSheet()
        val before = cells()

        edit { it.copy(merchant = "Dinner") }
        sync()

        assertRefused(SyncErrorKind.EDIT_CARRIED, before)
        assertEquals("Lunch", local().merchant)
    }

    @Test
    fun anEditOfARowThePhoneKnowsIsCarriedIsRefusedAtOnce() = runBlocking {
        delivered(split())
        tDao.rows[id] = tDao.rows.getValue(id).copy(status = "Carried Fwd")

        val result = edit { it.copy(totalAmount = 450.0, owedToMe = 350.0) }

        val error = (result as Result.Error).throwable as EditBlockedException
        assertEquals(EditBlockReason.DEBT_CARRIED, error.reason)
        assertEquals(300.0, local().totalAmount, 0.001)
        assertDelivered()
    }

    @Test
    fun retryingARefusedEditChangesNothing() = runBlocking {
        delivered(split())
        partlyRepaidOnSheet()
        edit { it.copy(totalAmount = 450.0, myShare = 150.0, owedToMe = 300.0, category = "Travel") }
        sync()
        val before = cells()

        qDao.resetQueueItem(queue().single().id)
        sync()

        assertEquals(before, cells())
        assertDelivered()
    }

    @Test
    fun anEditMadeAgainAfterARefusalStartsFromTheSheet() = runBlocking {
        delivered(split())
        partlyRepaidOnSheet()
        edit { it.copy(totalAmount = 450.0, myShare = 150.0, owedToMe = 300.0) }
        sync()

        edit { it.copy(myShare = 220.0, owedToMe = 80.0, totalAmount = 300.0, category = "Travel") }
        sync()

        assertEquals(220.0, number("I"), 0.001)
        assertEquals(80.0, number("J"), 0.001)
        assertEquals("Travel", text("F"))
        assertDelivered()
    }

    // ---- Several edits

    @Test
    fun twoWaitingEditsAreSentAsOne() = runBlocking {
        delivered(split())

        edit { it.copy(totalAmount = 450.0, myShare = 150.0, owedToMe = 300.0) }
        edit { it.copy(category = "Travel") }
        assertEquals(1, queue().size)
        sync()

        assertEquals(450.0, number("G"), 0.001)
        assertEquals(300.0, number("J"), 0.001)
        assertEquals("Travel", text("F"))
        assertDelivered()
    }

    @Test
    fun aSecondEditAfterALostReplyIsNotMistakenForAChangeOnTheSheet() = runBlocking {
        delivered(split())
        edit { it.copy(totalAmount = 450.0, myShare = 150.0, owedToMe = 300.0) }
        sheet.failValueWrite(1, applied = true)
        sync()
        assertTrue(sheet.faultWasHit())

        edit { it.copy(totalAmount = 600.0, myShare = 200.0, owedToMe = 400.0) }
        sync()

        assertEquals(600.0, number("G"), 0.001)
        assertEquals(400.0, number("J"), 0.001)
        assertDelivered()
    }

    @Test
    fun onlyTheCellsTheUserChangedAreWritten() = runBlocking {
        delivered(split())
        put("D", "Lunch with the team")

        edit { it.copy(category = "Travel") }
        sync()

        assertEquals("Lunch with the team", text("D"))
        assertEquals("Travel", text("F"))
        assertDelivered()
    }

    // ---- Older queue items and unreadable rows

    private suspend fun queuedByAnOlderBuild(change: (Transaction) -> Transaction) {
        tDao.update(com.financeautomation.data.local.entity.TransactionEntity.fromDomain(change(local())))
        qDao.insert(SyncQueueEntity(transactionId = id, operationType = SyncQueueEntity.OP_UPDATE, payloadJson = id))
    }

    @Test
    fun anOlderQueuedEditWithTheSameAmountsIsSent() = runBlocking {
        delivered(split())
        queuedByAnOlderBuild { it.copy(category = "Travel") }

        sync()

        assertEquals("Travel", text("F"))
        assertEquals(200.0, number("J"), 0.001)
        assertDelivered()
    }

    @Test
    fun anOlderQueuedEditWithOtherAmountsIsRefused() = runBlocking {
        delivered(split())
        partlyRepaidOnSheet()
        val before = cells()
        queuedByAnOlderBuild { it.copy(category = "Travel") }

        sync()

        assertRefused(SyncErrorKind.EDIT_SHEET_CHANGED, before)
    }

    @Test
    fun aRowThatCannotBeReadInFullIsLeftAloneAndRetried() = runBlocking {
        delivered(split())
        put("G", "not a number")
        val before = cells()

        edit { it.copy(category = "Travel") }
        sync()

        assertEquals(before, cells())
        assertEquals(SyncQueueEntity.STATUS_PENDING, queue().single().status)
    }

    // ---- Discarding

    @Test
    fun discardingAFriendPaidEntryTakesOutItsExpenseAndDebt() = runBlocking {
        val payId = (repo.logFriendPaid("Riya", 500.0, "Dinner", then) as Result.Success).data
        val expenseId = tDao.rows.keys.single()

        repo.discardQueueItem(queue().single().id)

        assertEquals("Void", tDao.rows.getValue(expenseId).status)
        assertEquals(null, pDao.getByUuid(payId))
        assertDelivered()
    }

    @Test
    fun discardingAnEntryThatMayBePartlyWrittenIsConfirmedFirst() {
        fun item(type: String, planJson: String? = null) =
            SyncQueueEntity(transactionId = "x", operationType = type, payloadJson = "x", planJson = planJson)
        val empty = """{"edits":[],"appends":[],"settled":[]}"""
        val written = """{"edits":[{"tab":"Nov26","row":4,"column":"J","value":1.0}],"appends":[]}"""

        assertTrue(DiscardWarning.isNeeded(item(SyncQueueEntity.OP_CARRY_FORWARD)))
        assertTrue(DiscardWarning.isNeeded(item(SyncQueueEntity.OP_LOG_REPAYMENT, written)))
        assertEquals(false, DiscardWarning.isNeeded(item(SyncQueueEntity.OP_LOG_REPAYMENT, empty)))
        assertEquals(false, DiscardWarning.isNeeded(item(SyncQueueEntity.OP_UPDATE)))
    }

    // ---- Other things that queue an update

    @Test
    fun restoringADeliveredVoidIsNotRefused() = runBlocking {
        delivered(split())
        val original = local()
        assertTrue(repo.voidTransaction(id) is Result.Success)
        sync()
        assertEquals("Void", text("K"))

        assertTrue(repo.updateTransaction(original.copy(syncState = SyncState.PENDING)) is Result.Success)
        sync()

        assertEquals("Pending", text("K"))
        assertEquals("Lunch", text("D"))
        assertEquals(200.0, number("J"), 0.001)
        assertDelivered()
    }

    @Test
    fun aCategoryRenameReachesTheSheetAndLeavesARepaymentThereAlone() = runBlocking {
        delivered(split())
        partlyRepaidOnSheet()

        repo.updateCategoryName("Food & Drink", "Eating Out")
        sync()

        assertEquals("Eating Out", text("F"))
        assertEquals(80.0, number("J"), 0.001)
        assertDelivered()
    }
}
