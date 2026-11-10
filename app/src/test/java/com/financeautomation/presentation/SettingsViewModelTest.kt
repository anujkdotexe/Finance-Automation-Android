package com.financeautomation.presentation

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SettingsViewModelTest {

    private lateinit var fakePreferencesRepository: FakePreferencesRepository
    private lateinit var fakePeopleRepository: FakePeopleRepository
    private lateinit var fakeTransactionRepository: FakeTransactionRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() = runBlocking {
        fakePreferencesRepository = FakePreferencesRepository()
        fakePeopleRepository = FakePeopleRepository()
        fakeTransactionRepository = FakeTransactionRepository()

        fakePreferencesRepository.setSpreadsheetId("sheet_12345")
        fakePreferencesRepository.setDefaultAccount(CategoryConstants.ACCOUNT_SBI)
        fakePreferencesRepository.setActiveTrip("Goa Trip")
        fakePreferencesRepository.setGoogleAccountEmail("test@example.com")

        fakePeopleRepository.insertCompanion(Companion(id = "comp_1", name = "Aman"))
        fakePeopleRepository.insertCompanion(Companion(id = "comp_2", name = "Priya"))

        viewModel = SettingsViewModel(
            preferencesRepository = fakePreferencesRepository,
            peopleRepository = fakePeopleRepository,
            transactionRepository = fakeTransactionRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testInitialStateLoadsPreferencesAndCompanions() {
        val state = viewModel.uiState.value
        assertEquals("sheet_12345", state.spreadsheetId)
        assertEquals(CategoryConstants.ACCOUNT_SBI, state.defaultAccount)
        assertEquals("Goa Trip", state.activeTripTag)
        assertEquals("test@example.com", state.googleAccountEmail)
        assertEquals(2, state.companions.size)
    }

    @Test
    fun testSpreadsheetIdChangedUpdatesRepository() = runBlocking {
        viewModel.onSpreadsheetIdChanged("sheet_new_999")

        val state = viewModel.uiState.value
        assertEquals("Spreadsheet ID saved", state.userMessage)
        val updated = fakePreferencesRepository.getSpreadsheetId().first()
        assertEquals("sheet_new_999", updated)
    }

    @Test
    fun testDefaultAccountChangedUpdatesRepository() = runBlocking {
        viewModel.onDefaultAccountChanged(CategoryConstants.ACCOUNT_CANARA)

        val updated = fakePreferencesRepository.getDefaultAccount().first()
        assertEquals(CategoryConstants.ACCOUNT_CANARA, updated)
    }

    @Test
    fun testActiveTripTagChangedUpdatesRepository() = runBlocking {
        viewModel.onActiveTripTagChanged("Manali Trip")

        val updated = fakePreferencesRepository.getActiveTrip().first()
        assertEquals("Manali Trip", updated)
    }

    @Test
    fun testAddCompanionInsertsIntoRepository() = runBlocking {
        viewModel.onAddCompanion("Vikram")

        val companion = fakePeopleRepository.getCompanionByName("Vikram")
        assertNotNull(companion)
        assertEquals("Vikram", companion?.name)

        val state = viewModel.uiState.value
        assertEquals("Added companion Vikram", state.userMessage)
    }

    @Test
    fun testAddDuplicateCompanionFailsValidation() = runBlocking {
        viewModel.onAddCompanion("Aman")

        val state = viewModel.uiState.value
        assertEquals("A companion with this name already exists", state.errorMessage)
    }

    @Test
    fun testAddBlankCompanionFailsValidation() = runBlocking {
        viewModel.onAddCompanion("   ")

        val state = viewModel.uiState.value
        assertEquals("Friend name cannot be blank", state.errorMessage)
    }

    @Test
    fun testRemoveCompanionDeletesFromRepository() = runBlocking {
        viewModel.onRemoveCompanion("comp_1")

        val companion = fakePeopleRepository.getCompanionById("comp_1")
        assertNull(companion)

        val state = viewModel.uiState.value
        assertEquals("Companion removed", state.userMessage)
    }

    @Test
    fun testSyncNowSuccessUpdatesResultState() = runBlocking {
        fakeTransactionRepository.syncReturnValue = Result.Success(5)
        viewModel.onSyncNow()

        val state = viewModel.uiState.value
        assertEquals("Synced 5 transaction(s) to Google Sheets", state.syncResult)
        assertEquals("Sync completed successfully", state.userMessage)
        assertNull(state.errorMessage)
    }

    @Test
    fun testSyncNowFailureHandlesErrorSafely() = runBlocking {
        fakeTransactionRepository.syncReturnValue = Result.Error(
            throwable = RuntimeException("Network timeout"),
            message = "Sync failed due to connection error"
        )
        viewModel.onSyncNow()

        val state = viewModel.uiState.value
        assertEquals("Sync failed due to connection error", state.errorMessage)
        assertNull(state.syncResult)
    }

    private class FakePreferencesRepository : PreferencesRepository {
        private val spreadsheetIdFlow = MutableStateFlow<String?>(null)
        private val defaultAccountFlow = MutableStateFlow(CategoryConstants.ACCOUNT_SBI)
        private val activeTripFlow = MutableStateFlow<String?>(null)
        private val googleEmailFlow = MutableStateFlow<String?>(null)
        private val settleDelayFlow = MutableStateFlow(120)

        override fun getSpreadsheetId(): Flow<String?> = spreadsheetIdFlow.asStateFlow()
        override suspend fun getSpreadsheetIdSync(): String? = spreadsheetIdFlow.value
        override suspend fun setSpreadsheetId(id: String) {
            spreadsheetIdFlow.value = id
        }

        override fun getDefaultAccount(): Flow<String> = defaultAccountFlow.asStateFlow()
        override suspend fun getDefaultAccountSync(): String = defaultAccountFlow.value
        override suspend fun setDefaultAccount(account: String) {
            defaultAccountFlow.value = account
        }

        override fun getActiveTrip(): Flow<String?> = activeTripFlow.asStateFlow()
        override suspend fun setActiveTrip(trip: String?) {
            activeTripFlow.value = trip
        }

        override fun getGoogleAccountEmail(): Flow<String?> = googleEmailFlow.asStateFlow()
        override suspend fun setGoogleAccountEmail(email: String?) {
            googleEmailFlow.value = email
        }

        override fun getSettleDelaySeconds(): Flow<Int> = settleDelayFlow.asStateFlow()
        override suspend fun getSettleDelaySecondsSync(): Int = settleDelayFlow.value
        override suspend fun setSettleDelaySeconds(seconds: Int) {
            settleDelayFlow.value = seconds
        }
    }

    private class FakePeopleRepository : PeopleRepository {
        private val storage = ConcurrentHashMap<String, Companion>()
        private val flow = MutableStateFlow<List<Companion>>(emptyList())

        private fun updateFlow() {
            flow.value = storage.values.toList()
        }

        override fun getCompanions(): Flow<List<Companion>> = flow.asStateFlow()
        override suspend fun getAllCompanions(): List<Companion> = storage.values.toList()
        override suspend fun getCompanionById(id: String): Companion? = storage[id]
        override suspend fun getCompanionByName(name: String): Companion? =
            storage.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

        override suspend fun insertCompanion(companion: Companion) {
            storage[companion.id] = companion
            updateFlow()
        }

        override suspend fun updateCompanion(companion: Companion) {
            storage[companion.id] = companion
            updateFlow()
        }

        override suspend fun updateCompanionName(id: String, newName: String) {
            val existing = storage[id]
            if (existing != null) {
                storage[id] = existing.copy(name = newName)
                updateFlow()
            }
        }

        override suspend fun deleteCompanion(companion: Companion) {
            storage.remove(companion.id)
            updateFlow()
        }

        override suspend fun deleteCompanionById(id: String) {
            storage.remove(id)
            updateFlow()
        }
    }

    private class FakeTransactionRepository : TransactionRepository {
        var syncReturnValue: Result<Int> = Result.Success(0)

        override fun getTransactions(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun getNeedsReview(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun getTransactionById(id: String): Transaction? = null
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = Result.Success(Unit)
        override suspend fun voidTransaction(id: String): Result<Unit> = Result.Success(Unit)
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> = emptyList()
        override suspend fun syncPendingTransactions(): Result<Int> = syncReturnValue
    }
}
