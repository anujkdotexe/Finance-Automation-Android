package com.financeautomation.presentation.screens.drawer

import com.financeautomation.R
import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.presentation.viewmodel.SettingsCustomizationCoordinator
import com.financeautomation.presentation.viewmodel.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PeopleTripsRemoveWiringTest {

    private class FakePeopleRepository(
        private var companions: List<Companion>
    ) : PeopleRepository {
        var removedPerson: String? = null

        override fun getCompanions(): Flow<List<Companion>> = flowOf(companions)
        override suspend fun getAllCompanions(): List<Companion> = companions
        override suspend fun getCompanionById(id: String): Companion? = companions.find { it.id == id }
        override suspend fun getCompanionByName(name: String): Companion? = companions.find { it.name.equals(name, ignoreCase = true) }
        override suspend fun insertCompanion(companion: Companion) { companions = companions + companion }
        override suspend fun updateCompanion(companion: Companion) {}
        override suspend fun updateCompanionName(id: String, newName: String) {}
        override suspend fun deleteCompanion(companion: Companion) {}
        override suspend fun deleteCompanionById(id: String) {}
        override suspend fun removePerson(name: String): Result<Unit> {
            removedPerson = name
            return Result.Success(Unit)
        }
    }

    private class FakeTripTagRepository(
        private val activeTrips: Set<String>
    ) : TripTagRepository {
        var removedTrip: String? = null

        override fun getTripTags(includeArchived: Boolean): Flow<List<TripTag>> = flowOf(emptyList())
        override suspend fun getAllTripTags(includeArchived: Boolean): List<TripTag> = emptyList()
        override suspend fun getActiveTripTags(): List<TripTag> = emptyList()
        override fun getActiveTag(): Flow<TripTag?> = flowOf(null)
        override suspend fun getActiveTagSync(): TripTag? = null
        override suspend fun getTagById(id: String): TripTag? = null
        override suspend fun getTagByName(name: String): TripTag? = null
        override suspend fun insertTripTag(name: String): TripTag = TripTag(id = name, name = name)
        override suspend fun updateTripTag(tripTag: TripTag) {}
        override suspend fun setActiveTag(id: String?) {}
        override suspend fun archiveTripTag(id: String) {}
        override suspend fun unarchiveTripTag(id: String) {}
        override suspend fun deleteTripTag(id: String) {}
        override suspend fun removeTripTag(name: String): Result<Unit> {
            if (activeTrips.contains(name)) {
                return Result.Error(IllegalStateException("Cannot remove active trip: $name"))
            }
            removedTrip = name
            return Result.Success(Unit)
        }
    }

    private class FakePreferencesRepository : PreferencesRepository {
        override fun getSpreadsheetId(): Flow<String?> = flowOf(null)
        override suspend fun getSpreadsheetIdSync(): String? = null
        override suspend fun setSpreadsheetId(id: String) {}
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(null)
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = null
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {}
        override fun getDefaultAccount(): Flow<String> = flowOf("Cash")
        override suspend fun getDefaultAccountSync(): String = "Cash"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(trip: String?) {}
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
        override suspend fun getGoogleAccountEmailSync(): String? = null
        override suspend fun setGoogleAccountEmail(email: String?) {}
        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(120)
        override suspend fun getSettleDelaySecondsSync(): Int = 120
        override suspend fun setSettleDelaySeconds(seconds: Int) {}
        override suspend fun getOrCreateDeviceId(): String = "device"
        override suspend fun getDeviceIdSync(): String? = "device"
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

    @Test
    fun personWithOpenBalance_removalIsBlockedAndRefusalShown() = runBlocking {
        val bob = Companion(id = "comp_bob", name = "Bob", balance = 150.0)
        val peopleRepo = FakePeopleRepository(listOf(bob))
        val tripRepo = FakeTripTagRepository(emptySet())
        val uiState = MutableStateFlow(SettingsUiState(companions = listOf(bob)))

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = FakePreferencesRepository(),
            peopleRepository = peopleRepo,
            transactionRepository = null,
            payableDao = null,
            payableDaoProvider = null,
            accountRepository = null,
            categoryRepository = null,
            tripTagRepository = tripRepo,
            scope = this,
            uiState = uiState,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.onRemoveCompanion("comp_bob")

        assertNull("Repository must not be called when balance is open", peopleRepo.removedPerson)
        assertEquals(R.string.settings_err_cannot_remove_open_balance, uiState.value.errorMessage?.resId)
        assertEquals(listOf("Bob"), uiState.value.errorMessage?.args)
    }

    @Test
    fun personWithZeroBalance_isRemovedViaRepositoryAndSuccessMessageShown() = runBlocking {
        val alice = Companion(id = "comp_alice", name = "Alice", balance = 0.0)
        val peopleRepo = FakePeopleRepository(listOf(alice))
        val tripRepo = FakeTripTagRepository(emptySet())
        val uiState = MutableStateFlow(SettingsUiState(companions = listOf(alice)))

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = FakePreferencesRepository(),
            peopleRepository = peopleRepo,
            transactionRepository = null,
            payableDao = null,
            payableDaoProvider = null,
            accountRepository = null,
            categoryRepository = null,
            tripTagRepository = tripRepo,
            scope = this,
            uiState = uiState,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.onRemoveCompanion("comp_alice")

        assertEquals("Alice", peopleRepo.removedPerson)
        assertEquals(R.string.settings_msg_companion_removed, uiState.value.userMessage?.resId)
    }

    @Test
    fun activeTrip_removalIsBlockedAndErrorMessageShown() = runBlocking {
        val peopleRepo = FakePeopleRepository(emptyList())
        val tripRepo = FakeTripTagRepository(activeTrips = setOf("GoaTrip"))
        val uiState = MutableStateFlow(SettingsUiState())

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = FakePreferencesRepository(),
            peopleRepository = peopleRepo,
            transactionRepository = null,
            payableDao = null,
            payableDaoProvider = null,
            accountRepository = null,
            categoryRepository = null,
            tripTagRepository = tripRepo,
            scope = this,
            uiState = uiState,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.onRemoveTripTag("GoaTrip")

        assertNull("Active trip must not be removed", tripRepo.removedTrip)
        assertNotNull("Error message must be shown for active trip removal", uiState.value.errorMessage)
    }

    @Test
    fun endedTrip_isRemovedViaRepositoryAndSuccessMessageShown() = runBlocking {
        val peopleRepo = FakePeopleRepository(emptyList())
        val tripRepo = FakeTripTagRepository(activeTrips = emptySet())
        val uiState = MutableStateFlow(SettingsUiState())

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = FakePreferencesRepository(),
            peopleRepository = peopleRepo,
            transactionRepository = null,
            payableDao = null,
            payableDaoProvider = null,
            accountRepository = null,
            categoryRepository = null,
            tripTagRepository = tripRepo,
            scope = this,
            uiState = uiState,
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.onRemoveTripTag("OldTrip")

        assertEquals("OldTrip", tripRepo.removedTrip)
        assertEquals(R.string.settings_msg_trip_removed, uiState.value.userMessage?.resId)
    }
}
