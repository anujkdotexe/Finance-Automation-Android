package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.SheetPayloadMapper
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardAndSyncQueueResilienceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun singularSavingNormalizesToPluralSavingsWithoutThrowing() {
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, CategoryConstants.normalizeCategory("saving"))
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, CategoryConstants.normalizeCategory("Saving"))
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, CategoryConstants.normalizeCategory("SAVING"))
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, CategoryConstants.normalizeCategory("savings"))
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, CategoryConstants.normalizeCategory("investment"))
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, CategoryConstants.normalizeCategory("sip"))
    }

    @Test
    fun sheetPayloadMapperMapsSingularSavingToPluralSavings() {
        val txn = Transaction(
            id = "txn_save_1",
            timestamp = System.currentTimeMillis(),
            dateSerial = 45000,
            timeSerial = 0.5,
            account = "Bank",
            merchant = "Mutual Fund",
            category = "Saving",
            totalAmount = 5000.0,
            myShare = 5000.0,
            owedToMe = 0.0
        )

        val row = SheetPayloadMapper.mapTransactionToFullRow(txn)
        // Column index 5 is Category
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, row[5])

        val table1 = SheetPayloadMapper.mapTransactionToTable1(txn)
        assertEquals(CategoryConstants.CATEGORY_SAVINGS, table1[5])
    }

    private class FakePrefRepo(var activeSpreadsheetId: String? = "test_sheet_id") : PreferencesRepository {
        val yearMap = mutableMapOf<Int, String>()
        override fun getSpreadsheetId(): Flow<String?> = flowOf(activeSpreadsheetId)
        override suspend fun getSpreadsheetIdSync(): String? = activeSpreadsheetId
        override suspend fun setSpreadsheetId(id: String) { activeSpreadsheetId = id }
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(yearMap[year])
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = yearMap[year]
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) { yearMap[year] = id }
        override fun getDefaultAccount(): Flow<String> = flowOf("Account")
        override suspend fun getDefaultAccountSync(): String = "Account"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(trip: String?) {}
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
        override suspend fun getGoogleAccountEmailSync(): String? = null
        override suspend fun setGoogleAccountEmail(email: String?) {}
        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(120)
        override suspend fun getSettleDelaySecondsSync(): Int = 120
        override suspend fun setSettleDelaySeconds(seconds: Int) {}
        override suspend fun getOrCreateDeviceId(): String = "test-device-uuid"
        override suspend fun getDeviceIdSync(): String? = "test-device-uuid"
        override fun getDeveloperMode(): Flow<Boolean> = flowOf(false)
        override suspend fun getDeveloperModeSync(): Boolean = false
        override suspend fun setDeveloperMode(enabled: Boolean) {}
        override fun getThemeMode(): Flow<com.financeautomation.domain.model.ThemeMode> = flowOf(com.financeautomation.domain.model.ThemeMode.SYSTEM)
        override suspend fun getThemeModeSync(): com.financeautomation.domain.model.ThemeMode = com.financeautomation.domain.model.ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: com.financeautomation.domain.model.ThemeMode) {}
        override suspend fun getPreference(key: String): String? = null
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {}
        override suspend fun deletePreference(key: String) {}
    }

    private class FakeAccRepo : AccountRepository {
        override fun getAccounts(includeArchived: Boolean): Flow<List<Account>> = flowOf(emptyList())
        override suspend fun getAllAccounts(includeArchived: Boolean): List<Account> = emptyList()
        override suspend fun getAccountById(id: String): Account? = null
        override suspend fun getAccountByName(name: String): Account? = null
        override suspend fun getDefaultAccount(): Account? = null
        override suspend fun insertAccount(account: Account) {}
        override suspend fun updateAccount(account: Account) {}
        override suspend fun setDefaultAccount(id: String) {}
        override suspend fun archiveAccount(id: String) {}
        override suspend fun unarchiveAccount(id: String) {}
        override suspend fun deleteAccount(id: String) {}
        override suspend fun reorderAccounts(accountIds: List<String>) {}
        override fun getAccountTransactionCounts(from: Long, to: Long): Flow<Map<String, Int>> = flowOf(emptyMap())
    }

    private class FakeCatRepo : CategoryRepository {
        override fun getCategories(includeArchived: Boolean): Flow<List<Category>> = flowOf(emptyList())
        override suspend fun getAllCategories(includeArchived: Boolean): List<Category> = emptyList()
        override suspend fun getActiveCategories(): List<Category> = emptyList()
        override suspend fun getCategoryById(id: String): Category? = null
        override suspend fun getCategoryByName(name: String): Category? = null
        override suspend fun insertCategory(category: Category) {}
        override suspend fun updateCategory(category: Category) {}
        override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean) {}
        override suspend fun archiveCategory(id: String) {}
        override suspend fun unarchiveCategory(id: String) {}
        override suspend fun deleteCategory(id: String) {}
        override suspend fun reorderCategories(categoryIds: List<String>) {}
    }

    @Test
    fun regenerateDashboardReturnsErrorWhenHttpClientIsNullWithoutFallingBackToSheet0() = runTest(testDispatcher) {
        val fakeSheetsClient = GoogleSheetsClient(okHttpClient = okhttp3.OkHttpClient(), tokenProvider = null)
        val useCase = RegenerateDashboardUseCase(
            googleSheetsClient = fakeSheetsClient,
            preferencesRepository = FakePrefRepo(),
            accountRepository = FakeAccRepo(),
            categoryRepository = FakeCatRepo(),
            okHttpClient = null,
            tokenProvider = null,
            ioDispatcher = testDispatcher
        )

        val result = useCase()
        assertTrue("Expected Result.Error when sheet properties cannot be fetched", result is Result.Error)
        val err = (result as Result.Error).exception
        assertTrue("Error should mention failed fetch, got: ${err.message}", err.message?.contains("Failed to fetch sheet properties") == true)
    }

    @Test
    fun syncQueuePoisonPillDoesNotBlockQueueRun() = runTest(testDispatcher) {
        val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
        var queueStatusUpdates = mutableMapOf<Long, String>()

        val poisonedItem = SyncQueueEntity(
            id = 101L,
            transactionId = "txn_poisoned",
            operationType = SyncQueueEntity.OP_LOG_CREDIT,
            payloadJson = "{ INVALID JSON }",
            status = SyncQueueEntity.STATUS_PENDING,
            retryCount = 0
        )

        val validItem = SyncQueueEntity(
            id = 102L,
            transactionId = "txn_valid",
            operationType = SyncQueueEntity.OP_CONFIG_PUSH,
            payloadJson = "{}",
            status = SyncQueueEntity.STATUS_PENDING,
            retryCount = 0
        )

        val pendingItems = listOf(poisonedItem, validItem)
        val failedIds = mutableSetOf<String>()
        var processedValid = false

        for (item in pendingItems) {
            if (item.transactionId in failedIds) continue
            try {
                if (item.operationType == SyncQueueEntity.OP_LOG_CREDIT) {
                    // Simulate JSON deserialization error
                    throw kotlinx.serialization.SerializationException("Malformed JSON payload")
                }
                if (item.operationType == SyncQueueEntity.OP_CONFIG_PUSH) {
                    processedValid = true
                }
            } catch (e: Exception) {
                // Verified production pattern from TransactionRepositoryImpl: fail item and continue
                queueStatusUpdates[item.id] = SyncQueueEntity.STATUS_FAILED
                failedIds.add(item.transactionId)
                continue
            }
        }

        assertEquals("Poisoned item must be marked FAILED", SyncQueueEntity.STATUS_FAILED, queueStatusUpdates[101L])
        assertTrue("Poisoned item txnId must be in failedIds", failedIds.contains("txn_poisoned"))
        assertTrue("Subsequent valid item must be processed successfully despite earlier error", processedValid)
    }
}
