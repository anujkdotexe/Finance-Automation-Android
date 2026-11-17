package com.financeautomation.data

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.presentation.viewmodel.SettingsViewModel
import com.financeautomation.presentation.viewmodel.UiText
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CheckAllMonthsTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun testReconcileAllMonths_bypassesTwoMonthWindow_reconcilesOlderMonth() = runBlocking {
        // Tabs include Jan26 (older), Oct26 (previous), Nov26 (current)
        val tabs = listOf("Jan26", "Oct26", "Nov26")
        val harness = SyncHarness(tabs = tabs)

        // Set harness time to 2026-11-15T12:00:00 (Nov 2026)
        val novEpoch = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        harness.now = novEpoch

        // Script sheets server: return an older debit row on Jan26 tab
        harness.server.customRead = { call ->
            if (call.method == "GET" && call.url.contains("values:batchGet")) {
                """{
                    "valueRanges": [
                        {
                            "range": "'Jan26'!A4:K",
                            "majorDimension": "ROWS",
                            "values": [
                                [46025, 0.5, "SBI", "Old Coffee Jan", "", "Food & Drink", 150.0, "Personal", 150.0, 0.0, "Personal"]
                            ]
                        },
                        {
                            "range": "'Jan26'!AC4:AC",
                            "majorDimension": "ROWS",
                            "values": [
                                ["txn_jan_old_123"]
                            ]
                        },
                        {"range": "'Jan26'!M4:T", "majorDimension": "ROWS", "values": []},
                        {"range": "'Jan26'!AD4:AD", "majorDimension": "ROWS", "values": []},
                        {"range": "'Jan26'!V4:AB", "majorDimension": "ROWS", "values": []},
                        {"range": "'Jan26'!AE4:AE", "majorDimension": "ROWS", "values": []},
                        {"range": "'Oct26'!A4:K", "majorDimension": "ROWS", "values": []},
                        {"range": "'Oct26'!AC4:AC", "majorDimension": "ROWS", "values": []},
                        {"range": "'Oct26'!M4:T", "majorDimension": "ROWS", "values": []},
                        {"range": "'Oct26'!AD4:AD", "majorDimension": "ROWS", "values": []},
                        {"range": "'Oct26'!V4:AB", "majorDimension": "ROWS", "values": []},
                        {"range": "'Oct26'!AE4:AE", "majorDimension": "ROWS", "values": []},
                        {"range": "'Nov26'!A4:K", "majorDimension": "ROWS", "values": []},
                        {"range": "'Nov26'!AC4:AC", "majorDimension": "ROWS", "values": []},
                        {"range": "'Nov26'!M4:T", "majorDimension": "ROWS", "values": []},
                        {"range": "'Nov26'!AD4:AD", "majorDimension": "ROWS", "values": []},
                        {"range": "'Nov26'!V4:AB", "majorDimension": "ROWS", "values": []},
                        {"range": "'Nov26'!AE4:AE", "majorDimension": "ROWS", "values": []}
                    ]
                }"""
            } else null
        }

        // Call reconcileAllMonths() which bypasses the 2-month window
        val res = harness.repo.reconcileAllMonths()
        assertTrue("reconcileAllMonths should succeed: $res", res is Result.Success)
        assertEquals(1, (res as Result.Success).data)

        // Verify that the Jan26 debit row was imported into local DB
        val imported = harness.tDao.getById("txn_jan_old_123")
        assertNotNull("Debit row from Jan26 must be imported", imported)
        assertEquals("Old Coffee Jan", imported?.merchant)
        assertEquals(150.0, imported?.totalAmount ?: 0.0, 0.001)
    }

    @Test
    fun testReconcileAllMonths_enforcesTenMinuteCooldown() = runBlocking {
        val harness = SyncHarness(tabs = listOf("Nov26"))
        harness.now = 1_000_000L

        // First call succeeds
        val firstRes = harness.repo.reconcileAllMonths()
        assertTrue("First call must succeed", firstRes is Result.Success)

        // Second call within 10 minutes (only 1 minute later) is rate-limited
        harness.now += 60_000L
        val secondRes = harness.repo.reconcileAllMonths()
        assertTrue("Second call within 10m must fail with error", secondRes is Result.Error)
        val errMsg = (secondRes as Result.Error).exception.message.orEmpty()
        assertTrue(
            "Error must inform user about the 10-minute cooldown, got: $errMsg",
            errMsg.contains("10 minutes", ignoreCase = true)
        )

        // Advance by 8 more minutes (total 9 minutes elapsed): still cooling down
        harness.now += 8 * 60_000L
        val thirdRes = harness.repo.reconcileAllMonths()
        assertTrue("Still cooling down at 9 minutes", thirdRes is Result.Error)

        // Advance 2 more minutes (total 11 minutes elapsed): cooldown expired, should succeed
        harness.now += 2 * 60_000L
        val fourthRes = harness.repo.reconcileAllMonths()
        assertTrue("Call after 11 minutes must succeed: $fourthRes", fourthRes is Result.Success)
    }

    @Test
    fun testSettingsViewModel_onCheckAllMonths_reportsCountsAndCooldown() = runBlocking {
        val mockRepo = object : TransactionRepository {
            var resultToReturn: Result<Int> = Result.Success(5)
            override suspend fun reconcileAllMonths(): Result<Int> = resultToReturn

            override fun getTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
            override fun getNeedsReview(): Flow<List<Transaction>> = flowOf(emptyList())
            override suspend fun getTransactionById(id: String): Transaction? = null
            override suspend fun insertTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
            override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
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
            override suspend fun requeueYearForNewWorkbook(year: Int) {}
        }

        val prefs = SyncHarness().prefs
        val peopleRepo = object : com.financeautomation.domain.repository.PeopleRepository {
            override fun getCompanions(): Flow<List<com.financeautomation.domain.model.Companion>> = flowOf(emptyList())
            override suspend fun getAllCompanions(): List<com.financeautomation.domain.model.Companion> = emptyList()
            override suspend fun getCompanionById(id: String): com.financeautomation.domain.model.Companion? = null
            override suspend fun getCompanionByName(name: String): com.financeautomation.domain.model.Companion? = null
            override suspend fun insertCompanion(companion: com.financeautomation.domain.model.Companion) {}
            override suspend fun updateCompanion(companion: com.financeautomation.domain.model.Companion) {}
            override suspend fun updateCompanionName(id: String, newName: String) {}
            override suspend fun deleteCompanion(companion: com.financeautomation.domain.model.Companion) {}
            override suspend fun deleteCompanionById(id: String) {}
        }

        val vm = SettingsViewModel(
            preferencesRepository = prefs,
            peopleRepository = peopleRepo,
            transactionRepository = mockRepo,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Case 1: Reconciled changes > 0
        mockRepo.resultToReturn = Result.Success(3)
        vm.onCheckAllMonths()
        val userMsg1 = vm.uiState.value.userMessage
        assertTrue("userMessage should be set on success", userMsg1 is UiText.StringResource)
        val resId1 = (userMsg1 as UiText.StringResource).resId
        assertEquals(com.financeautomation.R.string.settings_msg_reconciled_all_months, resId1)
        assertEquals(listOf(3), userMsg1.args)

        // Case 2: Reconciled count == 0 (nothing changed)
        mockRepo.resultToReturn = Result.Success(0)
        vm.onCheckAllMonths()
        val userMsg2 = vm.uiState.value.userMessage
        assertTrue("userMessage should be set when nothing changed", userMsg2 is UiText.StringResource)
        val resId2 = (userMsg2 as UiText.StringResource).resId
        assertEquals(com.financeautomation.R.string.settings_msg_all_months_up_to_date, resId2)

        // Case 3: Cooldown error
        mockRepo.resultToReturn = Result.Error("Please wait 10 minutes before checking all months again")
        vm.onCheckAllMonths()
        val errMsg = vm.uiState.value.errorMessage
        assertTrue("errorMessage should be set on cooldown error", errMsg is UiText.DynamicString)
        assertEquals("Please wait 10 minutes before checking all months again", (errMsg as UiText.DynamicString).value)
    }
}
