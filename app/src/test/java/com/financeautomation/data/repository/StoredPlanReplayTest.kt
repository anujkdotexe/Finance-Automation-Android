package com.financeautomation.data.repository

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.core.utils.SyncErrorKind
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
 * A saved accounting plan that is sent again must apply each rupee exactly once. Real repository, executor and Sheets
 * client over one in-memory workbook, an injected clock, a failure after a chosen write, and a second device with its
 * own tables and queue that carries the same sheet in between. Invented data.
 */
class StoredPlanReplayTest {

    private val now = Instant.parse("2026-11-22T10:00:00Z").toEpochMilli()
    private val october = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli()
    private val sheet = FakeSheetApplier(mutableListOf("Oct26", "Nov26", "Config", "Subscriptions", "Trips"))

    private inner class Device {
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val pDao = InMemoryPayableDao()
        private val preferences = mutableMapOf<String, String?>()

        @Suppress("UNCHECKED_CAST")
        private val prefs: PreferencesRepository = Proxy.newProxyInstance(
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

        private val sheets = sheet.client()
        private val lDao = InMemoryLineageDao()

        val repo = TransactionRepositoryImpl(
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

        suspend fun sync() = repo.syncPendingTransactions(userInitiated = true)

        suspend fun queue(): List<SyncQueueEntity> = qDao.getAllQueueFlow().first().sortedBy { it.id }

        suspend fun iOwe(): Double = pDao.getPending().sumOf { it.amountIOwe }
    }

    private val phone = Device()
    private val otherPhone = Device()

    /** The other device carries October into November on the same sheet. */
    private suspend fun carriedElsewhere() {
        otherPhone.repo.carryForward("Nov26", "Oct26")
        otherPhone.sync()
        assertTrue("the other device's carry finished", otherPhone.queue().isEmpty())
    }

    private fun column(letters: String): Int = SheetSchemaConstants.columnIndex(letters) + 1

    private fun put(tab: String, row: Int, letters: String, value: Any) = sheet.setCell(tab, row, column(letters), value)

    private fun text(tab: String, row: Int, letters: String): String = sheet.getCell(tab, row, letters)?.toString().orEmpty()

    private fun amount(tab: String, row: Int, letters: String): Double = text(tab, row, letters).toDouble()

    private fun rows(tab: String): List<Int> = sheet.grid.getValue(tab).keys.filter { it >= 4 }.sorted()

    /** Open amounts of one table across both months, as "tab amount". */
    private fun open(amountColumn: String, statusColumn: String): List<String> = listOf("Oct26", "Nov26").flatMap { tab ->
        rows(tab).filter { text(tab, it, statusColumn) in listOf("Pending", "Partial") }
            .map { "$tab ${amount(tab, it, amountColumn)}" }
    }

    private fun openIOwe() = open("AA", "AC")

    private fun openOwedToMe() = open("J", "K")

    private fun copyRow(tab: String, textColumn: String): Int =
        rows(tab).first { text(tab, it, textColumn).startsWith("[Carried from Oct26]") }

    /** A debt the user owes, on the October tab and on this device. */
    private suspend fun seedIOwe(person: String, owed: Double) {
        val serial = DateSerialUtils.toDateSerial(october)
        listOf<Any>(serial, 0.5, person, "Tickets", owed).forEachIndexed { i, value ->
            sheet.setCell("Oct26", 4, column("W") + i, value)
        }
        put("Oct26", 4, "AC", "Pending")
        put("Oct26", 4, "AF", "pay_oct")
        phone.pDao.upsert(
            PayableEntity(
                uuid = "pay_oct", dateIncurredSerial = serial, timeSerial = 0.5, paidBy = person,
                whatFor = "Tickets", amountIOwe = owed, dateRepaidSerial = null, repaymentStatus = "Pending",
                tabName = "Oct26", sheetRowIndex = 4
            )
        )
    }

    /** A debt owed to the user, on the October tab. */
    private fun seedOwedToMe(person: String, owed: Double) {
        listOf<Any>(
            DateSerialUtils.toDateSerial(october), 0.5, "Cash", "Villa", "", "Travel", owed + 400.0, person, 400.0,
            owed, "Pending"
        ).forEachIndexed { i, value -> sheet.setCell("Oct26", 4, i + 1, value) }
        put("Oct26", 4, "AD", "txn_oct")
    }

    private suspend fun assertFinished() = assertTrue("the entry left the queue: ${phone.queue()}", phone.queue().isEmpty())

    // ---- Repayment, with the debt carried on by another device in between

    @Test
    fun aRepaymentThatNeverReachedTheSheetGoesToTheCarriedCopy() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        sheet.failValueWrite(1, applied = false)
        phone.sync()
        assertTrue(sheet.faultWasHit())
        carriedElsewhere()

        phone.sync()

        assertEquals("Carried Fwd", text("Oct26", 4, "AC"))
        assertEquals(500.0, amount("Oct26", 4, "AA"), 0.001)
        assertEquals(listOf("Nov26 400.0"), openIOwe())
        assertEquals(400.0, phone.iOwe(), 0.001)
        assertFinished()
    }

    @Test
    fun aRepaymentCopiedByTheCarryAfterItWasWrittenIsNotAppliedAgain() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        sheet.failValueWrite(1, applied = true)
        phone.sync()
        carriedElsewhere()

        phone.sync()

        assertEquals("Carried Fwd", text("Oct26", 4, "AC"))
        assertEquals(listOf("Nov26 400.0"), openIOwe())
        assertEquals(400.0, phone.iOwe(), 0.001)
        assertFinished()
    }

    @Test
    fun anOverpaymentStoppedAfterItsNewRowIsFinishedOnTheCopyWithoutASecondRow() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 600.0, null, now)
        // The first write adds the excess row, the second one is the repayment itself
        sheet.failValueWrite(2, applied = false)
        phone.sync()
        carriedElsewhere()

        phone.sync()

        assertEquals("Carried Fwd", text("Oct26", 4, "AC"))
        assertEquals(emptyList<String>(), openIOwe())
        assertEquals("the excess is owed back exactly once", listOf("Nov26 100.0"), openOwedToMe())
        assertEquals(0.0, phone.iOwe(), 0.001)
        assertFinished()
    }

    @Test
    fun aRetargetedRepaymentWhoseReplyIsLostIsNotAppliedTwice() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        sheet.failValueWrite(1, applied = false)
        phone.sync()
        carriedElsewhere()
        // The retry writes to the copy, and that reply is lost as well
        sheet.failValueWrite(1, applied = true)
        phone.sync()
        assertTrue(sheet.faultWasHit())

        phone.sync()

        assertEquals(listOf("Nov26 400.0"), openIOwe())
        assertEquals(500.0, amount("Oct26", 4, "AA"), 0.001)
        assertFinished()
    }

    private fun splitWithAsha() = Transaction(
        id = "txn_split_nov", timestamp = now - 3_600_000L, dateSerial = DateSerialUtils.toDateSerial(now),
        timeSerial = 0.4, account = "Cash", merchant = "Lunch", category = "Food & Drink", totalAmount = 300.0,
        companions = "Asha", myShare = 100.0, owedToMe = 200.0, status = TransactionStatus.PENDING,
        reviewState = ReviewState.CONFIRMED, syncState = SyncState.PENDING
    )

    // ---- The other operations that save a plan

    @Test
    fun aSettlementThatNeverReachedTheDebtGoesToItsCarriedCopyWithOneIncomeRow() = runBlocking {
        seedOwedToMe("Asha", 600.0)
        phone.repo.logCredit("Asha", 250.0, emptyList(), "Cash", "Friend settlement", "Part", now)
        // The first write adds the income row, the second one settles the debt
        sheet.failValueWrite(2, applied = false)
        phone.sync()
        carriedElsewhere()

        phone.sync()

        assertEquals("Carried Fwd", text("Oct26", 4, "K"))
        assertEquals(listOf("Nov26 350.0"), openOwedToMe())
        assertEquals(1, rows("Nov26").count { text("Nov26", it, "T").isNotBlank() })
        assertFinished()
    }

    @Test
    fun aFriendPaidEntryStoppedBeforeItsOffsetOffsetsTheCarriedCopy() = runBlocking {
        seedOwedToMe("Asha", 600.0)
        val payId = (phone.repo.logFriendPaid("Asha", 200.0, "Cab", now) as Result.Success).data
        // Two rows are added first; the third write offsets what Asha owes
        sheet.failValueWrite(3, applied = false)
        phone.sync()
        carriedElsewhere()

        phone.sync()

        assertEquals("Carried Fwd", text("Oct26", 4, "K"))
        assertEquals(listOf("Nov26 400.0"), openOwedToMe())
        assertEquals(1, rows("Nov26").count { text("Nov26", it, "AF") == payId })
        assertFinished()
    }

    @Test
    fun aSplitExpenseStoppedBeforeItsOffsetOffsetsTheCarriedCopyOfTheDebt() = runBlocking {
        seedIOwe("Asha", 300.0)
        val expense = splitWithAsha()
        phone.repo.insertTransaction(expense)
        // The first write adds the expense row, the second one offsets what the user owes Asha
        sheet.failValueWrite(2, applied = false)
        phone.sync()
        carriedElsewhere()

        phone.sync()

        assertEquals("Carried Fwd", text("Oct26", 4, "AC"))
        assertEquals(listOf("Nov26 100.0"), openIOwe())
        assertEquals(1, rows("Nov26").count { text("Nov26", it, "AD") == expense.id })
        assertFinished()
    }

    // ---- Amounts that read as written prove nothing about rows that were never added

    /** A settlement of the whole debt saves its plan and stops before the income row is sent. */
    private suspend fun settlementStoppedBeforeItsIncomeRow() {
        seedOwedToMe("Asha", 600.0)
        phone.repo.logCredit("Asha", 600.0, emptyList(), "Cash", "Friend settlement", "All of it", now)
        sheet.failValueWrite(1, applied = false)
        phone.sync()
        assertTrue(sheet.faultWasHit())
    }

    /** Something else then clears the same debt. */
    private fun clearedByHand() {
        put("Oct26", 4, "J", 0.0)
        put("Oct26", 4, "K", "Settled")
    }

    private fun incomeRows(): Int = rows("Nov26").count { text("Nov26", it, "T").isNotBlank() }

    @Test
    fun aSettlementWhoseIncomeRowWasNeverSentIsNotTakenAsWritten() = runBlocking {
        settlementStoppedBeforeItsIncomeRow()
        clearedByHand()
        val before = wholeSheet()

        phone.sync()

        assertRefusedAsPartlySent(before)
    }

    @Test
    fun aNettingExpenseThatWasNeverSentIsNotMarkedDelivered() = runBlocking {
        seedIOwe("Asha", 300.0)
        val expense = splitWithAsha()
        phone.repo.insertTransaction(expense)
        sheet.failValueWrite(1, applied = false)
        phone.sync()
        assertTrue(sheet.faultWasHit())
        // Something else brings the debt to the amount the offset would have left
        put("Oct26", 4, "AA", 100.0)
        put("Oct26", 4, "AC", "Partial")
        val before = wholeSheet()

        phone.sync()

        assertRefusedAsPartlySent(before)
        assertTrue(phone.tDao.rows.getValue(expense.id).syncState != SyncState.SYNCED.name)
    }

    @Test
    fun anIdReadThatFailsIsRetriedNotJudged() = runBlocking {
        settlementStoppedBeforeItsIncomeRow()
        clearedByHand()
        sheet.failSingleRangeReads = true

        phone.sync()

        assertEquals(SyncQueueEntity.STATUS_PENDING, phone.queue().single().status)

        sheet.failSingleRangeReads = false
        phone.sync()

        assertEquals(SyncErrorKind.PARTLY_SENT.name, phone.queue().single().lastErrorKind)
    }

    /** The whole settlement reaches the sheet and only the last reply is lost. */
    private suspend fun settlementWithItsLastReplyLost() {
        seedOwedToMe("Asha", 600.0)
        phone.repo.logCredit("Asha", 600.0, emptyList(), "Cash", "Friend settlement", "All of it", now)
        sheet.failValueWrite(2, applied = true)
        phone.sync()
        assertTrue(sheet.faultWasHit())
    }

    @Test
    fun aSettlementThatFullyArrivedIsFinishedOnce() = runBlocking {
        settlementWithItsLastReplyLost()

        phone.sync()

        assertEquals(1, incomeRows())
        assertFinished()
    }

    @Test
    fun anIncomeRowFoundOnlyInTheOlderIdColumnStillCounts() = runBlocking {
        settlementWithItsLastReplyLost()
        val row = rows("Nov26").single { text("Nov26", it, "AE").isNotBlank() }
        put("Nov26", row, "AD", text("Nov26", row, "AE"))
        put("Nov26", row, "AE", "")

        phone.sync()

        assertEquals(1, incomeRows())
        assertFinished()
    }

    /** Cuts the row an overpayment added out of November and pastes it into October. */
    private fun moveTheExcessRowToOctober(): String {
        val from = rows("Nov26").single { text("Nov26", it, "AD").isNotBlank() }
        val id = text("Nov26", from, "AD")
        (1..column("AD")).forEach { index ->
            sheet.getCell("Nov26", from, index)?.let { value ->
                sheet.setCell("Oct26", 8, index, value)
                sheet.setCell("Nov26", from, index, "")
            }
        }
        return id
    }

    private fun rowsWithId(id: String): Int =
        listOf("Oct26", "Nov26").sumOf { tab -> rows(tab).count { text(tab, it, "AD") == id } }

    @Test
    fun aDeliveredEntryWhoseNewRowWasMovedIsStillFinished() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 600.0, null, now)
        sheet.failValueWrite(2, applied = true)
        phone.sync()
        val id = moveTheExcessRowToOctober()

        phone.sync()

        assertEquals(1, rowsWithId(id))
        assertFinished()
    }

    @Test
    fun aNewRowMovedBeforeTheRestWasSentIsNotAddedAgain() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 600.0, null, now)
        sheet.failValueWrite(2, applied = false)
        phone.sync()
        val id = moveTheExcessRowToOctober()

        phone.sync()

        assertEquals(1, rowsWithId(id))
        assertEquals(0.0, amount("Oct26", 4, "AA"), 0.001)
        assertFinished()
    }

    // ---- What cannot be proven changes nothing

    private suspend fun assertRefusedAsPartlySent(sheetBefore: String) {
        assertEquals("nothing is written", sheetBefore, wholeSheet())
        val item = phone.queue().first()
        assertEquals(SyncQueueEntity.STATUS_FAILED, item.status)
        assertEquals(SyncErrorKind.PARTLY_SENT.name, item.lastErrorKind)
    }

    private fun wholeSheet(): String =
        listOf("Oct26", "Nov26").joinToString { tab -> rows(tab).joinToString { sheet.getRow(tab, it).toString() } }

    @Test
    fun anAmountChangedBySomethingElseRefusesTheRetry() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        sheet.failValueWrite(1, applied = false)
        phone.sync()
        put("Oct26", 4, "AA", 350.0)
        put("Oct26", 4, "AC", "Partial")
        val before = wholeSheet()

        phone.sync()

        assertRefusedAsPartlySent(before)
    }

    @Test
    fun aCarriedCopyThatWasChangedAgainRefusesTheRetry() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        sheet.failValueWrite(1, applied = false)
        phone.sync()
        carriedElsewhere()
        val copy = copyRow("Nov26", "Z")
        put("Nov26", copy, "AA", 150.0)
        put("Nov26", copy, "AC", "Partial")
        val before = wholeSheet()

        phone.sync()

        assertRefusedAsPartlySent(before)
    }

    @Test
    fun laterRepaymentsWaitBehindARefusedOneUntilItIsDiscarded() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        sheet.failValueWrite(1, applied = false)
        phone.sync()
        put("Oct26", 4, "AA", 350.0)
        put("Oct26", 4, "AC", "Partial")
        phone.sync()
        phone.repo.logRepayment("Riya", 50.0, null, now)

        phone.sync()
        assertEquals(350.0, amount("Oct26", 4, "AA"), 0.001)
        assertEquals(2, phone.queue().size)

        phone.repo.discardQueueItem(phone.queue().first().id)
        phone.sync()

        assertEquals(300.0, amount("Oct26", 4, "AA"), 0.001)
        assertFinished()
    }

    // ---- This device does not create the bad order itself

    @Test
    fun aCarryQueuedBehindAnUnfinishedRepaymentWaitsForIt() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        phone.repo.carryForward("Nov26", "Oct26")
        sheet.failValueWrite(1, applied = false)

        phone.sync()

        assertEquals("the carry has not run", "Pending", text("Oct26", 4, "AC"))
        assertEquals(emptyList<Int>(), rows("Nov26"))

        phone.sync()

        assertEquals("Carried Fwd", text("Oct26", 4, "AC"))
        assertEquals(listOf("Nov26 400.0"), openIOwe())
        assertFinished()
    }

    @Test
    fun anOrdinaryExpenseIsNotHeldBackByAnUnfinishedRepayment() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        sheet.failValueWrite(1, applied = false)
        phone.tDao.insert(
            TransactionEntity(
                id = "txn_plain", timestamp = now - 60_000L, dateSerial = DateSerialUtils.toDateSerial(now),
                timeSerial = 0.4, account = "Cash", merchant = "Tea", category = "Food & Drink", totalAmount = 40.0,
                myShare = 40.0, owedToMe = 0.0, companions = "Personal", status = "Personal",
                syncState = SyncState.PENDING.name, reviewState = ReviewState.CONFIRMED.name
            )
        )
        phone.qDao.insert(
            SyncQueueEntity(transactionId = "txn_plain", operationType = SyncQueueEntity.OP_INSERT, payloadJson = "txn_plain")
        )

        phone.sync()

        assertEquals(1, rows("Nov26").count { text("Nov26", it, "AD") == "txn_plain" })
    }

    // ---- Retries where nothing moved are not hindered

    @Test
    fun anUnsentRepaymentIsSimplySentOnTheRetry() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 100.0, null, now)
        sheet.failValueWrite(1, applied = false)
        phone.sync()

        phone.sync()

        assertEquals(listOf("Oct26 400.0"), openIOwe())
        assertEquals(400.0, phone.iOwe(), 0.001)
        assertFinished()
    }

    @Test
    fun aRepaymentWhoseReplyWasLostIsAppliedOnce() = runBlocking {
        seedIOwe("Riya", 500.0)
        phone.repo.logRepayment("Riya", 600.0, null, now)
        sheet.failValueWrite(2, applied = true)
        phone.sync()

        phone.sync()

        assertEquals(emptyList<String>(), openIOwe())
        assertEquals(listOf("Nov26 100.0"), openOwedToMe())
        assertFinished()
    }

    // ---- A carry sent again

    @Test
    fun aCarryWhoseCopyNoLongerMatchesItsOpenRowAddsNothing() = runBlocking {
        // A carry wrote the copy and stopped; the old row was then repaid in part before the retry
        seedIOwe("Riya", 400.0)
        listOf<Any>(DateSerialUtils.toDateSerial(october), 0.5, "Riya", "[Carried from Oct26] Tickets", 500.0)
            .forEachIndexed { i, value -> sheet.setCell("Nov26", 4, column("W") + i, value) }
        put("Nov26", 4, "AC", "Pending")
        put("Nov26", 4, "AF", "pay_copy")
        val before = wholeSheet()
        phone.repo.carryForward("Nov26", "Oct26")

        phone.sync()

        assertEquals(before, wholeSheet())
        assertEquals(SyncErrorKind.CARRY_CONFLICT.name, phone.queue().single().lastErrorKind)
    }
}
