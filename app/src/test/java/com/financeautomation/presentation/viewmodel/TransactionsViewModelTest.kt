package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
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
        override fun getTransactions(): Flow<List<Transaction>> = txns
        override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = txns.value.find { it.id == id }
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> {
            txns.value = txns.value + transaction
            return Result.Success(Unit)
        }
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> {
            txns.value = txns.value.map { if (it.id == transaction.id) transaction else it }
            return Result.Success(Unit)
        }
        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> = Result.Success(Unit)
        override suspend fun undoRecentSave(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = Result.Success(0)
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> = Result.Success("id")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> = Result.Success(Unit)
        override suspend fun closeYear(year: Int): Result<Unit> = Result.Success(Unit)
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
}
