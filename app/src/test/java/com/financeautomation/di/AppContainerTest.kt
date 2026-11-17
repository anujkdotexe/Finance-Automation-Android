package com.financeautomation.di

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import com.financeautomation.data.remote.TokenProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
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

        val requeuedYears = mutableListOf<Int>()
        val recordingRepo = Proxy.newProxyInstance(
            com.financeautomation.domain.repository.TransactionRepository::class.java.classLoader,
            arrayOf(com.financeautomation.domain.repository.TransactionRepository::class.java)
        ) { _, method, args ->
            if (method.name == "requeueYearForNewWorkbook") {
                requeuedYears.add(args!![0] as Int)
                null
            } else if (kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(method.returnType)) {
                kotlinx.coroutines.flow.emptyFlow<Any?>()
            } else null
        } as com.financeautomation.domain.repository.TransactionRepository

        val resolver = container.buildWorkbookResolver(txnRepoProvider = { recordingRepo })
        resolver.onWorkbookReplaced(2026, "old_id", "new_id")

        org.junit.Assert.assertEquals(listOf(2026), requeuedYears)
    }
}
