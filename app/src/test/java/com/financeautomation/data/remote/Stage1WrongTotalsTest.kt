package com.financeautomation.data.remote

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.usecase.BudgetProgressUseCase
import com.financeautomation.domain.usecase.ExportMonthCsvUseCase
import com.financeautomation.domain.usecase.LivingBurn
import com.financeautomation.domain.usecase.TripSummaryUseCase
import com.financeautomation.domain.usecase.YearCloseUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class Stage1WrongTotalsTest {

    private val kolkataZone = ZoneId.of("Asia/Kolkata")

    private fun createTxn(
        id: String,
        timestamp: Long,
        merchant: String,
        category: String,
        totalAmount: Double,
        myShare: Double,
        owedToMe: Double,
        status: TransactionStatus,
        tripTag: String = ""
    ): Transaction = Transaction(
        id = id,
        timestamp = timestamp,
        dateSerial = 46358,
        timeSerial = 0.5,
        account = "HDFC",
        merchant = merchant,
        tripTag = tripTag,
        category = category,
        totalAmount = totalAmount,
        companions = if (owedToMe > 0) "Riya" else "Personal",
        myShare = myShare,
        owedToMe = owedToMe,
        status = status,
        reviewState = ReviewState.CONFIRMED
    )

    // =========================================================================
    // 1C: Trip Totals Formulas (buildTripTotalPaid, buildTripMyShare, buildTripOwedToMe)
    // =========================================================================

    @Test
    fun testTripTotalPaidFormula_excludesCarriedCopiesAndVoid() {
        val formula = DashboardFormulaBuilder.buildTripTotalPaidFormula(year = 2026, row1BasedIndex = 4)
        assertTrue("Must start with '='", formula.startsWith("="))
        assertTrue("Must exclude [Void]*", formula.contains("<>[Void]*"))
        assertTrue("Must exclude [Carried* copies", formula.contains("<>[Carried*"))
        assertTrue("Must exclude Savings", formula.contains("<>Savings"))
        assertTrue("Must exclude Transfer", formula.contains("<>Transfer"))
        assertTrue("Must exclude Refund", formula.contains("<>Refund"))
    }

    @Test
    fun testTripMyShareFormula_excludesCarriedCopiesAndVoid() {
        val formula = DashboardFormulaBuilder.buildTripMyShareFormula(year = 2026, row1BasedIndex = 4)
        assertTrue("Must start with '='", formula.startsWith("="))
        assertTrue("Must exclude [Void]*", formula.contains("<>[Void]*"))
        assertTrue("Must exclude [Carried* copies", formula.contains("<>[Carried*"))
        assertTrue("Must exclude Savings", formula.contains("<>Savings"))
        assertTrue("Must exclude Transfer", formula.contains("<>Transfer"))
        assertTrue("Must exclude Refund", formula.contains("<>Refund"))
    }

    @Test
    fun testTripOwedToMeFormula_requiresStatusPending() {
        val formula = DashboardFormulaBuilder.buildTripOwedToMeFormula(year = 2026, row1BasedIndex = 4)
        assertTrue("Must start with '='", formula.startsWith("="))
        assertTrue("Must filter status Pending so Carried Fwd originals are excluded", formula.contains("K4:K\"), \"Pending\""))
        assertTrue("Must exclude [Void]*", formula.contains("<>[Void]*"))
        assertTrue("Must exclude Savings", formula.contains("<>Savings"))
        assertTrue("Must exclude Transfer", formula.contains("<>Transfer"))
    }

    // =========================================================================
    // 1D: Subscription, Monthly Trend, Category, Chart, Tables, App Totals
    // =========================================================================

    @Test
    fun testSubscriptionPaidThisYearFormula_excludesCarriedCopiesAndVoid() {
        val formula = DashboardFormulaBuilder.buildPaidThisYearFormula(year = 2026, row1BasedIndex = 4)
        assertTrue("Must exclude [Carried* copies", formula.contains("<>[Carried*"))
        assertTrue("Must exclude [Void]*", formula.contains("<>[Void]*"))
    }

    @Test
    fun testSubscriptionLastPaidDateFormula_excludesCarriedCopiesAndVoid() {
        val formula = DashboardFormulaBuilder.buildLastPaidDateFormula(year = 2026, row1BasedIndex = 4)
        assertTrue("Must exclude [Carried* copies", formula.contains("<>[Carried*"))
        assertTrue("Must exclude [Void]*", formula.contains("<>[Void]*"))
    }

    @Test
    fun testSubscriptionPaidThisMonthFormula_excludesCarriedCopiesAndVoid() {
        val formula = SheetProvisioner.buildPaidThisMonthFormula(row1BasedIndex = 4, year = 2026)
        assertTrue("Must exclude [Carried* copies", formula.contains("<>[Carried*"))
        assertTrue("Must exclude [Void]*", formula.contains("<>[Void]*"))
    }

    @Test
    fun testMonthlySummaryOutflowFormula_excludesCarriedCopiesAndVoid() {
        val formula = DashboardFormulaBuilder.buildMonthlySummaryOutflowFormula(rowNumber = 6, year = 2026)
        assertTrue("Must exclude [Carried*", formula.contains("<>[Carried*"))
        assertTrue("Must exclude [Void]*", formula.contains("<>[Void]*"))
    }

    @Test
    fun testCategorySpendFormula_excludesCarriedCopiesAndVoid() {
        val formula = DashboardFormulaBuilder.buildCategorySpendFormula(rowNumber = 6, year = 2026)
        assertTrue("Must exclude [Void]*", formula.contains("<>[Void]*"))
        assertTrue("Must exclude [Carried*", formula.contains("<>[Carried*"))
    }

    @Test
    fun testInsightCategorySpendFormula_excludesCarriedCopies() {
        val formula = DashboardFormulaBuilder.buildInsightCategorySpendFormula(rowNum = 6, year = 2026)
        assertTrue("Must exclude [Carried*", formula.contains("<>[Carried*"))
    }

    @Test
    fun testInsightTripSpendFormula_excludesCarriedCopies() {
        val formula = DashboardFormulaBuilder.buildInsightTripSpendFormula(year = 2026)
        assertTrue("Must exclude [Carried*", formula.contains("<>[Carried*"))
    }

    @Test
    fun testTheyOweMeAndIOwePeopleFormulas_stackCurrentAndNextMonthWithFallback() {
        val theyOweMe = DashboardFormulaBuilder.buildTheyOweMeQueryFormula(year = 2026)
        assertTrue("They Owe Me must query Col11 = 'Pending'", theyOweMe.contains("Col11 = 'Pending'"))
        assertTrue("They Owe Me must reference next month via EDATE", theyOweMe.contains("EDATE(TODAY(), 1)"))

        val iOwePeople = DashboardFormulaBuilder.buildIOwePeopleQueryFormula(year = 2026)
        assertTrue("I Owe People must query Col7 = 'Pending' OR Col7 = 'Partial'", iOwePeople.contains("Col7 = 'Pending' OR Col7 = 'Partial'"))
        assertTrue("I Owe People must reference next month via EDATE", iOwePeople.contains("EDATE(TODAY(), 1)"))
    }

    // =========================================================================
    // 1D: Domain UseCases: LivingBurn, Budgets, TripSummary, CSV, YearClose
    // =========================================================================

    @Test
    fun testLivingBurn_excludesCarriedCopies() {
        val novStart = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, kolkataZone).toInstant().toEpochMilli()
        val novWindow = LivingBurn.monthWindow(2026, 11, kolkataZone)

        val originalTxn = createTxn(
            id = "txn_orig_1",
            timestamp = novStart + 1000,
            merchant = "Dinner",
            category = "Dining Out",
            totalAmount = 1000.0,
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.CARRIED_FWD
        )
        val copyTxn = createTxn(
            id = "txn_copy_1",
            timestamp = novStart + 2000,
            merchant = "[Carried from Nov26] Dinner",
            category = "Dining Out",
            totalAmount = 500.0,
            myShare = 0.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING
        )

        val list = listOf(originalTxn, copyTxn)
        val burn = LivingBurn.total(list, novWindow, emptySet())
        assertEquals("Living burn must count original myShare exactly once (500.0)", 500.0, burn, 0.001)
        assertFalse("Carried copy must not count toward living burn", LivingBurn.counts(copyTxn, novWindow, emptySet()))
    }

    @Test
    fun testBudgetProgress_excludesCarriedCopies() {
        val novStart = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, kolkataZone).toInstant().toEpochMilli()
        val originalTxn = createTxn(
            id = "txn_orig_1",
            timestamp = novStart + 1000,
            merchant = "Dinner",
            category = "Dining Out",
            totalAmount = 1000.0,
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.CARRIED_FWD
        )
        val copyTxn = createTxn(
            id = "txn_copy_1",
            timestamp = novStart + 2000,
            merchant = "[Carried from Nov26] Dinner",
            category = "Dining Out",
            totalAmount = 500.0,
            myShare = 0.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING
        )

        val budget = Budget(category = "Dining Out", monthlyAmount = 1000.0, updatedAt = System.currentTimeMillis())
        val useCase = BudgetProgressUseCase(zone = kolkataZone)
        val progress = useCase.forMonth(
            year = 2026,
            month = 11,
            budgets = listOf(budget),
            transactions = listOf(originalTxn, copyTxn),
            categories = listOf(Category("cat_1", "Dining Out"))
        )
        assertEquals(1, progress.size)
        assertEquals("Spent in budget must be 500.0 (excluding carried copy)", 500.0, progress[0].spent, 0.001)
    }

    @Test
    fun testTripSummary_excludesCarriedCopiesFromTotalPaidAndMyShare_judgesOwedByLiveCopy() {
        val novStart = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, kolkataZone).toInstant().toEpochMilli()
        val originalTripTxn = createTxn(
            id = "txn_trip_orig",
            timestamp = novStart + 1000,
            merchant = "Resort Stay",
            category = "Travel",
            tripTag = "Goa",
            totalAmount = 5220.0,
            myShare = 4320.0,
            owedToMe = 900.0,
            status = TransactionStatus.CARRIED_FWD
        )
        // Dec carried copy after a partial repayment: 200 repaid, remaining 700
        val copyTripTxn = createTxn(
            id = "txn_trip_copy",
            timestamp = novStart + 5000,
            merchant = "[Carried from Nov26] Resort Stay",
            category = "Travel",
            tripTag = "Goa",
            totalAmount = 700.0,
            myShare = 0.0,
            owedToMe = 700.0,
            status = TransactionStatus.PENDING
        )

        val useCase = TripSummaryUseCase()
        val summary = useCase.forTrip("Goa", listOf(originalTripTxn, copyTripTxn))

        assertEquals("Total Paid must count original only (5220.0)", 5220.0, summary.totalPaid, 0.001)
        assertEquals("Net expenditure must be 4320.0", 4320.0, summary.netExpenditure, 0.001)
        assertEquals("Open pending debt must reflect the live copy (700.0)", 700.0, summary.givenPending, 0.001)
        assertEquals("Returned/repaid must reflect (900 - 700 = 200.0)", 200.0, summary.returned, 0.001)
    }

    @Test
    fun testExportMonthCsv_excludesCarriedCopies() {
        val novStart = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, kolkataZone).toInstant().toEpochMilli()
        val originalTxn = createTxn(
            id = "txn_orig_1",
            timestamp = novStart + 1000,
            merchant = "Dinner",
            category = "Dining Out",
            totalAmount = 1000.0,
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.CARRIED_FWD
        )
        val copyTxn = createTxn(
            id = "txn_copy_1",
            timestamp = novStart + 2000,
            merchant = "[Carried from Nov26] Dinner",
            category = "Dining Out",
            totalAmount = 500.0,
            myShare = 0.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING
        )

        // Test the pure build method of ExportMonthCsvUseCase
        val exporter = ExportMonthCsvUseCase(
            transactionRepository = object : com.financeautomation.domain.repository.TransactionRepository {
                override fun getTransactions() = kotlinx.coroutines.flow.emptyFlow<List<Transaction>>()
                override fun getNeedsReview() = kotlinx.coroutines.flow.emptyFlow<List<Transaction>>()
                override suspend fun getTransactionById(id: String) = null
                override suspend fun insertTransaction(transaction: Transaction) = Result.Success(Unit)
                override suspend fun updateTransaction(transaction: Transaction) = Result.Success(Unit)
                override suspend fun voidTransaction(id: String) = Result.Success(Unit)
                override suspend fun discardLocalOnly(id: String, voidIt: Boolean) = Result.Success(Unit)
                override suspend fun undoRecentSave(id: String) = Result.Success(Unit)
                override suspend fun findRecentSimilar(amount: Double, windowMillis: Long) = emptyList<Transaction>()
                override suspend fun syncPendingTransactions() = Result.Success(0)
                override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long) = Result.Success("id")
                override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long, category: String) = Result.Success("id")
                override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long) = Result.Success("id")
                override suspend fun carryForward(targetTab: String, sourceTab: String?) = Result.Success(Unit)
                override suspend fun closeYear(year: Int) = Result.Success(Unit)
                override suspend fun reconcileAllMonths() = Result.Success(0)
                override suspend fun requeueYearForNewWorkbook(year: Int) {}
            },
            inflowRepository = object : com.financeautomation.domain.repository.InflowRepository {
                override fun getAllInflows() = kotlinx.coroutines.flow.emptyFlow<List<Inflow>>()
                override suspend fun getInflowById(id: String) = null
                override suspend fun insertInflow(inflow: Inflow) {}
                override suspend fun voidInflow(id: String) = Result.Success(Unit)
                override suspend fun updateStatus(id: String, status: String) {}
                override fun getInflowsByRange(start: Long, end: Long): Flow<List<Inflow>> = emptyFlow()
            },
            zone = kolkataZone
        )

        val csv = exporter.build(2026, 11, listOf(originalTxn, copyTxn), emptyList())
        assertTrue("CSV must contain original Dinner", csv.contains("Dinner"))
        assertFalse("CSV must not contain carried copy [Carried from Nov26]", csv.contains("[Carried from Nov26]"))
    }

    @Test
    fun testYearClose_countsPendingDebtsAndExcludesCarriedFwdOriginals() {
        val originalDebit = LedgerDebit(
            tab = "Nov26",
            row = 4,
            uuid = "txn_nov",
            dateSerial = 46358,
            timeSerial = 0.5,
            account = "HDFC",
            item = "Dinner",
            tripTag = null,
            category = "Dining Out",
            total = 1000.0,
            companions = "Riya",
            myShare = 500.0,
            owed = 500.0,
            status = "Carried Fwd"
        )
        val copyDebit = LedgerDebit(
            tab = "Dec26",
            row = 4,
            uuid = "txn_dec",
            dateSerial = 46358,
            timeSerial = 0.5,
            account = "HDFC",
            item = "[Carried from Nov26] Dinner",
            tripTag = null,
            category = "Dining Out",
            total = 500.0,
            companions = "Riya",
            myShare = 0.0,
            owed = 500.0,
            status = "Pending"
        )

        val useCase = YearCloseUseCase()
        val statement = useCase.invoke(2026, listOf(originalDebit, copyDebit), emptyList())

        assertEquals("Year close must compile exactly 1 pending receivable", 1, statement.receivableCount)
        assertTrue("Statement must include live Dec copy", statement.jsonContent.contains("\"sourceSheet\": \"Dec26\""))
        assertFalse("Statement must not include Carried Fwd original", statement.jsonContent.contains("\"sourceSheet\": \"Nov26\""))
    }

    // =========================================================================
    // 1A: Future Month Key Calculation Guard
    // =========================================================================

    @Test
    fun testMonthKeyComparison_futureMonthDetection() {
        val nov26Key = AccountingSyncExecutor.parseMonthKey("Nov26")
        val dec26Key = AccountingSyncExecutor.parseMonthKey("Dec26")
        val jan27Key = AccountingSyncExecutor.parseMonthKey("Jan27")

        assertEquals(2026 * 12 + 10, nov26Key)
        assertEquals(2026 * 12 + 11, dec26Key)
        assertEquals(2027 * 12 + 0, jan27Key)

        assertTrue("Dec26 must be strictly greater than Nov26", dec26Key!! > nov26Key!!)
        assertTrue("Jan27 must be strictly greater than Dec26", jan27Key!! > dec26Key)
    }
}
