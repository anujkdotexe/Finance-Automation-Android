package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.dao.CategoryDao
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.data.local.entity.CategoryEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.ChartManager
import com.financeautomation.data.remote.DashboardFormulaBuilder
import com.financeautomation.data.repository.AccountRepositoryImpl
import com.financeautomation.data.repository.CategoryRepositoryImpl
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.presentation.viewmodel.SettingsCustomizationCoordinator
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import com.financeautomation.testutil.createTestAccountingSyncExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class UserDefinedCategoriesAndAccountsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeCategoryDao : CategoryDao {
        val items = mutableMapOf<String, CategoryEntity>()
        override fun getCategoriesFlow(): Flow<List<CategoryEntity>> = flowOf(items.values.toList())
        override fun getActiveCategoriesFlow(): Flow<List<CategoryEntity>> = flowOf(items.values.filter { !it.isArchived })
        override suspend fun getAllCategories(): List<CategoryEntity> = items.values.toList()
        override suspend fun getActiveCategories(): List<CategoryEntity> = items.values.filter { !it.isArchived }
        override suspend fun getById(id: String): CategoryEntity? = items[id]
        override suspend fun getByName(name: String): CategoryEntity? = items.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun count(): Int = items.size
        override suspend fun insert(category: CategoryEntity) { items[category.id] = category }
        override suspend fun insertAll(categories: List<CategoryEntity>) { categories.forEach { insert(it) } }
        override suspend fun update(category: CategoryEntity) { items[category.id] = category }
        override suspend fun delete(category: CategoryEntity) { items.remove(category.id) }
        override suspend fun deleteById(id: String) { items.remove(id) }
        override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(isExcludedFromLivingBurn = excluded) }
        }
        override suspend fun archiveCategory(id: String, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(isArchived = true) }
        }
        override suspend fun unarchiveCategory(id: String, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(isArchived = false) }
        }
        override suspend fun updateName(id: String, name: String, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(name = name) }
        }
        override suspend fun updateDisplayOrder(id: String, displayOrder: Int, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(displayOrder = displayOrder) }
        }
        override suspend fun getDirtyCategories(): List<CategoryEntity> = emptyList()
        override suspend fun markClean(ids: List<String>) {}
    }

    private class FakeAccountDao : AccountDao {
        val items = mutableMapOf<String, AccountEntity>()
        override fun getAccountsFlow(): Flow<List<AccountEntity>> = flowOf(items.values.toList())
        override fun getActiveAccountsFlow(): Flow<List<AccountEntity>> = flowOf(items.values.filter { !it.isArchived })
        override suspend fun getAllAccounts(): List<AccountEntity> = items.values.toList()
        override suspend fun getActiveAccounts(): List<AccountEntity> = items.values.filter { !it.isArchived }
        override suspend fun getById(id: String): AccountEntity? = items[id]
        override suspend fun getByName(name: String): AccountEntity? = items.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override suspend fun getDefaultAccount(): AccountEntity? = items.values.firstOrNull { it.isDefault }
        override suspend fun count(): Int = items.size
        override suspend fun insert(account: AccountEntity) { items[account.id] = account }
        override suspend fun insertAll(accounts: List<AccountEntity>) { accounts.forEach { insert(it) } }
        override suspend fun update(account: AccountEntity) { items[account.id] = account }
        override suspend fun delete(account: AccountEntity) { items.remove(account.id) }
        override suspend fun deleteById(id: String) { items.remove(id) }
        override suspend fun clearDefaultFlag() { items.values.forEach { items[it.id] = it.copy(isDefault = false) } }
        override suspend fun setDefaultFlag(id: String) { items[id]?.let { items[id] = it.copy(isDefault = true) } }
        override suspend fun archiveAccount(id: String, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(isArchived = true) }
        }
        override suspend fun unarchiveAccount(id: String, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(isArchived = false) }
        }
        override suspend fun updateDisplayOrder(id: String, displayOrder: Int, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(displayOrder = displayOrder) }
        }
        override suspend fun updateName(id: String, name: String, updatedAt: Long) {
            items[id]?.let { items[id] = it.copy(name = name) }
        }
        override suspend fun getDirtyAccounts(): List<AccountEntity> = emptyList()
        override suspend fun markClean(ids: List<String>) {}
        override suspend fun makeOnlyDefault(id: String) { clearDefaultFlag(); setDefaultFlag(id) }
    }

    private fun mockPreferencesRepo(): PreferencesRepository =
        Proxy.newProxyInstance(
            PreferencesRepository::class.java.classLoader,
            arrayOf(PreferencesRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getSpreadsheetId" -> flowOf("test_sheet")
                "getSpreadsheetIdSync" -> "test_sheet"
                "getDefaultAccount" -> flowOf("Other")
                "getDefaultAccountSync" -> "Other"
                else -> null
            }
        } as PreferencesRepository

    private fun mockPeopleRepo(): PeopleRepository =
        Proxy.newProxyInstance(
            PeopleRepository::class.java.classLoader,
            arrayOf(PeopleRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getCompanions" -> flowOf(emptyList<com.financeautomation.domain.model.Companion>())
                else -> null
            }
        } as PeopleRepository

    private fun mockTripTagRepo(): TripTagRepository =
        Proxy.newProxyInstance(
            TripTagRepository::class.java.classLoader,
            arrayOf(TripTagRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAllTripTags" -> flowOf(emptyList<com.financeautomation.domain.model.TripTag>())
                else -> null
            }
        } as TripTagRepository

    private fun collectStrings(node: Any?): List<String> = when (node) {
        is Map<*, *> -> node.entries.flatMap { (k, v) ->
            if (k == "stringValue" && v is String) listOf(v) else collectStrings(v)
        }
        is List<*> -> node.flatMap { collectStrings(it) }
        else -> emptyList()
    }

    @Test
    fun elevenCategoriesExpandsDashboardRangeAndDoughnutChart() {
        val categories = (1..11).map { i ->
            Category(
                id = "cat_$i",
                name = "Category $i",
                isDefault = false,
                isExcludedFromLivingBurn = false,
                isArchived = false,
                displayOrder = i - 1
            )
        }

        val requests = DashboardFormulaBuilder.buildDashboardFormulaRequests(
            dashboardSheetId = 1,
            categories = categories
        )

        // Find updateCells request for Category Table (col 24 to 26)
        val categoryUpdateRequest = requests.first { req ->
            val updateCells = req["updateCells"] as? Map<*, *>
            val range = updateCells?.get("range") as? Map<*, *>
            range?.get("startColumnIndex") == 24
        }
        val updateRange = (categoryUpdateRequest["updateCells"] as Map<*, *>)["range"] as Map<*, *>
        val endRowIndex = updateRange["endRowIndex"] as Int
        assertTrue("Category table endRowIndex must scale beyond 15 for 11 categories", endRowIndex >= 16)

        // Verify Doughnut chart scales its range
        val chartRequests = ChartManager.buildChartRequests(
            dashboardSheetId = 1,
            columnChartColorPrimary = mapOf("red" to 0.1f),
            columnChartColorSecondary = mapOf("red" to 0.2f),
            categoryCount = 11
        )
        val doughnut = chartRequests.first { req ->
            val addChart = req["addChart"] as? Map<*, *>
            val chart = addChart?.get("chart") as? Map<*, *>
            val spec = chart?.get("spec") as? Map<*, *>
            spec?.get("title") == ChartManager.DOUGHNUT_CHART_TITLE
        }
        val spec = ((doughnut["addChart"] as Map<*, *>)["chart"] as Map<*, *>)["spec"] as Map<*, *>
        val pieChart = spec["pieChart"] as Map<*, *>
        val domainSources = ((pieChart["domain"] as Map<*, *>)["sourceRange"] as Map<*, *>)["sources"] as List<Map<*, *>>
        assertEquals(16, domainSources[0]["endRowIndex"])
    }

    @Test
    fun twentyFiveCategoriesExpandsDashboardRangeAndDoughnutChart() {
        val categories = (1..25).map { i ->
            Category(
                id = "cat_$i",
                name = "Category $i",
                isDefault = false,
                isExcludedFromLivingBurn = false,
                isArchived = false,
                displayOrder = i - 1
            )
        }

        val requests = DashboardFormulaBuilder.buildDashboardFormulaRequests(
            dashboardSheetId = 1,
            categories = categories
        )

        val categoryUpdateRequest = requests.first { req ->
            val updateCells = req["updateCells"] as? Map<*, *>
            val range = updateCells?.get("range") as? Map<*, *>
            range?.get("startColumnIndex") == 24
        }
        val updateRange = (categoryUpdateRequest["updateCells"] as Map<*, *>)["range"] as Map<*, *>
        val endRowIndex = updateRange["endRowIndex"] as Int
        assertTrue("Category table endRowIndex must scale for 25 categories", endRowIndex >= 30)

        val chartRequests = ChartManager.buildChartRequests(
            dashboardSheetId = 1,
            columnChartColorPrimary = mapOf("red" to 0.1f),
            columnChartColorSecondary = mapOf("red" to 0.2f),
            categoryCount = 25
        )
        val doughnut = chartRequests.first { req ->
            val addChart = req["addChart"] as? Map<*, *>
            val chart = addChart?.get("chart") as? Map<*, *>
            val spec = chart?.get("spec") as? Map<*, *>
            spec?.get("title") == ChartManager.DOUGHNUT_CHART_TITLE
        }
        val spec = ((doughnut["addChart"] as Map<*, *>)["chart"] as Map<*, *>)["spec"] as Map<*, *>
        val pieChart = spec["pieChart"] as Map<*, *>
        val domainSources = ((pieChart["domain"] as Map<*, *>)["sourceRange"] as Map<*, *>)["sources"] as List<Map<*, *>>
        assertEquals(30, domainSources[0]["endRowIndex"])
    }

    @Test
    fun categoryTablePreservesUserOrderAndGuaranteesSavingsAndOther() {
        val customOrder = listOf(
            Category("c1", "Transit", false, false, false, displayOrder = 0),
            Category("c2", "Groceries", false, false, false, displayOrder = 1),
            Category("c3", "Food & Drink", false, false, false, displayOrder = 2)
        )

        val requests = DashboardFormulaBuilder.buildDashboardFormulaRequests(
            dashboardSheetId = 1,
            categories = customOrder
        )

        val strings = collectStrings(requests)
        val transitIndex = strings.indexOf("Transit")
        val groceriesIndex = strings.indexOf("Groceries")
        val foodIndex = strings.indexOf("Food & Drink")

        assertTrue("Transit must precede Groceries in user displayOrder", transitIndex < groceriesIndex)
        assertTrue("Groceries must precede Food & Drink in user displayOrder", groceriesIndex < foodIndex)
        assertTrue("Savings must never be dropped even if omitted in input", strings.contains(CategoryConstants.CATEGORY_SAVINGS))
        assertTrue("Other must never be dropped even if omitted in input", strings.contains(CategoryConstants.CATEGORY_OTHER))
    }

    @Test
    fun lockedCategoriesCannotBeRenamedOrArchived() = runBlocking {
        val categoryDao = FakeCategoryDao()
        val catSavings = CategoryEntity("cat_savings", CategoryConstants.CATEGORY_SAVINGS, false, true, false, 0, "Teal", 0L, 0)
        val catSubscriptions = CategoryEntity("cat_subs", CategoryConstants.CATEGORY_SUBSCRIPTIONS, false, false, false, 1, "Indigo", 0L, 0)
        val catCustom = CategoryEntity("cat_custom", "Coffee", false, false, false, 2, "Amber", 0L, 0)
        categoryDao.insert(catSavings)
        categoryDao.insert(catSubscriptions)
        categoryDao.insert(catCustom)

        val categoryRepo = CategoryRepositoryImpl(categoryDao = categoryDao, ioDispatcher = Dispatchers.Unconfined)
        val uiState = MutableStateFlow(
            SettingsUiState(
                categories = listOf(catSavings.toDomain(), catSubscriptions.toDomain(), catCustom.toDomain())
            )
        )

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = mockPreferencesRepo(),
            peopleRepository = mockPeopleRepo(),
            transactionRepository = null,
            payableDao = null,
            accountRepository = null,
            categoryRepository = categoryRepo,
            tripTagRepository = mockTripTagRepo(),
            uiState = uiState,
            scope = this,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Attempt to rename locked category Savings
        coordinator.onRenameCategory("cat_savings", "Investments")
        assertEquals(catSavings.name, categoryDao.getById("cat_savings")?.name)
        assertNotNull(uiState.value.errorMessage)

        // Attempt to rename locked category Subscriptions
        coordinator.onRenameCategory("cat_subs", "Recurring")
        assertEquals(catSubscriptions.name, categoryDao.getById("cat_subs")?.name)

        // Attempt to archive locked category Savings
        coordinator.onArchiveCategory("cat_savings")
        assertFalse(categoryDao.getById("cat_savings")!!.isArchived)

        // Renaming custom category Coffee succeeds
        coordinator.onRenameCategory("cat_custom", "Coffee & Tea")
        assertEquals("Coffee & Tea", categoryDao.getById("cat_custom")?.name)
    }

    @Test
    fun lockedAccountsCannotBeRenamedOrArchivedOrDeleted() = runBlocking {
        val accountDao = FakeAccountDao()
        val accCash = AccountEntity("acc_cash", CategoryConstants.ACCOUNT_CASH, null, isDefault = false, isArchived = false, displayOrder = 0)
        val accOther = AccountEntity("acc_other", CategoryConstants.ACCOUNT_OTHER, null, isDefault = true, isArchived = false, displayOrder = 1)
        val accBank = AccountEntity("acc_bank", "HDFC", "1234", isDefault = false, isArchived = false, displayOrder = 2)
        accountDao.insert(accCash)
        accountDao.insert(accOther)
        accountDao.insert(accBank)

        val accountRepo = AccountRepositoryImpl(accountDao = accountDao, ioDispatcher = Dispatchers.Unconfined)
        val uiState = MutableStateFlow(
            SettingsUiState(
                accounts = listOf(accCash.toDomain(), accOther.toDomain(), accBank.toDomain())
            )
        )

        val coordinator = SettingsCustomizationCoordinator(
            preferencesRepository = mockPreferencesRepo(),
            peopleRepository = mockPeopleRepo(),
            transactionRepository = null,
            payableDao = null,
            accountRepository = accountRepo,
            categoryRepository = null,
            tripTagRepository = mockTripTagRepo(),
            uiState = uiState,
            scope = this,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Attempt to rename Cash
        coordinator.onRenameAccount("acc_cash", "Petty Cash")
        assertEquals(CategoryConstants.ACCOUNT_CASH, accountDao.getById("acc_cash")?.name)

        // Attempt to archive Other
        coordinator.onArchiveAccount("acc_other")
        assertFalse(accountDao.getById("acc_other")!!.isArchived)

        // Attempt to delete Cash
        coordinator.onDeleteAccount("acc_cash")
        assertNotNull(accountDao.getById("acc_cash"))

        // Renaming custom account HDFC succeeds
        coordinator.onRenameAccount("acc_bank", "HDFC Bank", "1234")
        assertEquals("HDFC Bank", accountDao.getById("acc_bank")?.name)
    }

    @Test
    fun categoryRenameEnqueuesSheetUpdatesForSyncedTransactions() = runBlocking {
        val txnDao = InMemoryTransactionDao()
        val syncQueueDao = InMemoryQueueDao()

        val syncedTxn = TransactionEntity.fromDomain(
            Transaction(
                id = "txn_101",
                timestamp = 1700000000000L,
                dateSerial = 45000,
                timeSerial = 0.5,
                totalAmount = 500.0,
                account = "HDFC",
                category = "Dining",
                merchant = "Bistro",
                companions = "Personal",
                myShare = 500.0,
                owedToMe = 0.0,
                status = TransactionStatus.PERSONAL,
                sheetRowIndex = 6,
                syncState = SyncState.SYNCED,
                reviewState = ReviewState.CONFIRMED
            )
        )
        txnDao.insert(syncedTxn)

        val repo = TransactionRepositoryImpl(
            transactionDao = txnDao,
            syncQueueDao = syncQueueDao,
            accountingSyncExecutor = createTestAccountingSyncExecutor(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repo.updateCategoryName("Dining", "Restaurants")
        assertTrue(result is com.financeautomation.core.model.Result.Success)
        assertEquals(1, (result as com.financeautomation.core.model.Result.Success).data)

        // Verify local DB was updated
        val updatedTxn = txnDao.getById("txn_101")
        assertEquals("Restaurants", updatedTxn?.category)

        // Verify OP_UPDATE was enqueued into syncQueue for sheet synchronization
        val queue = syncQueueDao.getAllQueueFlow().first()
        val queuedUpdate = queue.firstOrNull { it.transactionId == "txn_101" && it.operationType == SyncQueueEntity.OP_UPDATE }
        assertNotNull("OP_UPDATE must be enqueued for synced transactions affected by rename", queuedUpdate)
    }

    @Test
    fun accountRenameEnqueuesSheetUpdatesForSyncedTransactions() = runBlocking {
        val txnDao = InMemoryTransactionDao()
        val syncQueueDao = InMemoryQueueDao()

        val syncedTxn = TransactionEntity.fromDomain(
            Transaction(
                id = "txn_201",
                timestamp = 1700000000000L,
                dateSerial = 45000,
                timeSerial = 0.5,
                totalAmount = 1200.0,
                account = "HDFC",
                category = "Groceries",
                merchant = "Supermarket",
                companions = "Personal",
                myShare = 1200.0,
                owedToMe = 0.0,
                status = TransactionStatus.PERSONAL,
                sheetRowIndex = 8,
                syncState = SyncState.SYNCED,
                reviewState = ReviewState.CONFIRMED
            )
        )
        txnDao.insert(syncedTxn)

        val repo = TransactionRepositoryImpl(
            transactionDao = txnDao,
            syncQueueDao = syncQueueDao,
            accountingSyncExecutor = createTestAccountingSyncExecutor(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repo.updateAccountName("HDFC", "HDFC Bank")
        assertTrue(result is com.financeautomation.core.model.Result.Success)
        assertEquals(1, (result as com.financeautomation.core.model.Result.Success).data)

        // Verify local DB was updated
        val updatedTxn = txnDao.getById("txn_201")
        assertEquals("HDFC Bank", updatedTxn?.account)

        // Verify OP_UPDATE was enqueued
        val queue = syncQueueDao.getAllQueueFlow().first()
        val queuedUpdate = queue.firstOrNull { it.transactionId == "txn_201" && it.operationType == SyncQueueEntity.OP_UPDATE }
        assertNotNull("OP_UPDATE must be enqueued for synced transactions affected by account rename", queuedUpdate)
    }

    @Test
    fun reorderCategoriesPersistsAndUpdatesDisplayOrder() = runBlocking {
        val categoryDao = FakeCategoryDao()
        categoryDao.insert(CategoryEntity("c1", "Food", false, false, false, 0, "Teal", 0L, 0))
        categoryDao.insert(CategoryEntity("c2", "Groceries", false, false, false, 1, "Amber", 0L, 0))
        categoryDao.insert(CategoryEntity("c3", "Transit", false, false, false, 2, "Sky", 0L, 0))

        val repo = CategoryRepositoryImpl(categoryDao = categoryDao, ioDispatcher = Dispatchers.Unconfined)
        repo.reorderCategories(listOf("c3", "c1", "c2"))

        assertEquals(0, categoryDao.getById("c3")?.displayOrder)
        assertEquals(1, categoryDao.getById("c1")?.displayOrder)
        assertEquals(2, categoryDao.getById("c2")?.displayOrder)
    }

    @Test
    fun reorderAccountsPersistsAndUpdatesDisplayOrder() = runBlocking {
        val accountDao = FakeAccountDao()
        accountDao.insert(AccountEntity("a1", "Cash", null, false, false, 0))
        accountDao.insert(AccountEntity("a2", "HDFC", "1234", false, false, 1))
        accountDao.insert(AccountEntity("a3", "SBI", "5678", false, false, 2))

        val repo = AccountRepositoryImpl(accountDao = accountDao, ioDispatcher = Dispatchers.Unconfined)
        repo.reorderAccounts(listOf("a2", "a3", "a1"))

        assertEquals(0, accountDao.getById("a2")?.displayOrder)
        assertEquals(1, accountDao.getById("a3")?.displayOrder)
        assertEquals(2, accountDao.getById("a1")?.displayOrder)
    }
}
