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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

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

    private class RecordingTransactionRepository : TransactionRepository {
        val requeuedYears = mutableListOf<Int>()

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
        override suspend fun reconcileAllMonths(): Result<Int> = Result.Success(0)
        override suspend fun requeueYearForNewWorkbook(year: Int) {
            requeuedYears.add(year)
        }
    }
}
