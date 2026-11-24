package com.financeautomation.money

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.LivingBurn
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.testutil.InMemoryInflowDao
import com.financeautomation.testutil.InMemoryLineageDao
import com.financeautomation.testutil.InMemoryPayableDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * T1: Comprehensive End-to-End Money and Ledger Accounting tests.
 * Validates splits, netting, repayments, voids, updates, and living burn across Room and Google Sheets.
 */
class EndToEndMoneyTest {

    private val kolkataZone = ZoneId.of("Asia/Kolkata")
    private val fixedInstant = Instant.parse("2026-11-22T10:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, kolkataZone)
    private val currentTab = "Nov26"

    private lateinit var fakeSheet: FakeSheetApplier
    private lateinit var sheets: GoogleSheetsClient
    private lateinit var tDao: InMemoryTransactionDao
    private lateinit var qDao: InMemoryQueueDao
    private lateinit var iDao: InMemoryInflowDao
    private lateinit var lDao: InMemoryLineageDao
    private lateinit var pDao: InMemoryPayableDao
    private lateinit var repo: TransactionRepositoryImpl
    private lateinit var logExpenseUseCase: LogExpenseUseCase
    private val splitUseCase = CalculateSplitUseCase(fixedClock)

    private val nowMillis: Long get() = fixedInstant.toEpochMilli()

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

        @Suppress("UNCHECKED_CAST")
        val prefs: PreferencesRepository = Proxy.newProxyInstance(
            PreferencesRepository::class.java.classLoader,
            arrayOf(PreferencesRepository::class.java)
        ) { _, method, args ->
            when {
                method.name == "getGoogleAccountEmailSync" -> "test@example.com"
                method.name == "getPreference" -> preferences[args!![0] as String]
                method.name == "setPreference" -> { preferences[args!![0] as String] = args[1] as String?; null }
                method.name == "getDefaultAccount" -> flowOf("Cash")
                method.name == "getDefaultAccountSync" -> "Cash"
                method.name == "getSpreadsheetIdSync" || method.name == "getSpreadsheetIdForYearSync" -> "test_sheet_id"
                Flow::class.java.isAssignableFrom(method.returnType) -> flowOf<Any?>(null)
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == java.lang.Integer.TYPE -> 0
                else -> null
            }
        } as PreferencesRepository

        val executor = createTestAccountingSyncExecutor(
            sheetsClient = sheets,
            syncQueueDao = qDao,
            lineageDao = lDao,
            transactionDao = tDao,
            inflowDao = iDao,
            payableDao = pDao
        )

        repo = TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            payableDao = pDao,
            inflowDao = iDao,
            lineageDao = lDao,
            googleSheetsClient = sheets,
            preferencesRepository = prefs,
            workbookResolver = null,
            accountingSyncExecutor = executor,
            onTriggerSync = {},
            ioDispatcher = Dispatchers.Unconfined,
            clock = { nowMillis }
        )

        logExpenseUseCase = LogExpenseUseCase(repo, splitUseCase)
    }

    private suspend fun syncAll() {
        repo.syncPendingTransactions(userInitiated = true)
    }

    @Test
    fun test_a_justMeExpense() = runBlocking {
        val res = logExpenseUseCase(
            amount = 111.0,
            merchant = "Asha Stores",
            category = "Food & Drink",
            account = "Cash",
            splitMode = SplitMode.JUST_ME,
            timestamp = nowMillis
        )
        assertTrue(res is Result.Success)
        val txn = (res as Result.Success).data
        assertEquals(111.0, txn.totalAmount, 0.001)
        assertEquals(111.0, txn.myShare, 0.001)
        assertEquals(0.0, txn.owedToMe, 0.001)
        assertEquals(TransactionStatus.PERSONAL, txn.status)
        assertEquals("Personal", txn.companions)

        syncAll()

        // Verify sheet row 4
        val row = fakeSheet.getRow(currentTab, 4)
        assertTrue("Sheet row 4 must be populated", row.isNotEmpty())
        assertEquals("Asha Stores", fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_DESCRIPTION))
        assertEquals(111.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_TOTAL_AMOUNT) as Number).toDouble(), 0.001)
        assertEquals(111.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_MY_SHARE) as Number).toDouble(), 0.001)
        assertEquals(0.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_OWED_TO_ME) as Number).toDouble(), 0.001)
        assertEquals("Personal", fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_STATUS))
    }

    @Test
    fun test_b_splitEquallyWithKabir() = runBlocking {
        val res = logExpenseUseCase(
            amount = 900.0,
            merchant = "Dinner",
            category = "Food & Drink",
            account = "Cash",
            splitMode = SplitMode.SPLIT_EQUALLY,
            companions = listOf("Kabir"),
            timestamp = nowMillis
        )
        assertTrue(res is Result.Success)
        val txn = (res as Result.Success).data
        assertEquals(900.0, txn.totalAmount, 0.001)
        assertEquals(450.0, txn.myShare, 0.001)
        assertEquals(450.0, txn.owedToMe, 0.001)
        assertEquals(TransactionStatus.PENDING, txn.status)
        assertEquals("Kabir", txn.companions)

        syncAll()

        assertEquals(450.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_MY_SHARE) as Number).toDouble(), 0.001)
        assertEquals(450.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_OWED_TO_ME) as Number).toDouble(), 0.001)
        assertEquals("Pending", fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_STATUS))
        assertEquals("Kabir", fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_COMPANIONS))
    }

    @Test
    fun test_c_splitEquallyWithKabirAndRiya() = runBlocking {
        val res = logExpenseUseCase(
            amount = 1000.0,
            merchant = "Groceries",
            category = "Groceries",
            account = "Cash",
            splitMode = SplitMode.SPLIT_EQUALLY,
            companions = listOf("Kabir", "Riya"),
            timestamp = nowMillis
        )
        assertTrue(res is Result.Success)
        val txn = (res as Result.Success).data
        assertEquals(1000.0, txn.totalAmount, 0.001)
        assertEquals(333.33, txn.myShare, 0.001)
        assertEquals(666.67, txn.owedToMe, 0.001)
        assertEquals(TransactionStatus.PENDING, txn.status)
        assertEquals("Kabir, Riya", txn.companions)

        syncAll()

        assertEquals(333.33, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_MY_SHARE) as Number).toDouble(), 0.001)
        assertEquals(666.67, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_OWED_TO_ME) as Number).toDouble(), 0.001)
    }

    @Test
    fun test_d_customSplit() = runBlocking {
        val res = logExpenseUseCase(
            amount = 1000.0,
            merchant = "Cab",
            category = "Transport",
            account = "Cash",
            splitMode = SplitMode.CUSTOM,
            customShare = 300.0,
            companions = listOf("Riya"),
            timestamp = nowMillis
        )
        assertTrue(res is Result.Success)
        val txn = (res as Result.Success).data
        assertEquals(1000.0, txn.totalAmount, 0.001)
        assertEquals(300.0, txn.myShare, 0.001)
        assertEquals(700.0, txn.owedToMe, 0.001)
        assertEquals(TransactionStatus.PENDING, txn.status)

        syncAll()

        assertEquals(300.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_MY_SHARE) as Number).toDouble(), 0.001)
        assertEquals(700.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_OWED_TO_ME) as Number).toDouble(), 0.001)
    }

    @Test
    fun test_e_savingsCategory_spelledSavingNormalizesAndExcludedFromBurn() = runBlocking {
        val res = logExpenseUseCase(
            amount = 2000.0,
            merchant = "Mutual Fund SIP",
            category = "Saving", // Spelled "Saving"
            account = "Cash",
            splitMode = SplitMode.JUST_ME,
            timestamp = nowMillis
        )
        assertTrue("Saving input must not throw and succeed", res is Result.Success)
        val txn = (res as Result.Success).data
        assertEquals("Savings", txn.category)

        syncAll()

        assertEquals("Savings", fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_CATEGORY))

        // Check living burn excludes Savings
        val window = LivingBurn.monthWindow(2026, 11, kolkataZone)
        val burn = LivingBurn.total(listOf(txn), window, emptySet())
        assertEquals(0.0, burn, 0.001)
    }

    @Test
    fun test_f_incomeRefundTransfer_livingBurnComputation() = runBlocking {
        // Expense 1000
        val expRes = logExpenseUseCase(
            amount = 1000.0, merchant = "Shopping", category = "Shopping",
            account = "Cash", splitMode = SplitMode.JUST_ME, timestamp = nowMillis
        )
        assertTrue(expRes is Result.Success)

        // Income 5000
        repo.logCredit(
            settler = "Company", amount = 5000.0, explicitRefs = emptyList(),
            account = "Cash", inflowType = "Income", reason = "Salary", timestamp = nowMillis
        )

        // Refund 100
        repo.logCredit(
            settler = "Merchant", amount = 100.0, explicitRefs = emptyList(),
            account = "Cash", inflowType = "Refund", reason = "Returned shirt", timestamp = nowMillis
        )

        // Self Transfer 250
        repo.logCredit(
            settler = "Self", amount = 250.0, explicitRefs = emptyList(),
            account = "Cash", inflowType = "Self Transfer", reason = "ATM withdraw", timestamp = nowMillis
        )

        syncAll()

        val allTxns = tDao.getAllForYear(0, Long.MAX_VALUE).map { it.toDomain() }
        val allInflows = iDao.getAllForYear(0, Long.MAX_VALUE).map { it.toDomain() }
        val window = LivingBurn.monthWindow(2026, 11, kolkataZone)

        // Living burn = 1000 (My share) - 100 (Refund) = 900. Income and Transfer do not reduce burn.
        val burn = LivingBurn.total(allTxns, window, emptySet(), allInflows)
        assertEquals(900.0, burn, 0.001)
    }

    @Test
    fun test_g_friendSettlement_kabirDoesNotGoNegative() = runBlocking {
        // Kabir owes 450
        val exp = logExpenseUseCase(
            amount = 900.0, merchant = "Lunch", category = "Food & Drink",
            account = "Cash", splitMode = SplitMode.SPLIT_EQUALLY, companions = listOf("Kabir"),
            timestamp = nowMillis
        )
        assertTrue(exp is Result.Success)
        val expTxn = (exp as Result.Success).data
        syncAll()

        // First settlement 450
        val s1 = repo.logCredit(
            settler = "Kabir", amount = 450.0, explicitRefs = listOf(expTxn.id),
            account = "Cash", inflowType = "Friend Settlement", reason = "Lunch share", timestamp = nowMillis
        )
        assertTrue(s1 is Result.Success)
        syncAll()

        val settledTxn = tDao.getById(expTxn.id)!!.toDomain()
        assertEquals(TransactionStatus.SETTLED, settledTxn.status)
        assertEquals(0.0, settledTxn.owedToMe, 0.001)

        // Second settlement 450 from Kabir
        val s2 = repo.logCredit(
            settler = "Kabir", amount = 450.0, explicitRefs = emptyList(),
            account = "Cash", inflowType = "Friend Settlement", reason = "Duplicate", timestamp = nowMillis
        )
        assertTrue(s2 is Result.Success)
        syncAll()

        val checkTxn = tDao.getById(expTxn.id)!!.toDomain()
        assertTrue("Kabir owed must not go negative", checkTxn.owedToMe >= 0.0)
    }

    @Test
    fun test_h_friendPaidForMe_nettedAgainstOpenDebt() = runBlocking {
        // Riya owes me 800
        val exp = logExpenseUseCase(
            amount = 1600.0, merchant = "Concert", category = "Entertainment",
            account = "Cash", splitMode = SplitMode.SPLIT_EQUALLY, companions = listOf("Riya"),
            timestamp = nowMillis
        )
        assertTrue(exp is Result.Success)
        val expTxn = (exp as Result.Success).data
        syncAll()

        // Riya paid for me 300
        val pRes = repo.logPayable(
            creditor = "Riya", amount = 300.0, whatFor = "Dinner",
            timestamp = nowMillis, category = "Food & Drink"
        )
        assertTrue(pRes is Result.Success)
        syncAll()

        // After netting: Riya owes me 500 (800 - 300), my payable is 0 / Repaid
        val updatedDebt = tDao.getById(expTxn.id)!!.toDomain()
        assertEquals(500.0, updatedDebt.owedToMe, 0.001)

        val pendingPayables = pDao.getPending()
        assertEquals(0, pendingPayables.size)

        val allPayables = pDao.getAll()
        val riyaPayable = allPayables.firstOrNull { it.paidBy == "Riya" }
        assertNotNull(riyaPayable)
        assertEquals(0.0, riyaPayable!!.amountIOwe, 0.001)
        assertEquals("Repaid", riyaPayable.repaymentStatus)
    }

    @Test
    fun test_i_repaidFriend_remainingDebtUpdated() = runBlocking {
        // I owe Riya 300
        val pRes = repo.logPayable(
            creditor = "Riya", amount = 300.0, whatFor = "Groceries",
            timestamp = nowMillis, category = "Groceries"
        )
        assertTrue(pRes is Result.Success)
        syncAll()

        // Repay 100
        val rRes = repo.logRepayment(
            creditor = "Riya", amount = 100.0, explicitRef = null, timestamp = nowMillis
        )
        assertTrue(rRes is Result.Success)
        syncAll()

        val payables = pDao.getAll().filter { it.paidBy == "Riya" }
        val remainingOwed = payables.sumOf { it.amountIOwe }
        assertEquals(200.0, remainingOwed, 0.001)
    }

    @Test
    fun test_j_voidExpense_reducesLivingBurn() = runBlocking {
        val exp = logExpenseUseCase(
            amount = 500.0, merchant = "Wrong Entry", category = "Food & Drink",
            account = "Cash", splitMode = SplitMode.JUST_ME, timestamp = nowMillis
        )
        assertTrue(exp is Result.Success)
        val txn = (exp as Result.Success).data
        syncAll()

        val window = LivingBurn.monthWindow(2026, 11, kolkataZone)
        assertEquals(500.0, LivingBurn.total(listOf(txn), window, emptySet()), 0.001)

        val voidRes = repo.voidTransaction(txn.id)
        assertTrue(voidRes is Result.Success)
        syncAll()

        val voidedTxn = tDao.getById(txn.id)!!.toDomain()
        assertEquals(TransactionStatus.VOID, voidedTxn.status)

        val burnAfterVoid = LivingBurn.total(listOf(voidedTxn), window, emptySet())
        assertEquals(0.0, burnAfterVoid, 0.001)

        // Sheet row description marked with [Void]
        val desc = fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_DESCRIPTION)?.toString()
        assertTrue("Description must be marked [Void]", desc?.startsWith("[Void]") == true)
    }

    @Test
    fun test_k_editExpense_updatesSingleSheetRow() = runBlocking {
        val exp = logExpenseUseCase(
            amount = 111.0, merchant = "Stationery", category = "Shopping",
            account = "Cash", splitMode = SplitMode.JUST_ME, timestamp = nowMillis
        )
        assertTrue(exp is Result.Success)
        val txn = (exp as Result.Success).data
        syncAll()

        val debitRowsBefore = fakeSheet.getDebitRows(currentTab)
        assertEquals(1, debitRowsBefore.size)
        assertEquals(111.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_TOTAL_AMOUNT) as Number).toDouble(), 0.001)

        // Update to 120.0
        val updatedTxn = txn.copy(
            totalAmount = 120.0,
            myShare = 120.0
        )
        val editRes = repo.updateTransaction(updatedTxn)
        assertTrue(editRes is Result.Success)
        syncAll()

        val debitRowsAfter = fakeSheet.getDebitRows(currentTab)
        assertEquals("Must still have only 1 debit row in sheet", 1, debitRowsAfter.size)
        assertEquals(120.0, (fakeSheet.getCell(currentTab, 4, SheetSchemaConstants.COL_LETTER_DEBIT_TOTAL_AMOUNT) as Number).toDouble(), 0.001)
    }

    @Test
    fun test_l_totalLivingBurnMatchesHandCalculation() = runBlocking {
        // Just Me 111
        logExpenseUseCase(amount = 111.0, merchant = "Asha Stores", category = "Food & Drink", timestamp = nowMillis)
        // Split Equally 900 (my share 450)
        logExpenseUseCase(amount = 900.0, merchant = "Dinner", category = "Food & Drink", splitMode = SplitMode.SPLIT_EQUALLY, companions = listOf("Kabir"), timestamp = nowMillis)
        // Split Equally 1000 (my share 333.33)
        logExpenseUseCase(amount = 1000.0, merchant = "Groceries", category = "Groceries", splitMode = SplitMode.SPLIT_EQUALLY, companions = listOf("Kabir", "Riya"), timestamp = nowMillis)
        // Custom split 1000 (my share 300)
        logExpenseUseCase(amount = 1000.0, merchant = "Cab", category = "Transport", splitMode = SplitMode.CUSTOM, customShare = 300.0, companions = listOf("Riya"), timestamp = nowMillis)
        // Savings 2000 (excluded from burn)
        logExpenseUseCase(amount = 2000.0, merchant = "SIP", category = "Savings", timestamp = nowMillis)
        // Income 5000 (not burn)
        repo.logCredit(settler = "Office", amount = 5000.0, account = "Cash", inflowType = "Income", reason = "Salary", timestamp = nowMillis)
        // Refund 100 (reduces burn)
        repo.logCredit(settler = "Store", amount = 100.0, account = "Cash", inflowType = "Refund", reason = "Return", timestamp = nowMillis)
        // Transfer 250 (excluded)
        repo.logCredit(settler = "Self", amount = 250.0, account = "Cash", inflowType = "Self Transfer", reason = "Move", timestamp = nowMillis)

        syncAll()

        // Hand calculation:
        // 111.0 + 450.0 + 333.33 + 300.0 = 1194.33
        // Minus refund 100.0 = 1094.33
        val expectedHandBurn = 1094.33

        val allTxns = tDao.getAllForYear(0, Long.MAX_VALUE).map { it.toDomain() }
        val allInflows = iDao.getAllForYear(0, Long.MAX_VALUE).map { it.toDomain() }
        val window = LivingBurn.monthWindow(2026, 11, kolkataZone)
        val computedBurn = LivingBurn.total(allTxns, window, emptySet(), allInflows)

        assertEquals(expectedHandBurn, computedBurn, 0.01)
    }
}
