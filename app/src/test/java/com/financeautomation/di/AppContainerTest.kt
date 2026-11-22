package com.financeautomation.di

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import com.financeautomation.data.remote.TokenProvider
import kotlinx.coroutines.CompletableDeferred
import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.financeautomation.data.local.FinanceDatabase
import com.financeautomation.data.local.dao.*
import com.financeautomation.service.NotificationListenerServiceImpl
import com.financeautomation.service.NotificationProcessor

@Suppress("UNCHECKED_CAST")
internal inline fun <reified T> fakeDao(): T {
    return Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, _, _ -> null } as T
}

internal class FakeFinanceDatabase : FinanceDatabase() {
    val pendingCapture: PendingCaptureDao = fakeDao()
    val handledNotification: HandledNotificationDao = fakeDao()
    val account: AccountDao = fakeDao()
    val preference: PreferenceDao = fakeDao()
    val transaction: TransactionDao = fakeDao()

    override fun transactionDao(): TransactionDao = transaction
    override fun companionDao(): CompanionDao = fakeDao()
    override fun categoryDao(): CategoryDao = fakeDao()
    override fun syncQueueDao(): SyncQueueDao = fakeDao()
    override fun accountDao(): AccountDao = account
    override fun tripTagDao(): TripTagDao = fakeDao()
    override fun preferenceDao(): PreferenceDao = preference
    override fun lineageDao(): LineageDao = fakeDao()
    override fun pendingCaptureDao(): PendingCaptureDao = pendingCapture
    override fun handledNotificationDao(): HandledNotificationDao = handledNotification
    override fun categoryStatDao(): CategoryStatDao = fakeDao()
    override fun pipelineEventDao(): PipelineEventDao = fakeDao()
    override fun payableDao(): PayableDao = fakeDao()
    override fun subscriptionDao(): SubscriptionDao = fakeDao()
    override fun subscriptionCycleDao(): SubscriptionCycleDao = fakeDao()
    override fun inflowDao(): InflowDao = fakeDao()
    override fun budgetDao(): BudgetDao = fakeDao()

    override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper = fakeDao()
    override fun createInvalidationTracker(): InvalidationTracker =
        InvalidationTracker(this, emptyMap<String, String>(), emptyMap<String, Set<String>>(), "transactions")
    override fun clearAllTables() {}
}

class AppContainerTest {

    private fun createFakeContext(): Context {
        val windowManager = Proxy.newProxyInstance(
            Context::class.java.classLoader,
            arrayOf(WindowManager::class.java)
        ) { _, _, _ -> null } as WindowManager

        return object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.financeautomation"
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): java.io.File = java.io.File(System.getProperty("java.io.tmpdir") ?: ".", "test_files").apply { mkdirs() }
            override fun getSystemService(name: String): Any? {
                return if (name == Context.WINDOW_SERVICE) windowManager else null
            }
        }
    }

    private val fakeTokenProvider = object : TokenProvider {
        override fun getAccessToken(): String? = "test_token"
        override fun refreshAccessToken(): String? = "test_token"
    }

    /** Not resolved here: building it calls the Play Services Identity client, which needs a real device. */
    private val notResolvedInUnitTests = setOf("googleAuthTokenProvider")

    @Test
    fun appContainer_resolvesEveryRealProperty_withoutCircularDependencyOrStackOverflow() {
        val container: AppContainer = DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred()
        )

        val getters = AppContainer::class.java.methods
            .filter { it.parameterTypes.isEmpty() && it.name.startsWith("get") && it.returnType != Void.TYPE }
        assertTrue("reflection found only ${getters.size} container properties", getters.size >= 33)

        for (getter in getters) {
            val name = getter.name.removePrefix("get").replaceFirstChar { it.lowercase() }
            if (name in notResolvedInUnitTests) continue
            val instance = getter.invoke(container)
            assertNotNull("Property $name resolved to null", instance)
        }
    }

    @Test
    fun appContainer_touchingPreferencesRepositoryFirst_doesNotCauseRecursion() {
        val container: AppContainer = DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred()
        )

        // Specifically test the startup trigger path that caused the Realme crash:
        // MainActivity.onResume -> getPreferencesRepository -> getSyncConfigUseCase -> getConfigSyncCoordinator
        assertNotNull(container.preferencesRepository)
        assertNotNull(container.syncConfigUseCase)
        assertNotNull(container.configSyncCoordinator)
    }

    @Test
    fun appContainer_touchingCoordinatorFirst_doesNotCauseRecursion() {
        val container: AppContainer = DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred()
        )

        assertNotNull(container.configSyncCoordinator)
        assertNotNull(container.preferencesRepository)
    }

    @Test
    fun appContainer_touchingAccountRepositoryFirst_doesNotCauseRecursion() {
        val container: AppContainer = DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred()
        )

        assertNotNull(container.accountRepository)
        assertNotNull(container.configSyncCoordinator)
    }

    @Test
    fun appContainerWiresTheReplacementCallbackToTheRepository() = kotlinx.coroutines.runBlocking {
        val container = DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred()
        )

        val recordingRepo = RecordingTransactionRepository()

        val resolver = container.buildWorkbookResolver(txnRepoProvider = { recordingRepo })
        resolver.onWorkbookReplaced(2026, "old_id", "new_id")

        assertEquals(listOf(2026), recordingRepo.requeuedYears)
    }

    @Test
    fun getNotificationProcessor_calledMultipleTimes_returnsSameCachedInstance() = kotlinx.coroutines.runBlocking {
        val fakeDb = FakeFinanceDatabase()
        val container: AppContainer = DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred(fakeDb)
        )

        val first = container.getNotificationProcessor()
        val second = container.getNotificationProcessor()

        assertNotNull(first)
        assertSame("NotificationProcessor must be a cached singleton on the container", first, second)
    }

    @Test
    fun notificationService_handleNotification_retrievesProcessorFromAppContainer() = kotlinx.coroutines.runBlocking {
        var containerProcessorCalled = false
        val dummyProcessor = NotificationProcessor(
            pendingCaptureDao = null,
            handledNotificationDao = null,
            accountDao = null,
            preferenceDao = null,
            ownPackageName = "com.financeautomation"
        )

        val fakeAppContainer = object : AppContainer by DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred(FakeFinanceDatabase())
        ) {
            override suspend fun getNotificationProcessor(): NotificationProcessor {
                containerProcessorCalled = true
                return dummyProcessor
            }
        }

        val fakeApp = com.financeautomation.FinanceApplication()
        val appContainerField = com.financeautomation.FinanceApplication::class.java.getDeclaredField("appContainer")
        appContainerField.isAccessible = true
        appContainerField.set(fakeApp, fakeAppContainer)

        val service = object : NotificationListenerServiceImpl() {
            override fun getApplicationContext(): Context = fakeApp
        }

        service.handleNotification(
            sourcePackage = "com.example.payment",
            title = "Test",
            text = "Paid Rs 100",
            postTime = System.currentTimeMillis()
        )

        val start = System.currentTimeMillis()
        while (!containerProcessorCalled && System.currentTimeMillis() - start < 2000) {
            kotlinx.coroutines.delay(20)
        }

        assertTrue("NotificationListenerServiceImpl must query container.getNotificationProcessor()", containerProcessorCalled)
    }

    @Test
    fun foregroundWatcher_receivesCategoryDaoProvider() {
        val container: AppContainer = DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred()
        )
        val watcher = container.foregroundWatcher
        val field = com.financeautomation.service.ForegroundWatcher::class.java.getDeclaredField("categoryDaoProvider")
        field.isAccessible = true
        val provider = field.get(watcher)
        assertNotNull("ForegroundWatcher must receive non-null categoryDaoProvider (F23)", provider)
    }

    private class RecordingTransactionRepository : TransactionRepository {
        val requeuedYears = mutableListOf<Int>()

        override fun getTransactions(): Flow<List<Transaction>> =
            throw UnsupportedOperationException("unexpected call: getTransactions")
        override fun getNeedsReview(): Flow<List<Transaction>> =
            throw UnsupportedOperationException("unexpected call: getNeedsReview")
        override suspend fun getTransactionById(id: String): Transaction? =
            throw UnsupportedOperationException("unexpected call: getTransactionById")
        override suspend fun insertTransaction(transaction: Transaction): Result<Unit> =
            throw UnsupportedOperationException("unexpected call: insertTransaction")
        override suspend fun updateTransaction(transaction: Transaction): Result<Unit> =
            throw UnsupportedOperationException("unexpected call: updateTransaction")
        override suspend fun voidTransaction(id: String): Result<Unit> =
            throw UnsupportedOperationException("unexpected call: voidTransaction")
        override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> =
            throw UnsupportedOperationException("unexpected call: discardLocalOnly")
        override suspend fun undoRecentSave(id: String): Result<Unit> =
            throw UnsupportedOperationException("unexpected call: undoRecentSave")
        override suspend fun findRecentSimilar(amount: Double, windowMillis: Long): List<Transaction> =
            throw UnsupportedOperationException("unexpected call: findRecentSimilar")
        override suspend fun syncPendingTransactions(): Result<Int> =
            throw UnsupportedOperationException("unexpected call: syncPendingTransactions")
        override suspend fun logCredit(settler: String, amount: Double, explicitRefs: List<String>, account: String, inflowType: String, reason: String, timestamp: Long): Result<String> =
            throw UnsupportedOperationException("unexpected call: logCredit")
        override suspend fun logPayable(creditor: String, amount: Double, whatFor: String, timestamp: Long, category: String): Result<String> =
            throw UnsupportedOperationException("unexpected call: logPayable")
        override suspend fun logRepayment(creditor: String, amount: Double, explicitRef: String?, timestamp: Long): Result<String> =
            throw UnsupportedOperationException("unexpected call: logRepayment")
        override suspend fun carryForward(targetTab: String, sourceTab: String?): Result<Unit> =
            throw UnsupportedOperationException("unexpected call: carryForward")
        override suspend fun closeYear(year: Int): Result<Unit> =
            throw UnsupportedOperationException("unexpected call: closeYear")
        override suspend fun reconcileAllMonths(): Result<Int> =
            throw UnsupportedOperationException("unexpected call: reconcileAllMonths")
        override suspend fun requeueYearForNewWorkbook(year: Int) {
            requeuedYears.add(year)
        }
    }
}
