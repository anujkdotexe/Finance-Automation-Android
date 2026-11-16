package com.financeautomation.data.repository

import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.core.model.Result
import com.financeautomation.data.local.ConfigSyncLocalGatewayImpl
import com.financeautomation.data.local.dao.BudgetDao
import com.financeautomation.data.local.entity.BudgetEntity
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.domain.model.ConfigRow
import com.financeautomation.domain.repository.ConfigRepository
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.DirtyConfigSnapshot
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BudgetRepositoryImplTest {

    private class FakeBudgetDao : BudgetDao {
        val rows = MutableStateFlow<Map<String, BudgetEntity>>(emptyMap())
        override fun getBudgetsFlow(): Flow<List<BudgetEntity>> =
            rows.map { m -> m.values.filter { it.deleted == 0 }.sortedBy { it.category.lowercase() } }
        override suspend fun getAll(): List<BudgetEntity> = rows.value.values.filter { it.deleted == 0 }
        override suspend fun getAllIncludingDeleted(): List<BudgetEntity> = rows.value.values.toList()
        override suspend fun getByCategory(category: String): BudgetEntity? = rows.value[category]
        override suspend fun upsert(budget: BudgetEntity) { rows.value = rows.value + (budget.category to budget) }
        override suspend fun markDeleted(category: String, updatedAt: Long) {
            rows.value[category]?.let { rows.value = rows.value + (category to it.copy(deleted = 1, dirty = 1, updatedAt = updatedAt)) }
        }
        override suspend fun getDirty(): List<BudgetEntity> = rows.value.values.filter { it.dirty == 1 }
        override suspend fun markClean(categories: List<String>) {
            rows.value = rows.value.mapValues { (k, v) -> if (k in categories) v.copy(dirty = 0) else v }
        }
        override suspend fun updateCategoryName(oldCategory: String, newCategory: String, updatedAt: Long): Int {
            val existing = rows.value[oldCategory] ?: return 0
            val updatedMap = rows.value.toMutableMap()
            updatedMap.remove(oldCategory)
            updatedMap[newCategory] = existing.copy(category = newCategory, dirty = 1, updatedAt = updatedAt)
            rows.value = updatedMap
            return 1
        }
    }

    private var now = 1_000L
    private val dao = FakeBudgetDao()
    private val repo = BudgetRepositoryImpl(dao, Dispatchers.Unconfined) { now }

    @Test
    fun setBudgetStoresAndObserves() = runBlocking {
        assertTrue(repo.setBudget("Food", 5000.0) is Result.Success)
        val list = repo.observeBudgets().first()
        assertEquals(1, list.size)
        assertEquals("Food", list[0].category)
        assertEquals(5000.0, list[0].monthlyAmount, 0.0)
        assertEquals(1_000L, list[0].updatedAt)
        assertEquals(1, dao.rows.value.getValue("Food").dirty)
    }

    @Test
    fun settingAgainReplacesAndKeepsTheOriginalSpelling() = runBlocking {
        repo.setBudget("Food", 5000.0)
        now = 2_000L
        repo.setBudget(" food ", 6000.0)

        val list = repo.observeBudgets().first()
        assertEquals(1, list.size)
        assertEquals("Food", list[0].category)
        assertEquals(6000.0, list[0].monthlyAmount, 0.0)
        assertEquals(2_000L, list[0].updatedAt)
    }

    @Test
    fun invalidInputIsRejected() = runBlocking {
        assertTrue(repo.setBudget("", 100.0) is Result.Error)
        assertTrue(repo.setBudget("   ", 100.0) is Result.Error)
        assertTrue(repo.setBudget("Food", 0.0) is Result.Error)
        assertTrue(repo.setBudget("Food", -5.0) is Result.Error)
        assertTrue(repo.setBudget("Food", Double.NaN) is Result.Error)
        assertTrue(repo.setBudget("Food", Double.POSITIVE_INFINITY) is Result.Error)
        assertTrue(repo.observeBudgets().first().isEmpty())
    }

    @Test
    fun removeKeepsADirtyTombstoneAndHidesTheBudget() = runBlocking {
        repo.setBudget("Food", 5000.0)
        dao.markClean(listOf("Food"))
        now = 3_000L

        assertTrue(repo.removeBudget("food") is Result.Success)

        assertTrue(repo.observeBudgets().first().isEmpty())
        val tomb = dao.rows.value.getValue("Food")
        assertEquals(1, tomb.deleted)
        assertEquals(1, tomb.dirty)
        assertEquals(3_000L, tomb.updatedAt)
    }

    @Test
    fun removingAMissingBudgetSucceeds() = runBlocking {
        assertTrue(repo.removeBudget("Nothing") is Result.Success)
    }

    @Test
    fun settingAgainAfterRemoveBringsItBack() = runBlocking {
        repo.setBudget("Food", 5000.0)
        repo.removeBudget("Food")
        repo.setBudget("Food", 4000.0)
        val list = repo.observeBudgets().first()
        assertEquals(listOf(4000.0), list.map { it.monthlyAmount })
        assertEquals(0, dao.rows.value.getValue("Food").deleted)
    }

    // --- Config mirror -------------------------------------------------------------------------------------

    private class FakeConfigRepository : ConfigRepository {
        val rows = mutableListOf<ConfigRow>()
        override suspend fun readAll(spreadsheetId: String): Result<List<ConfigRow>> = Result.Success(rows.toList())
        override suspend fun upsert(spreadsheetId: String, rows: List<ConfigRow>): Result<Unit> {
            for (r in rows) {
                this.rows.removeAll { it.compositeKey == r.compositeKey }
                this.rows.add(r)
            }
            return Result.Success(Unit)
        }
        override suspend fun tombstone(spreadsheetId: String, type: String, key: String, deviceId: String): Result<Unit> =
            Result.Success(Unit)
    }

    /** Gateway backed by a real budget DAO fake; everything except budgets is empty. */
    private class BudgetGateway(val budgetDao: FakeBudgetDao) : ConfigSyncLocalGateway {
        private val real = ConfigSyncLocalGatewayImpl(budgetDao = budgetDao, ioDispatcher = Dispatchers.Unconfined)
        override suspend fun getDirtySnapshot() = DirtyConfigSnapshot(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            budgets = budgetDao.getDirty().map { it.toDomain() }
        )
        override suspend fun markAccountsClean(ids: List<String>) {}
        override suspend fun markCategoriesClean(ids: List<String>) {}
        override suspend fun markCompanionsClean(ids: List<String>) {}
        override suspend fun markTripTagsClean(ids: List<String>) {}
        override suspend fun markPreferencesClean(keys: List<String>) {}
        override suspend fun markSubscriptionsClean(ids: List<String>) {}
        override suspend fun markBudgetsClean(categories: List<String>) = real.markBudgetsClean(categories)
        override suspend fun applyRemoteBudget(category: String, monthlyAmount: Double, updatedAt: Long, deleted: Boolean) =
            real.applyRemoteBudget(category, monthlyAmount, updatedAt, deleted)
        override suspend fun applyRemoteSubscription(subscription: com.financeautomation.domain.model.Subscription, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteAccount(name: String, lastFour: String?, isDefault: Boolean, isArchived: Boolean, displayOrder: Int, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteCategory(name: String, excludedFromBurn: Boolean, isDefault: Boolean, isArchived: Boolean, displayOrder: Int, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteCompanion(name: String, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemoteTripTag(name: String, isActive: Boolean, isArchived: Boolean, updatedAt: Long, deleted: Boolean) {}
        override suspend fun applyRemotePreference(key: String, value: String?, updatedAt: Long, deleted: Boolean) {}
        override suspend fun markAllLocalItemsDirty() {
            budgetDao.getAllIncludingDeleted().forEach { budgetDao.upsert(it.copy(dirty = 1)) }
        }
    }

    private fun useCase(gateway: ConfigSyncLocalGateway, config: FakeConfigRepository) = SyncConfigUseCase(
        configRepository = config,
        localGateway = gateway,
        preferencesRepository = PreferencesRepositoryImpl(isTestHeadless = true),
        ioDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun budgetsPushAsConfigRowsWithCategoryKeyAndAmountInValue1() = runBlocking {
        repo.setBudget("Food", 5000.0)
        repo.setBudget("Health, Fitness", 1250.5)
        val sheet = FakeConfigRepository()

        val result = useCase(BudgetGateway(dao), sheet).push("sheet_1")

        assertTrue("push failed: $result", result is Result.Success)
        val rows = sheet.rows.filter { it.type == ConfigConstants.TYPE_BUDGET }.associateBy { it.key }
        assertEquals(setOf("Food", "Health, Fitness"), rows.keys)
        assertEquals("5000", rows.getValue("Food").value1)
        assertEquals("1250.5", rows.getValue("Health, Fitness").value1)
        assertFalse(rows.getValue("Food").deleted)
        assertTrue("dirty flags cleared", dao.getDirty().isEmpty())
    }

    @Test
    fun aRemovedBudgetPushesATombstone() = runBlocking {
        repo.setBudget("Food", 5000.0)
        val sheet = FakeConfigRepository()
        val uc = useCase(BudgetGateway(dao), sheet)
        uc.push("sheet_1")
        now = 9_000L
        repo.removeBudget("Food")

        uc.push("sheet_1")

        val row = sheet.rows.single { it.type == ConfigConstants.TYPE_BUDGET && it.key == "Food" }
        assertTrue(row.deleted)
    }

    @Test
    fun mirrorThenRestoreBringsBudgetsBackOnAnotherDevice() = runBlocking {
        repo.setBudget("Food", 5000.0)
        repo.setBudget("Transit", 800.0)
        val sheet = FakeConfigRepository()
        useCase(BudgetGateway(dao), sheet).pushLocalDefaults("sheet_1")

        val otherDao = FakeBudgetDao()
        val restore = useCase(BudgetGateway(otherDao), sheet).restoreFromCloud("sheet_1")

        assertTrue("restore failed: $restore", restore is Result.Success)
        val restored = BudgetRepositoryImpl(otherDao, Dispatchers.Unconfined).observeBudgets().first()
        assertEquals(mapOf("Food" to 5000.0, "Transit" to 800.0), restored.associate { it.category to it.monthlyAmount })
        assertTrue("restored rows are not dirty", otherDao.getDirty().isEmpty())
    }

    @Test
    fun aRemoteTombstoneRemovesALocalBudgetOnlyWhenNewer() = runBlocking {
        repo.setBudget("Food", 5000.0) // updatedAt 1000
        dao.markClean(listOf("Food"))
        val sheet = FakeConfigRepository()
        sheet.rows.add(ConfigRow(ConfigConstants.TYPE_BUDGET, "Food", listOf("5000"), Instant.ofEpochMilli(500L), "d", deleted = true))
        useCase(BudgetGateway(dao), sheet).pull("sheet_1")
        assertEquals("older tombstone is ignored", 1, repo.observeBudgets().first().size)

        sheet.rows.clear()
        sheet.rows.add(ConfigRow(ConfigConstants.TYPE_BUDGET, "Food", listOf("5000"), Instant.ofEpochMilli(5_000L), "d", deleted = true))
        useCase(BudgetGateway(dao), sheet).pull("sheet_1")
        assertTrue("newer tombstone removes it", repo.observeBudgets().first().isEmpty())
    }

    @Test
    fun aNewerRemoteAmountWinsAndAnOlderOneLoses() = runBlocking {
        repo.setBudget("Food", 5000.0) // updatedAt 1000
        val sheet = FakeConfigRepository()
        sheet.rows.add(ConfigRow(ConfigConstants.TYPE_BUDGET, "Food", listOf("7000"), Instant.ofEpochMilli(2_000L), "d"))
        useCase(BudgetGateway(dao), sheet).pull("sheet_1")
        assertEquals(7000.0, repo.observeBudgets().first().single().monthlyAmount, 0.0)

        sheet.rows.clear()
        sheet.rows.add(ConfigRow(ConfigConstants.TYPE_BUDGET, "Food", listOf("1"), Instant.ofEpochMilli(1_500L), "d"))
        useCase(BudgetGateway(dao), sheet).pull("sheet_1")
        assertEquals(7000.0, repo.observeBudgets().first().single().monthlyAmount, 0.0)
    }

    @Test
    fun badRemoteRowsAreSkipped() = runBlocking {
        val sheet = FakeConfigRepository()
        sheet.rows.add(ConfigRow(ConfigConstants.TYPE_BUDGET, "Food", listOf("abc"), Instant.ofEpochMilli(2_000L), "d"))
        sheet.rows.add(ConfigRow(ConfigConstants.TYPE_BUDGET, "Transit", listOf("-5"), Instant.ofEpochMilli(2_000L), "d"))
        sheet.rows.add(ConfigRow(ConfigConstants.TYPE_BUDGET, "Health", listOf("1,200"), Instant.ofEpochMilli(2_000L), "d"))
        useCase(BudgetGateway(dao), sheet).pull("sheet_1")
        assertEquals(mapOf("Health" to 1200.0), repo.observeBudgets().first().associate { it.category to it.monthlyAmount })
        assertNull(dao.rows.value["Food"])
    }
}
