package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.InflowRepository
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.financeautomation.data.local.entity.SyncQueueEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeTransactionRepo : TransactionRepository {
        val txns = MutableStateFlow<List<Transaction>>(emptyList())
        val voidCalls = mutableListOf<String>()
        val undoRecentSaveCalls = mutableListOf<String>()
        val updateCalls = mutableListOf<Transaction>()
        var syncCalls = 0

        override fun getTransactions(): Flow<List<Transaction>> = txns
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = txns.value.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            txns.value = txns.value + transaction
            return Result.Success(Unit)
        }
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            updateCalls.add(transaction)
            txns.value = txns.value.map { if (it.id == transaction.id) transaction else it }
            return Result.Success(Unit)
        }
        override suspend fun voidTransaction(id: String): Result<Unit> {
            voidCalls.add(id)
            return Result.Success(Unit)
        }
        override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> = Result.Success(Unit)
        override suspend fun undoRecentSave(id: String): Result<Unit> {
            undoRecentSaveCalls.add(id)
            return Result.Success(Unit)
        }
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> {
            syncCalls++
            return Result.Success(1)
        }
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
    }

    private class FakeInflowRepo : InflowRepository {
        val inflows = MutableStateFlow<List<Inflow>>(emptyList())
        val voidCalls = mutableListOf<String>()

        override fun getInflowsByRange(start: Long, end: Long): Flow<List<Inflow>> =
            flowOf(inflows.value.filter { it.timestamp in start..end })

        override fun getAllInflows(): Flow<List<Inflow>> = inflows

        override suspend fun getInflowById(id: String): Inflow? =
            inflows.value.find { it.id == id }

        override suspend fun insertInflow(inflow: Inflow) {
            inflows.value = inflows.value + inflow
        }

        override suspend fun updateStatus(id: String, status: String) {
            inflows.value = inflows.value.map { if (it.id == id) it.copy(status = status) else it }
        }

        override suspend fun voidInflow(id: String): Result<Unit> {
            voidCalls.add(id)
            updateStatus(id, "VOID")
            return Result.Success(Unit)
        }
    }

    @Test
    fun testGrouping_ordersNewestFirst_andCalculatesDayTotals() = runTest {
        val tRepo = FakeTransactionRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")

        val day1Cal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 5)
            set(Calendar.HOUR_OF_DAY, 10)
        }
        val day2Cal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 10)
            set(Calendar.HOUR_OF_DAY, 14)
        }

        val txn1 = Transaction(
            id = "t1",
            timestamp = day1Cal.timeInMillis,
            dateSerial = 46330,
            timeSerial = 0.4,
            account = "HDFC",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 200.0,
            companions = "Alice",
            myShare = 100.0,
            owedToMe = 100.0,
            status = TransactionStatus.PENDING
        )
        val txn2 = Transaction(
            id = "t2",
            timestamp = day1Cal.timeInMillis + 3600_000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Bakery",
            category = "Food & Drink",
            totalAmount = 300.0,
            companions = "Personal",
            myShare = 300.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        )
        val txn3 = Transaction(
            id = "t3",
            timestamp = day2Cal.timeInMillis,
            dateSerial = 46335,
            timeSerial = 0.6,
            account = "SBI",
            merchant = "Books",
            category = "Shopping",
            totalAmount = 500.0,
            companions = "Personal",
            myShare = 500.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        )

        tRepo.insertTransaction(txn1)
        tRepo.insertTransaction(txn2)
        tRepo.insertTransaction(txn3)

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.groupedTransactions.size)

        // Day 2 (Day 10) should be first (newest first)
        val firstGroup = state.groupedTransactions[0]
        assertEquals(500.0, firstGroup.dayTotalOut, 0.001)
        assertEquals(500.0, firstGroup.dayMyShare, 0.001)
        assertEquals(1, firstGroup.transactions.size)
        assertEquals("t3", firstGroup.transactions[0].id)

        // Day 1 (Day 5) should be second
        val secondGroup = state.groupedTransactions[1]
        assertEquals(500.0, secondGroup.dayTotalOut, 0.001)
        assertEquals(400.0, secondGroup.dayMyShare, 0.001)
        assertEquals(2, secondGroup.transactions.size)
    }

    @Test
    fun testTotals_andVoidExcluded() = runTest {
        val tRepo = FakeTransactionRepo()
        val now = System.currentTimeMillis()

        val activeTxn = Transaction(
            id = "t_active",
            timestamp = now,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Electronics",
            category = "Shopping",
            totalAmount = 1000.0,
            companions = "Alice",
            myShare = 600.0,
            owedToMe = 400.0,
            status = TransactionStatus.PENDING
        )
        val voidTxn = Transaction(
            id = "t_void",
            timestamp = now,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "[Void] Mistake",
            category = "Shopping",
            totalAmount = 5000.0,
            companions = "Personal",
            myShare = 5000.0,
            owedToMe = 0.0,
            status = TransactionStatus.VOID
        )

        tRepo.insertTransaction(activeTxn)
        tRepo.insertTransaction(voidTxn)

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        val state = vm.uiState.value
        // Month totals exclude void
        assertEquals(1000.0, state.totalOut, 0.001)
        assertEquals(600.0, state.myShare, 0.001)

        // Grouped list excludes void transactions
        val allTxnsInGroups = state.groupedTransactions.flatMap { it.transactions }
        assertEquals(1, allTxnsInGroups.size)
        assertEquals("t_active", allTxnsInGroups[0].id)
    }

    @Test
    fun testSearch_matchesMerchantCompanionsCategory() = runTest {
        val tRepo = FakeTransactionRepo()
        val now = System.currentTimeMillis()

        tRepo.insertTransaction(
            Transaction(
                id = "t_tokai",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "HDFC",
                merchant = "Blue Tokai Coffee",
                category = "Food & Drink",
                totalAmount = 350.0,
                companions = "Personal",
                myShare = 350.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            )
        )
        tRepo.insertTransaction(
            Transaction(
                id = "t_rohan",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "HDFC",
                merchant = "Dinner",
                category = "Food & Drink",
                totalAmount = 1200.0,
                companions = "Rohan, Priya",
                myShare = 400.0,
                owedToMe = 800.0,
                status = TransactionStatus.PENDING
            )
        )
        tRepo.insertTransaction(
            Transaction(
                id = "t_groceries",
                timestamp = now,
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "Cash",
                merchant = "Supermarket",
                category = "Groceries",
                totalAmount = 800.0,
                companions = "Personal",
                myShare = 800.0,
                owedToMe = 0.0,
                status = TransactionStatus.SETTLED
            )
        )

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // Search merchant
        vm.setSearchQuery("Tokai")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.groupedTransactions.flatMap { it.transactions }.size)
        assertEquals("t_tokai", vm.uiState.value.groupedTransactions.flatMap { it.transactions }[0].id)

        // Search companions
        vm.setSearchQuery("Rohan")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.groupedTransactions.flatMap { it.transactions }.size)
        assertEquals("t_rohan", vm.uiState.value.groupedTransactions.flatMap { it.transactions }[0].id)

        // Search category
        vm.setSearchQuery("Groceries")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.groupedTransactions.flatMap { it.transactions }.size)
        assertEquals("t_groceries", vm.uiState.value.groupedTransactions.flatMap { it.transactions }[0].id)
    }

    @Test
    fun testFilters_categoryAccountPersonTripTagStatus() = runTest {
        val tRepo = FakeTransactionRepo()
        val now = System.currentTimeMillis()

        val txn1 = Transaction(
            id = "t1",
            timestamp = now,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Uber",
            category = "Transport",
            totalAmount = 400.0,
            companions = "Alice",
            myShare = 200.0,
            owedToMe = 200.0,
            tripTag = "Goa Trip",
            status = TransactionStatus.PENDING,
            syncState = SyncState.PENDING,
            reviewState = ReviewState.NEEDS_REVIEW
        )
        val txn2 = Transaction(
            id = "t2",
            timestamp = now,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Flight",
            category = "Travel",
            totalAmount = 4500.0,
            companions = "Personal",
            myShare = 4500.0,
            owedToMe = 0.0,
            tripTag = "Goa Trip",
            status = TransactionStatus.SETTLED,
            syncState = SyncState.SYNCED,
            reviewState = ReviewState.CONFIRMED
        )
        tRepo.insertTransaction(txn1)
        tRepo.insertTransaction(txn2)

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // Filter category
        vm.setFilter(category = "Transport")
        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // Filter account
        vm.setFilter(account = "SBI")
        advanceUntilIdle()
        assertEquals(listOf("t2"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // Filter person
        vm.setFilter(person = "Alice")
        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // Filter trip tag
        vm.setFilter(tripTag = "Goa Trip")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.groupedTransactions.flatMap { it.transactions }.size)

        // Filter status "Pending" now means waiting to sync (pending queue item, not under review)
        vm.setFilter(status = "Pending")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // Filter status "Synced"
        vm.setFilter(status = "Synced")
        advanceUntilIdle()
        assertEquals(listOf("t2"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // Filter status "Needs review"
        vm.setFilter(status = "Needs review")
        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })
    }

    private fun baseTxn(id: String, reviewState: ReviewState = ReviewState.CONFIRMED) = Transaction(
        id = id, timestamp = System.currentTimeMillis(), dateSerial = 46330, timeSerial = 0.5,
        account = "HDFC", merchant = "M $id", category = "Food & Drink", totalAmount = 100.0,
        companions = "Personal", myShare = 100.0, owedToMe = 0.0,
        status = TransactionStatus.PERSONAL, reviewState = reviewState
    )

    private fun queueItem(id: Long, txnId: String, status: String, retries: Int = 0) =
        SyncQueueEntity(
            id = id, transactionId = txnId, operationType = SyncQueueEntity.OP_INSERT,
            payloadJson = txnId, status = status, retryCount = retries
        )

    @Test
    fun testRowStatus_eachStatusAndFilter() = runTest {
        val tRepo = FakeTransactionRepo()
        tRepo.insertTransaction(baseTxn("t_failed", ReviewState.NEEDS_REVIEW))
        tRepo.insertTransaction(baseTxn("t_review", ReviewState.NEEDS_REVIEW))
        tRepo.insertTransaction(baseTxn("t_wait"))
        tRepo.insertTransaction(baseTxn("t_synced"))
        val qDao = StubSyncQueueDao(
            listOf(
                queueItem(1, "t_failed", SyncQueueEntity.STATUS_FAILED, 8),
                queueItem(2, "t_wait", SyncQueueEntity.STATUS_PENDING)
            )
        )
        val vm = TransactionsViewModel(tRepo, { qDao }, testDispatcher)
        advanceUntilIdle()

        val statuses = vm.uiState.value.rowStatuses
        assertEquals(RowStatus.FAILED, statuses["t_failed"])
        assertEquals(RowStatus.NEEDS_REVIEW, statuses["t_review"])
        assertEquals(RowStatus.WAITING_TO_SYNC, statuses["t_wait"])
        assertEquals(RowStatus.SYNCED, statuses["t_synced"])

        for ((filter, expected) in listOf(
            "failed" to "t_failed", "needs review" to "t_review", "pending" to "t_wait", "synced" to "t_synced"
        )) {
            vm.setFilter(status = filter)
            advanceUntilIdle()
            assertEquals(listOf(expected), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })
        }
    }

    @Test
    fun testRowStatus_priorityOrder() {
        val review = baseTxn("x", ReviewState.NEEDS_REVIEW)
        assertEquals(RowStatus.FAILED, TransactionsViewModel.computeRowStatus(review, QueueTransactionIds(setOf("x"), setOf("x"))))
        assertEquals(RowStatus.NEEDS_REVIEW, TransactionsViewModel.computeRowStatus(review, QueueTransactionIds(emptySet(), setOf("x"))))
        val confirmed = baseTxn("x")
        assertEquals(RowStatus.WAITING_TO_SYNC, TransactionsViewModel.computeRowStatus(confirmed, QueueTransactionIds(emptySet(), setOf("x"))))
        assertEquals(RowStatus.SYNCED, TransactionsViewModel.computeRowStatus(confirmed, QueueTransactionIds()))
    }

    @Test
    fun testSettlementStatusStaysSeparateFromRowStatus() = runTest {
        val tRepo = FakeTransactionRepo()
        tRepo.insertTransaction(baseTxn("t_settled").copy(status = TransactionStatus.SETTLED))
        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        val shown = vm.uiState.value.groupedTransactions.flatMap { it.transactions }.single()
        assertEquals(TransactionStatus.SETTLED, shown.status)
        assertEquals(RowStatus.SYNCED, vm.uiState.value.rowStatuses["t_settled"])
    }

    @Test
    fun testSearchScope_thisMonthOnlyVersusAllMonths() = runTest {
        val tRepo = FakeTransactionRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val thisMonth = Calendar.getInstance(kolkataTz).apply { set(Calendar.DAY_OF_MONTH, 2); set(Calendar.HOUR_OF_DAY, 12) }
        val lastMonth = Calendar.getInstance(kolkataTz).apply { add(Calendar.MONTH, -1); set(Calendar.DAY_OF_MONTH, 20); set(Calendar.HOUR_OF_DAY, 12) }
        val twoMonthsAgo = Calendar.getInstance(kolkataTz).apply { add(Calendar.MONTH, -2); set(Calendar.DAY_OF_MONTH, 9); set(Calendar.HOUR_OF_DAY, 12) }

        tRepo.insertTransaction(baseTxn("now").copy(merchant = "Cafe Now", timestamp = thisMonth.timeInMillis, totalAmount = 100.0, myShare = 100.0))
        tRepo.insertTransaction(baseTxn("last").copy(merchant = "Cafe Last", timestamp = lastMonth.timeInMillis, totalAmount = 200.0, myShare = 150.0))
        tRepo.insertTransaction(baseTxn("old").copy(merchant = "Cafe Old", timestamp = twoMonthsAgo.timeInMillis, totalAmount = 300.0, myShare = 300.0))
        tRepo.insertTransaction(baseTxn("other").copy(merchant = "Bookshop", timestamp = lastMonth.timeInMillis, totalAmount = 999.0, myShare = 999.0))

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // Default scope: this month only
        vm.setSearchQuery("cafe")
        advanceUntilIdle()
        assertEquals(SearchScope.THIS_MONTH, vm.uiState.value.searchScope)
        assertEquals(listOf("now"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })
        assertEquals(100.0, vm.uiState.value.totalOut, 0.001)

        // All months: month filter ignored, newest first across months, totals are the results
        vm.setSearchScope(SearchScope.ALL_MONTHS)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(SearchScope.ALL_MONTHS, state.searchScope)
        assertEquals(listOf("now", "last", "old"), state.groupedTransactions.flatMap { it.transactions }.map { it.id })
        assertEquals(3, state.groupedTransactions.size)
        assertEquals(600.0, state.totalOut, 0.001)
        assertEquals(550.0, state.myShare, 0.001)

        // A blank query in all-months scope falls back to the selected month
        vm.setSearchQuery("")
        advanceUntilIdle()
        assertEquals(listOf("now"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })
        assertEquals(100.0, vm.uiState.value.totalOut, 0.001)
    }

    @Test
    fun testMonthTotal_andChipFilters_All_Upi_Card_Cash_NeedsReview() = runTest {
        val tRepo = FakeTransactionRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 15)
            set(Calendar.HOUR_OF_DAY, 12)
        }
        val ts = nowCal.timeInMillis

        val tUpi = baseTxn("t_upi").copy(
            account = "GPay UPI",
            merchant = "Chai Point",
            totalAmount = 200.0,
            myShare = 200.0,
            timestamp = ts
        )
        val tCard = baseTxn("t_card").copy(
            account = "HDFC Card",
            merchant = "Zara",
            totalAmount = 500.0,
            myShare = 500.0,
            timestamp = ts
        )
        val tCash = baseTxn("t_cash").copy(
            account = "Cash",
            merchant = "Vegetables",
            totalAmount = 100.0,
            myShare = 100.0,
            timestamp = ts
        )
        val tReview = baseTxn("t_review", ReviewState.NEEDS_REVIEW).copy(
            account = "Other",
            merchant = "Mystery Store",
            totalAmount = 400.0,
            myShare = 400.0,
            timestamp = ts
        )

        tRepo.insertTransaction(tUpi)
        tRepo.insertTransaction(tCard)
        tRepo.insertTransaction(tCash)
        tRepo.insertTransaction(tReview)

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // 1. All (no filter) -> month total = 1200.0
        assertEquals(1200.0, vm.uiState.value.totalOut, 0.001)
        assertEquals(4, vm.uiState.value.groupedTransactions.flatMap { it.transactions }.size)

        // 2. UPI filter chip
        vm.setFilter(TransactionFilter(account = "UPI"))
        advanceUntilIdle()
        assertEquals(1200.0, vm.uiState.value.totalOut, 0.001)
        assertEquals(listOf("t_upi"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // 3. Card filter chip
        vm.setFilter(TransactionFilter(account = "Card"))
        advanceUntilIdle()
        assertEquals(1200.0, vm.uiState.value.totalOut, 0.001)
        assertEquals(listOf("t_card"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // 4. Cash filter chip
        vm.setFilter(TransactionFilter(account = "Cash"))
        advanceUntilIdle()
        assertEquals(1200.0, vm.uiState.value.totalOut, 0.001)
        assertEquals(listOf("t_cash"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // 5. Needs Review filter chip
        vm.setFilter(TransactionFilter(status = "Needs Review"))
        advanceUntilIdle()
        assertEquals(1200.0, vm.uiState.value.totalOut, 0.001)
        assertEquals(listOf("t_review"), vm.uiState.value.groupedTransactions.flatMap { it.transactions }.map { it.id })

        // 6. Clear filter back to All
        vm.clearFilter()
        advanceUntilIdle()
        assertEquals(1200.0, vm.uiState.value.totalOut, 0.001)
        assertEquals(4, vm.uiState.value.groupedTransactions.flatMap { it.transactions }.size)
    }

    @Test
    fun testMergedList_ordersExpensesAndInflowsNewestFirstInterleaved() = runTest {
        val tRepo = FakeTransactionRepo()
        val iRepo = FakeInflowRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 10)
            set(Calendar.HOUR_OF_DAY, 10)
        }
        val t1 = cal.timeInMillis
        val t2 = t1 + 3600_000L // 1 hour later
        val t3 = t2 + 3600_000L // 2 hours later

        val txnEarly = baseTxn("t_early").copy(timestamp = t1, totalAmount = 100.0)
        val inflowMid = Inflow(
            id = "inf_mid",
            timestamp = t2,
            amount = 500.0,
            account = "HDFC",
            inflowType = "Salary",
            source = "Employer",
            note = "Monthly salary"
        )
        val txnLate = baseTxn("t_late").copy(timestamp = t3, totalAmount = 200.0)

        tRepo.insertTransaction(txnEarly)
        tRepo.insertTransaction(txnLate)
        iRepo.insertInflow(inflowMid)

        val vm = TransactionsViewModel(tRepo, inflowRepository = iRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        val groups = vm.uiState.value.groupedTransactions
        assertEquals(1, groups.size)
        val items = groups[0].items
        assertEquals(3, items.size)
        // Should be ordered newest first: txnLate (t3), inflowMid (t2), txnEarly (t1)
        assertEquals("t_late", items[0].id)
        assertTrue(items[0] is TransactionListItem.ExpenseItem)
        assertEquals("inf_mid", items[1].id)
        assertTrue(items[1] is TransactionListItem.IncomeItem)
        assertEquals("t_early", items[2].id)
        assertTrue(items[2] is TransactionListItem.ExpenseItem)
    }

    @Test
    fun testMonthAndSearchFiltering_appliesToInflows() = runTest {
        val tRepo = FakeTransactionRepo()
        val iRepo = FakeInflowRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 15)
            set(Calendar.HOUR_OF_DAY, 12)
        }
        val ts = nowCal.timeInMillis

        val infSalary = Inflow(
            id = "inf_sal",
            timestamp = ts,
            amount = 50000.0,
            account = "HDFC",
            inflowType = "Salary",
            source = "Tech Corp",
            note = "Monthly salary"
        )
        val infRefund = Inflow(
            id = "inf_ref",
            timestamp = ts + 1000L,
            amount = 1200.0,
            account = "GPay",
            inflowType = "Refund",
            source = "Amazon",
            note = "Returned mouse"
        )

        iRepo.insertInflow(infSalary)
        iRepo.insertInflow(infRefund)

        val vm = TransactionsViewModel(tRepo, inflowRepository = iRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // Both in current month
        val group = vm.uiState.value.groupedTransactions
        assertEquals(2, group.flatMap { it.items }.size)

        // Search for "Amazon"
        vm.setSearchQuery("Amazon")
        advanceUntilIdle()
        val searchResults = vm.uiState.value.groupedTransactions.flatMap { it.items }
        assertEquals(1, searchResults.size)
        assertEquals("inf_ref", searchResults[0].id)

        // Clear search
        vm.setSearchQuery("")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.groupedTransactions.flatMap { it.items }.size)

        // Previous month offset - should be empty
        vm.previousMonth()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.groupedTransactions.isEmpty())
    }

    @Test
    fun testVoidInflow_callsRepositoryWithCorrectId() = runTest {
        val tRepo = FakeTransactionRepo()
        val iRepo = FakeInflowRepo()
        val inf = Inflow(
            id = "inf_test_void",
            timestamp = System.currentTimeMillis(),
            amount = 1000.0,
            account = "HDFC",
            inflowType = "Income",
            source = "Client",
            note = "Consulting fee"
        )
        iRepo.insertInflow(inf)

        val vm = TransactionsViewModel(tRepo, inflowRepository = iRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        var callbackCalled = false
        var callbackSuccess = false
        vm.voidInflow("inf_test_void") { success ->
            callbackCalled = true
            callbackSuccess = success
        }
        advanceUntilIdle()

        assertTrue(callbackCalled)
        assertTrue(callbackSuccess)
        assertEquals(listOf("inf_test_void"), iRepo.voidCalls)
    }

    @Test
    fun testVoidInflowsHiddenByDefault_shownWhenFilterIsVoid() = runTest {
        val tRepo = FakeTransactionRepo()
        val iRepo = FakeInflowRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 15)
        }
        val ts = nowCal.timeInMillis

        val infActive = Inflow(
            id = "inf_active",
            timestamp = ts,
            amount = 2000.0,
            account = "HDFC",
            inflowType = "Income",
            source = "Freelance",
            note = "Active project",
            status = "CONFIRMED"
        )
        val infVoid = Inflow(
            id = "inf_void",
            timestamp = ts + 1000L,
            amount = 1500.0,
            account = "HDFC",
            inflowType = "Refund",
            source = "Vendor",
            note = "Accidental refund",
            status = "VOID"
        )

        iRepo.insertInflow(infActive)
        iRepo.insertInflow(infVoid)

        val vm = TransactionsViewModel(tRepo, inflowRepository = iRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // By default, VOID inflow should be hidden
        val defaultItems = vm.uiState.value.groupedTransactions.flatMap { it.items }
        assertEquals(1, defaultItems.size)
        assertEquals("inf_active", defaultItems[0].id)

        // Filter by Void status
        vm.setFilter(TransactionFilter(status = "Void"))
        advanceUntilIdle()

        val voidItems = vm.uiState.value.groupedTransactions.flatMap { it.items }
        assertEquals(1, voidItems.size)
        assertEquals("inf_void", voidItems[0].id)
    }

    @Test
    fun testCategoryAndTypeFiltersFromRoute_andUnknownCategoryIgnored() = runTest {
        val tRepo = FakeTransactionRepo()
        val iRepo = FakeInflowRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 15)
        }
        val ts = nowCal.timeInMillis

        val txnGroceries = Transaction(
            id = "t_groc",
            timestamp = ts,
            dateSerial = 46330,
            timeSerial = 0.4,
            totalAmount = 500.0,
            myShare = 500.0,
            owedToMe = 0.0,
            category = "Groceries",
            account = "UPI",
            merchant = "Store",
            status = TransactionStatus.SETTLED
        )
        val txnSavings = Transaction(
            id = "t_sav",
            timestamp = ts + 1000L,
            dateSerial = 46330,
            timeSerial = 0.4,
            totalAmount = 2000.0,
            myShare = 2000.0,
            owedToMe = 0.0,
            category = "Savings",
            account = "Bank",
            merchant = "SIP",
            status = TransactionStatus.SETTLED
        )
        val txnTransfer = Transaction(
            id = "t_trans",
            timestamp = ts + 2000L,
            dateSerial = 46330,
            timeSerial = 0.4,
            totalAmount = 1000.0,
            myShare = 1000.0,
            owedToMe = 0.0,
            category = "Transfer",
            account = "Bank",
            merchant = "Self",
            status = TransactionStatus.SETTLED
        )

        tRepo.insertTransaction(txnGroceries)
        tRepo.insertTransaction(txnSavings)
        tRepo.insertTransaction(txnTransfer)

        val vm = TransactionsViewModel(tRepo, inflowRepository = iRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // 1. Initial full month total (all 3 transactions = 3500)
        assertEquals(3500.0, vm.uiState.value.totalOut, 0.01)

        // 2. Set Category filter: Groceries
        vm.setCategoryFilter("Groceries")
        advanceUntilIdle()
        assertEquals("Groceries", vm.uiState.value.activeFilter.category)
        assertEquals(500.0, vm.uiState.value.totalOut, 0.01)
        val grocItems = vm.uiState.value.groupedTransactions.flatMap { it.items }
        assertEquals(1, grocItems.size)
        assertEquals("t_groc", grocItems[0].id)

        // 3. Unknown category is ignored (filter remains unchanged)
        vm.setCategoryFilter("HackedUnknownCategory123")
        advanceUntilIdle()
        assertEquals("Groceries", vm.uiState.value.activeFilter.category)
        assertEquals(500.0, vm.uiState.value.totalOut, 0.01)

        // 4. Type filter: living_burn (excludes Savings and Transfer, keeps Groceries = 500)
        vm.setTypeFilter("living_burn")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.activeFilter.excludeSavingsAndIgnored)
        assertEquals(500.0, vm.uiState.value.totalOut, 0.01)
        val livingItems = vm.uiState.value.groupedTransactions.flatMap { it.items }
        assertEquals(1, livingItems.size)
        assertEquals("t_groc", livingItems[0].id)

        // 5. Clear filter: resets header total to full month total (3500)
        vm.clearFilter()
        advanceUntilIdle()
        assertEquals(3500.0, vm.uiState.value.totalOut, 0.01)
        assertEquals(3, vm.uiState.value.groupedTransactions.flatMap { it.items }.size)
    }

    @Test
    fun testTypeFilter_incomeDisplaysOnlyInflows() = runTest {
        val tRepo = FakeTransactionRepo()
        val iRepo = FakeInflowRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 15)
        }
        val ts = nowCal.timeInMillis

        val txn = Transaction(
            id = "t_expense",
            timestamp = ts,
            dateSerial = 46330,
            timeSerial = 0.4,
            totalAmount = 300.0,
            myShare = 300.0,
            owedToMe = 0.0,
            category = "Food & Drink",
            account = "UPI",
            merchant = "Cafe",
            status = TransactionStatus.SETTLED
        )
        val inf = Inflow(
            id = "inf_salary",
            timestamp = ts + 500L,
            amount = 50000.0,
            account = "HDFC",
            inflowType = "Income",
            source = "Employer",
            note = "",
            status = "CONFIRMED"
        )

        tRepo.insertTransaction(txn)
        iRepo.insertInflow(inf)

        val vm = TransactionsViewModel(tRepo, inflowRepository = iRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // Filter to income type
        vm.setTypeFilter("income")
        advanceUntilIdle()

        val items = vm.uiState.value.groupedTransactions.flatMap { it.items }
        assertEquals(1, items.size)
        assertEquals("inf_salary", items[0].id)
    }

    @Test
    fun testTransactionsMonthTotal_equalsLivingBurn_withSplitTransactions() = runTest {
        val tRepo = FakeTransactionRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz).apply {
            set(Calendar.DAY_OF_MONTH, 10)
        }
        val ts = nowCal.timeInMillis

        val splitTxn = Transaction(
            id = "t_split",
            timestamp = ts,
            dateSerial = 46330,
            timeSerial = 0.4,
            account = "UPI",
            merchant = "Dinner",
            category = "Food & Drink",
            totalAmount = 900.0,
            companions = "Alice",
            myShare = 450.0,
            owedToMe = 450.0,
            status = TransactionStatus.PENDING
        )
        val personalTxn = Transaction(
            id = "t_personal",
            timestamp = ts + 1000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "UPI",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = 200.0,
            companions = "Personal",
            myShare = 200.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        )

        tRepo.insertTransaction(splitTxn)
        tRepo.insertTransaction(personalTxn)

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        val state = vm.uiState.value
        val expectedLivingBurn = 650.0
        val expectedGrossTotal = 1100.0

        assertEquals(expectedLivingBurn, state.myShare, 0.001)
        assertEquals(expectedGrossTotal, state.totalOut, 0.001)

        val dayGroup = state.groupedTransactions.firstOrNull()
        assertNotNull(dayGroup)
        assertEquals(expectedLivingBurn, dayGroup!!.dayMyShare, 0.001)
        assertEquals(expectedGrossTotal, dayGroup.dayTotalOut, 0.001)
    }

    @Test
    fun testVoidTransaction_andUndoVoid_andUndoSave() = runTest {
        val tRepo = FakeTransactionRepo()
        val ts = System.currentTimeMillis()
        val txn = Transaction(
            id = "t_voidable",
            timestamp = ts,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "UPI",
            merchant = "Grocery Store",
            category = "Groceries",
            totalAmount = 300.0,
            companions = "Personal",
            myShare = 300.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        )
        tRepo.insertTransaction(txn)

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // Void the transaction
        vm.voidTransaction(txn)
        advanceUntilIdle()
        assertEquals(listOf("t_voidable"), tRepo.voidCalls)
        assertEquals(txn, vm.lastVoidedTransaction)

        // Undo the void
        var undoVoidCompleted = false
        vm.undoVoid { success -> undoVoidCompleted = success }
        advanceUntilIdle()
        assertTrue(undoVoidCompleted)
        assertEquals(listOf(txn), tRepo.updateCalls)
        assertNull(vm.lastVoidedTransaction)

        // Undo save
        vm.recordSavedTransaction("t_newly_saved")
        assertEquals("t_newly_saved", vm.lastSavedTransactionId)
        var undoSaveCompleted = false
        vm.undoSave { success -> undoSaveCompleted = success }
        advanceUntilIdle()
        assertTrue(undoSaveCompleted)
        assertEquals(listOf("t_newly_saved"), tRepo.undoRecentSaveCalls)
        assertNull(vm.lastSavedTransactionId)
    }

    @Test
    fun testSyncNow_triggersSyncPendingTransactions() = runTest {
        val tRepo = FakeTransactionRepo()
        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        var syncCompleted = false
        vm.syncNow { success -> syncCompleted = success }
        advanceUntilIdle()

        assertTrue(syncCompleted)
        assertEquals(1, tRepo.syncCalls)
        assertEquals(false, vm.uiState.value.isSyncing)
    }

    @Test
    fun testFilterCombination_categoryAccountAndShowVoid() = runTest {
        val tRepo = FakeTransactionRepo()
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(kolkataTz).apply { set(Calendar.DAY_OF_MONTH, 15) }
        val ts = nowCal.timeInMillis

        // 1. Food & Drink with UPI
        val txnFoodUpi = Transaction(
            id = "t_food_upi",
            timestamp = ts,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "UPI",
            merchant = "Restaurant A",
            category = "Food & Drink",
            totalAmount = 500.0,
            companions = "Personal",
            myShare = 500.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        )
        // 2. Food & Drink with Card
        val txnFoodCard = Transaction(
            id = "t_food_card",
            timestamp = ts + 1000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "HDFC Card",
            merchant = "Restaurant B",
            category = "Food & Drink",
            totalAmount = 800.0,
            companions = "Personal",
            myShare = 800.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        )
        // 3. Groceries with UPI
        val txnGroceriesUpi = Transaction(
            id = "t_groceries_upi",
            timestamp = ts + 2000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "UPI",
            merchant = "Market",
            category = "Groceries",
            totalAmount = 300.0,
            companions = "Personal",
            myShare = 300.0,
            owedToMe = 0.0,
            status = TransactionStatus.SETTLED
        )
        // 4. Food & Drink with UPI that is VOID
        val txnFoodUpiVoid = Transaction(
            id = "t_food_upi_void",
            timestamp = ts + 3000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "UPI",
            merchant = "[Void] Restaurant C",
            category = "Food & Drink",
            totalAmount = 0.0,
            companions = "Personal",
            myShare = 0.0,
            owedToMe = 0.0,
            status = TransactionStatus.VOID
        )

        tRepo.insertTransaction(txnFoodUpi)
        tRepo.insertTransaction(txnFoodCard)
        tRepo.insertTransaction(txnGroceriesUpi)
        tRepo.insertTransaction(txnFoodUpiVoid)

        val vm = TransactionsViewModel(tRepo, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // Default: 3 active transactions (void is hidden)
        val defaultItems = vm.uiState.value.groupedTransactions.flatMap { it.transactions }
        assertEquals(3, defaultItems.size)

        // Filter: Category = Food & Drink
        vm.setFilter(TransactionFilter(category = "Food & Drink"))
        advanceUntilIdle()
        val foodItems = vm.uiState.value.groupedTransactions.flatMap { it.transactions }
        assertEquals(2, foodItems.size) // Food UPI, Food Card

        // Combine Filter: Category = Food & Drink AND Account = UPI
        vm.setFilter(TransactionFilter(category = "Food & Drink", account = "UPI"))
        advanceUntilIdle()
        val foodUpiItems = vm.uiState.value.groupedTransactions.flatMap { it.transactions }
        assertEquals(1, foodUpiItems.size)
        assertEquals("t_food_upi", foodUpiItems[0].id)

        // Combine Filter: Category = Food & Drink AND Account = UPI AND showVoid = true
        vm.setFilter(TransactionFilter(category = "Food & Drink", account = "UPI", showVoid = true))
        advanceUntilIdle()
        val foodUpiWithVoid = vm.uiState.value.groupedTransactions.flatMap { it.transactions }
        assertEquals(2, foodUpiWithVoid.size)
        assertTrue(foodUpiWithVoid.any { it.id == "t_food_upi" })
        assertTrue(foodUpiWithVoid.any { it.id == "t_food_upi_void" })
    }
}
