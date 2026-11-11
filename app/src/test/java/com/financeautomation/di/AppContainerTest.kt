package com.financeautomation.di

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import com.financeautomation.data.remote.TokenProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
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
            override fun getSystemService(name: String): Any? {
                return if (name == Context.WINDOW_SERVICE) windowManager else null
            }
        }
    }

    private val fakeTokenProvider = object : TokenProvider {
        override fun getAccessToken(): String? = "test_token"
        override fun refreshAccessToken(): String? = "test_token"
    }

    private val all32LazyProperties: List<Pair<String, (AppContainer) -> Any?>> = listOf(
        "okHttpClient" to { it.okHttpClient },
        "googleDriveClient" to { it.googleDriveClient },
        "googleSheetsClient" to { it.googleSheetsClient },
        "workbookResolver" to { it.workbookResolver },
        "transactionRepository" to { it.transactionRepository },
        "preferencesRepository" to { it.preferencesRepository },
        "peopleRepository" to { it.peopleRepository },
        "accountRepository" to { it.accountRepository },
        "categoryRepository" to { it.categoryRepository },
        "tripTagRepository" to { it.tripTagRepository },
        "configRepository" to { it.configRepository },
        "configSyncLocalGateway" to { it.configSyncLocalGateway },
        "configSyncCoordinator" to { it.configSyncCoordinator },
        "configSheetManager" to { it.configSheetManager },
        "parseNotificationUseCase" to { it.parseNotificationUseCase },
        "syncTransactionsUseCase" to { it.syncTransactionsUseCase },
        "syncConfigUseCase" to { it.syncConfigUseCase },
        "regenerateDashboardUseCase" to { it.regenerateDashboardUseCase },
        "calculateSplitUseCase" to { it.calculateSplitUseCase },
        "logExpenseUseCase" to { it.logExpenseUseCase },
        "predictCategoryUseCase" to { it.predictCategoryUseCase },
        "nettingUseCase" to { it.nettingUseCase },
        "settlementWaterfallUseCase" to { it.settlementWaterfallUseCase },
        "repaymentWaterfallUseCase" to { it.repaymentWaterfallUseCase },
        "carryForwardUseCase" to { it.carryForwardUseCase },
        "voidTransactionUseCase" to { it.voidTransactionUseCase },
        "yearCloseUseCase" to { it.yearCloseUseCase },
        "accountingSyncExecutor" to { it.accountingSyncExecutor },
        "foregroundWatcher" to { it.foregroundWatcher },
        "overlayController" to { it.overlayController },
        "peopleListRebuilder" to { it.peopleListRebuilder },
        "getOpenDebtsForPersonUseCase" to { it.getOpenDebtsForPersonUseCase }
    )

    @Test
    fun appContainer_touchesAll32LazyProperties_withoutCircularDependencyOrStackOverflow() {
        val container: AppContainer = DefaultAppContainer(
            applicationContext = createFakeContext(),
            providedTokenProvider = fakeTokenProvider,
            providedDatabaseReady = CompletableDeferred()
        )

        assertEquals("Expected exactly 32 lazy properties in test suite", 32, all32LazyProperties.size)

        for ((name, getter) in all32LazyProperties) {
            val instance = getter(container)
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
}
