package com.financeautomation.di

import android.content.Context
import androidx.room.withTransaction
import com.financeautomation.data.local.ConfigSyncLocalGatewayImpl
import com.financeautomation.data.local.FinanceDatabase
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.ConfigSheetManager
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.data.remote.GoogleApiError
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.ProfileAvatarManager
import com.financeautomation.data.remote.RawHttpErrorStore
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.TokenRecoveryInterceptor
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.data.repository.AccountRepositoryImpl
import com.financeautomation.data.repository.CategoryRepositoryImpl
import com.financeautomation.data.repository.ConfigRepositoryImpl
import com.financeautomation.data.repository.BudgetRepositoryImpl
import com.financeautomation.data.repository.InflowRepositoryImpl
import com.financeautomation.data.repository.PeopleListRebuilder
import com.financeautomation.data.repository.PeopleRepositoryImpl
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.data.repository.SubscriptionRepositoryImpl
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.repository.TripTagRepositoryImpl
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.HandledNotificationDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.presentation.overlay.OverlayController
import com.financeautomation.service.ForegroundWatcher
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.ConfigRepository
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.BudgetRepository
import com.financeautomation.domain.repository.InflowRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.SubscriptionRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.CarryForwardUseCase
import com.financeautomation.domain.usecase.GetOpenDebtsForPersonUseCase
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.domain.usecase.NettingUseCase
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import com.financeautomation.domain.usecase.RegenerateDashboardUseCase
import com.financeautomation.domain.usecase.RepaymentWaterfallUseCase
import com.financeautomation.domain.usecase.SettlementWaterfallUseCase
import com.financeautomation.domain.usecase.SubscriptionActionUseCase
import com.financeautomation.domain.usecase.SubscriptionMatcher
import com.financeautomation.domain.usecase.SubscriptionScheduleUseCase
import com.financeautomation.domain.usecase.SyncConfigUseCase
import com.financeautomation.domain.usecase.SyncTransactionsUseCase
import com.financeautomation.domain.usecase.VoidTransactionUseCase
import com.financeautomation.domain.usecase.YearCloseUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual Dependency Injection container providing application-scoped singletons
 * without reflection or runtime bytecode generation.
 */
interface AppContainer {
    val applicationContext: Context
    val ioDispatcher: CoroutineDispatcher
    val mainDispatcher: CoroutineDispatcher
    val defaultDispatcher: CoroutineDispatcher
    val tokenProvider: TokenProvider?
    val googleAuthTokenProvider: GoogleAuthTokenProvider
    val okHttpClient: OkHttpClient
    val databaseReady: Deferred<FinanceDatabase>
    suspend fun awaitDatabase(): FinanceDatabase
    val googleDriveClient: GoogleDriveClient
    val googleSheetsClient: GoogleSheetsClient
    val workbookResolver: WorkbookResolver
    val profileAvatarManager: ProfileAvatarManager
    val transactionRepository: TransactionRepository
    val preferencesRepository: PreferencesRepository
    val peopleRepository: PeopleRepository
    val accountRepository: AccountRepository
    val categoryRepository: CategoryRepository
    val tripTagRepository: TripTagRepository
    val configRepository: ConfigRepository
    val configSyncLocalGateway: ConfigSyncLocalGateway
    val configSyncCoordinator: ConfigSyncCoordinator
    val configSheetManager: ConfigSheetManager
    val parseNotificationUseCase: ParseNotificationUseCase
    val syncTransactionsUseCase: SyncTransactionsUseCase
    val syncConfigUseCase: SyncConfigUseCase
    val regenerateDashboardUseCase: RegenerateDashboardUseCase
    val calculateSplitUseCase: CalculateSplitUseCase
    val logExpenseUseCase: LogExpenseUseCase
    val predictCategoryUseCase: PredictCategoryUseCase
    val nettingUseCase: NettingUseCase
    val settlementWaterfallUseCase: SettlementWaterfallUseCase
    val repaymentWaterfallUseCase: RepaymentWaterfallUseCase
    val carryForwardUseCase: CarryForwardUseCase
    val voidTransactionUseCase: VoidTransactionUseCase
    val yearCloseUseCase: YearCloseUseCase
    val accountingSyncExecutor: AccountingSyncExecutor
    val foregroundWatcher: ForegroundWatcher
    val overlayController: OverlayController
    val peopleListRebuilder: PeopleListRebuilder
    val getOpenDebtsForPersonUseCase: GetOpenDebtsForPersonUseCase
    val rawHttpErrorStore: com.financeautomation.data.remote.RawHttpErrorStore
    val connectivityObserver: com.financeautomation.core.utils.ConnectivityObserver
    val subscriptionRepository: SubscriptionRepository
    val budgetRepository: BudgetRepository
    val appLockManager: com.financeautomation.domain.security.AppLockManager
    val privacySettings: com.financeautomation.domain.security.PrivacySettings

    /** Loads the saved lock and privacy settings once per process. Safe to call from every entry point. */
    suspend fun ensureAppLockStarted()
    val inflowRepository: InflowRepository
    val subscriptionScheduleUseCase: SubscriptionScheduleUseCase
    val subscriptionMatcher: SubscriptionMatcher
    val subscriptionActionUseCase: SubscriptionActionUseCase
}

/**
 * Default implementation of [AppContainer] instantiated at application startup.
 */
class DefaultAppContainer(
    override val applicationContext: Context,
    providedTokenProvider: TokenProvider? = null,
    providedDatabaseReady: Deferred<FinanceDatabase>? = null
) : AppContainer {

    override val tokenProvider: TokenProvider = providedTokenProvider
        ?: GoogleAuthTokenProvider(applicationContext)

    override val googleAuthTokenProvider: GoogleAuthTokenProvider
        get() = (tokenProvider as? GoogleAuthTokenProvider) ?: GoogleAuthTokenProvider(applicationContext)

    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    override val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
    override val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    override val connectivityObserver: com.financeautomation.core.utils.ConnectivityObserver by lazy {
        com.financeautomation.core.utils.ConnectivityObserver(applicationContext)
    }

    override val rawHttpErrorStore: RawHttpErrorStore by lazy {
        RawHttpErrorStore(applicationContext.filesDir)
    }

    init {
        GoogleApiError.rawHttpErrorStore = rawHttpErrorStore
    }

    /** One gate for the whole app: the rate-limit cooldown and the minimum interval between full ledger reads. */
    val sheetsReadGate: com.financeautomation.data.remote.SheetsReadGate = com.financeautomation.data.remote.SheetsReadGate()

    override val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(TokenRecoveryInterceptor(tokenProvider))
            .addInterceptor(com.financeautomation.data.remote.RateLimitInterceptor(sheetsReadGate))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override val databaseReady: Deferred<FinanceDatabase> = providedDatabaseReady
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            FinanceDatabase.getInstance(applicationContext)
        }

    private val databaseProvider = com.financeautomation.data.local.RetriableProvider<FinanceDatabase> {
        // The first attempt reuses the pre-warm job. If that job failed, the failure is not remembered:
        // every later call opens the database again, so a transient Keystore problem heals without a restart.
        try {
            databaseReady.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (providedDatabaseReady != null) throw e
            FinanceDatabase.getInstanceAsync(applicationContext)
        }
    }

    override suspend fun awaitDatabase(): FinanceDatabase = databaseProvider.get()

    override val googleDriveClient: GoogleDriveClient by lazy {
        GoogleDriveClient(
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider
        )
    }

    override val googleSheetsClient: GoogleSheetsClient by lazy {
        GoogleSheetsClient(
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider
        )
    }

    override val profileAvatarManager: ProfileAvatarManager by lazy {
        ProfileAvatarManager(
            context = applicationContext,
            okHttpClient = okHttpClient,
            ioDispatcher = ioDispatcher
        )
    }

    override val preferencesRepository: PreferencesRepository by lazy {
        PreferencesRepositoryImpl(
            preferenceDaoProvider = { awaitDatabase().preferenceDao() },
            configSyncCoordinatorProvider = { configSyncCoordinator },
            ioDispatcher = ioDispatcher
        )
    }

    override val peopleRepository: PeopleRepository by lazy {
        PeopleRepositoryImpl(
            companionDaoProvider = { awaitDatabase().companionDao() },
            configSyncCoordinatorProvider = { configSyncCoordinator },
            ioDispatcher = ioDispatcher,
            openBalanceChecker = { name ->
                val db = awaitDatabase()
                val transactions = db.transactionDao().getTransactionsFlow().first().map { it.toDomain() }
                val payables = db.payableDao().getPending().map {
                    com.financeautomation.domain.usecase.PersonBalance.OpenPayable(it.paidBy, it.amountIOwe)
                }
                com.financeautomation.domain.usecase.PersonBalance.hasOpenBalance(name, transactions, payables)
            }
        )
    }

    override val accountRepository: AccountRepository by lazy {
        AccountRepositoryImpl(
            accountDaoProvider = { awaitDatabase().accountDao() },
            configSyncCoordinatorProvider = { configSyncCoordinator },
            transactionDaoProvider = { awaitDatabase().transactionDao() },
            ioDispatcher = ioDispatcher
        )
    }

    override val categoryRepository: CategoryRepository by lazy {
        CategoryRepositoryImpl(
            categoryDaoProvider = { awaitDatabase().categoryDao() },
            configSyncCoordinatorProvider = { configSyncCoordinator },
            ioDispatcher = ioDispatcher
        )
    }

    override val tripTagRepository: TripTagRepository by lazy {
        TripTagRepositoryImpl(
            tripTagDaoProvider = { awaitDatabase().tripTagDao() },
            configSyncCoordinatorProvider = { configSyncCoordinator },
            preferencesRepositoryProvider = { preferencesRepository },
            ioDispatcher = ioDispatcher
        )
    }

    override val configRepository: ConfigRepository by lazy {
        ConfigRepositoryImpl(
            googleSheetsClient = googleSheetsClient,
            ioDispatcher = ioDispatcher
        )
    }

    override val configSyncLocalGateway: ConfigSyncLocalGateway by lazy {
        ConfigSyncLocalGatewayImpl(
            dbProvider = { awaitDatabase() },
            ioDispatcher = ioDispatcher
        )
    }

    override val appLockManager: com.financeautomation.domain.security.AppLockManager by lazy {
        com.financeautomation.domain.security.AppLockManager()
    }

    override val privacySettings: com.financeautomation.domain.security.PrivacySettings by lazy {
        com.financeautomation.domain.security.PrivacySettings()
    }

    override suspend fun ensureAppLockStarted() {
        if (appLockManager.isStarted) return
        val prefs = preferencesRepository
        val lockOn = prefs.getAppLockEnabled()
        privacySettings.set(prefs.getHideRecentsPreview())
        privacySettings.setAppLockOn(lockOn)
        appLockManager.onColdStart(lockOn)
    }

    override val budgetRepository: BudgetRepository by lazy {
        BudgetRepositoryImpl(
            budgetDaoProvider = { awaitDatabase().budgetDao() },
            configSyncCoordinatorProvider = { configSyncCoordinator },
            ioDispatcher = ioDispatcher
        )
    }

    override val configSheetManager: ConfigSheetManager by lazy {
        ConfigSheetManager(
            googleSheetsClient = googleSheetsClient,
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider,
            ioDispatcher = ioDispatcher
        )
    }

    override val workbookResolver: WorkbookResolver by lazy {
        WorkbookResolver(
            googleDriveClient = googleDriveClient,
            googleSheetsClient = googleSheetsClient,
            preferencesRepository = preferencesRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            syncConfigUseCase = syncConfigUseCase,
            regenerateDashboardUseCase = regenerateDashboardUseCase,
            ioDispatcher = ioDispatcher
        )
    }

    override val transactionRepository: TransactionRepository by lazy {
        TransactionRepositoryImpl(
            transactionDaoProvider = { awaitDatabase().transactionDao() },
            syncQueueDaoProvider = { awaitDatabase().syncQueueDao() },
            payableDaoProvider = { awaitDatabase().payableDao() },
            lineageDaoProvider = { awaitDatabase().lineageDao() },
            inflowDaoProvider = { awaitDatabase().inflowDao() },
            databaseProvider = { awaitDatabase() },
            googleSheetsClient = googleSheetsClient,
            preferencesRepository = preferencesRepository,
            workbookResolver = workbookResolver,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            syncConfigUseCase = syncConfigUseCase,
            calculateSplitUseCase = calculateSplitUseCase,
            nettingUseCase = nettingUseCase,
            voidTransactionUseCase = voidTransactionUseCase,
            accountingSyncExecutor = accountingSyncExecutor,
            ioDispatcher = ioDispatcher,
            readGate = sheetsReadGate,
            modifiedTimeProvider = { id -> googleDriveClient.getModifiedTime(id) }
        ).also { impl ->
            // WorkManager keeps the retry alive across process death, unlike a plain coroutine
            impl.onTriggerSync = {
                com.financeautomation.service.worker.SyncTransactionsWorker.enqueueExpedited(applicationContext)
            }
            impl.onSyncCompleted = {
                com.financeautomation.service.MonthBurnWidgetProvider.requestUpdate(applicationContext)
            }
        }
    }

    override val inflowRepository: InflowRepository by lazy {
        InflowRepositoryImpl(
            inflowDaoProvider = { awaitDatabase().inflowDao() },
            ioDispatcher = ioDispatcher,
            syncQueueDaoProvider = { awaitDatabase().syncQueueDao() },
            transactionRunner = { block -> awaitDatabase().withTransaction { block() } },
            settledDebtsCheck = { id ->
                preferencesRepository.getPreference(com.financeautomation.data.repository.SettledInflowMarker.key(id)) == "1"
            }
        ).also { impl ->
            // WorkManager keeps the retry alive across process death, unlike a plain coroutine
            impl.onTriggerSync = {
                com.financeautomation.service.worker.SyncTransactionsWorker.enqueueExpedited(applicationContext)
            }
        }
    }

    override val subscriptionRepository: SubscriptionRepository by lazy {
        SubscriptionRepositoryImpl(
            subscriptionDaoProvider = { awaitDatabase().subscriptionDao() },
            cycleDaoProvider = { awaitDatabase().subscriptionCycleDao() },
            configSyncCoordinatorProvider = { configSyncCoordinator },
            ioDispatcher = ioDispatcher
        )
    }

    override val subscriptionScheduleUseCase: SubscriptionScheduleUseCase by lazy {
        SubscriptionScheduleUseCase()
    }

    override val subscriptionMatcher: SubscriptionMatcher by lazy {
        SubscriptionMatcher(subscriptionRepository)
    }

    override val subscriptionActionUseCase: SubscriptionActionUseCase by lazy {
        SubscriptionActionUseCase(
            repository = subscriptionRepository,
            logExpenseUseCase = logExpenseUseCase,
            createCapture = { sub, timestamp ->
                val capture = PendingCaptureEntity(
                    id = "cap_${java.util.UUID.randomUUID().toString().replace("-", "")}",
                    sourcePackage = "manual",
                    amount = null,
                    payee = sub.name,
                    inferredAccount = sub.account,
                    timestamp = timestamp,
                    kind = PendingCaptureEntity.KIND_DEBIT,
                    flags = PendingCaptureEntity.FLAG_NEEDS_AMOUNT + "," +
                        PendingCaptureEntity.FLAG_SUBSCRIPTION_PREFIX + sub.id,
                    state = PendingCaptureEntity.STATE_SHOWN,
                    notificationKey = null,
                    contentHash = null,
                    updatedAt = System.currentTimeMillis()
                )
                awaitDatabase().pendingCaptureDao().insert(capture)
                capture.id
            }
        )
    }

    override val parseNotificationUseCase: ParseNotificationUseCase by lazy {
        ParseNotificationUseCase(ownPackageName = applicationContext.packageName)
    }

    override val syncTransactionsUseCase: SyncTransactionsUseCase by lazy {
        SyncTransactionsUseCase(transactionRepository = transactionRepository)
    }

    override val syncConfigUseCase: SyncConfigUseCase by lazy {
        SyncConfigUseCase(
            configRepository = configRepository,
            localGateway = configSyncLocalGateway,
            preferencesRepository = preferencesRepository,
            configSheetManager = configSheetManager,
            ioDispatcher = ioDispatcher
        )
    }

    override val configSyncCoordinator: ConfigSyncCoordinator by lazy {
        ConfigSyncCoordinator(
            syncConfigUseCase = syncConfigUseCase,
            syncQueueDaoProvider = { awaitDatabase().syncQueueDao() },
            localGateway = configSyncLocalGateway,
            applicationScope = CoroutineScope(SupervisorJob() + ioDispatcher),
            ioDispatcher = ioDispatcher
        ).also { coordinator ->
            coordinator.onTriggerQueueSync = {
                com.financeautomation.service.worker.SyncTransactionsWorker.enqueueExpedited(applicationContext)
            }
        }
    }

    override val regenerateDashboardUseCase: RegenerateDashboardUseCase by lazy {
        RegenerateDashboardUseCase(
            googleSheetsClient = googleSheetsClient,
            preferencesRepository = preferencesRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider,
            ioDispatcher = ioDispatcher
        )
    }

    override val calculateSplitUseCase: CalculateSplitUseCase by lazy {
        CalculateSplitUseCase()
    }

    override val logExpenseUseCase: LogExpenseUseCase by lazy {
        LogExpenseUseCase(
            transactionRepository = transactionRepository,
            calculateSplitUseCase = calculateSplitUseCase
        )
    }

    override val predictCategoryUseCase: PredictCategoryUseCase by lazy {
        PredictCategoryUseCase()
    }

    override val nettingUseCase: NettingUseCase by lazy {
        NettingUseCase()
    }

    override val settlementWaterfallUseCase: SettlementWaterfallUseCase by lazy {
        SettlementWaterfallUseCase()
    }

    override val repaymentWaterfallUseCase: RepaymentWaterfallUseCase by lazy {
        RepaymentWaterfallUseCase(nettingUseCase = nettingUseCase)
    }

    override val carryForwardUseCase: CarryForwardUseCase by lazy {
        CarryForwardUseCase()
    }

    override val voidTransactionUseCase: VoidTransactionUseCase by lazy {
        VoidTransactionUseCase()
    }

    override val yearCloseUseCase: YearCloseUseCase by lazy {
        YearCloseUseCase()
    }

    override val accountingSyncExecutor: AccountingSyncExecutor by lazy {
        AccountingSyncExecutor(
            googleSheetsClient = googleSheetsClient,
            googleDriveClient = googleDriveClient,
            syncQueueDaoProvider = { awaitDatabase().syncQueueDao() },
            lineageDaoProvider = { awaitDatabase().lineageDao() },
            transactionDaoProvider = { awaitDatabase().transactionDao() },
            accountsProvider = { accountRepository.getAllAccounts(includeArchived = false).map { it.name } },
            categoriesProvider = { categoryRepository.getAllCategories(includeArchived = false).map { it.name } },
            settlementWaterfallUseCase = settlementWaterfallUseCase,
            repaymentWaterfallUseCase = repaymentWaterfallUseCase,
            carryForwardUseCase = carryForwardUseCase,
            nettingUseCase = nettingUseCase,
            yearCloseUseCase = yearCloseUseCase
        )
    }

    override val overlayController: OverlayController by lazy {
        OverlayController(
            context = applicationContext,
            transactionRepository = transactionRepository,
            peopleRepository = peopleRepository,
            categoryRepository = categoryRepository,
            accountRepository = accountRepository,
            tripTagRepository = tripTagRepository,
            pendingCaptureDaoProvider = { awaitDatabase().pendingCaptureDao() },
            syncQueueDaoProvider = { awaitDatabase().syncQueueDao() },
            categoryStatDaoProvider = { awaitDatabase().categoryStatDao() },
            predictCategoryUseCase = predictCategoryUseCase,
            getOpenDebtsForPersonUseCase = getOpenDebtsForPersonUseCase,
            payableDaoProvider = { awaitDatabase().payableDao() },
            ioDispatcher = ioDispatcher,
            mainDispatcher = mainDispatcher,
            isLocked = {
                ensureAppLockStarted()
                appLockManager.isLockedNow()
            },
            hidePreview = { privacySettings.secure.value },
            subscriptionRepository = subscriptionRepository
        )
    }

    override val foregroundWatcher: ForegroundWatcher by lazy {
        ForegroundWatcher(
            context = applicationContext,
            pendingCaptureDaoProvider = { awaitDatabase().pendingCaptureDao() },
            transactionDaoProvider = { awaitDatabase().transactionDao() },
            categoryStatDaoProvider = { awaitDatabase().categoryStatDao() },
            predictCategoryUseCase = predictCategoryUseCase,
            overlayControllerProvider = { overlayController }
        )
    }

    override val peopleListRebuilder: PeopleListRebuilder by lazy {
        PeopleListRebuilder(
            preferencesRepository = preferencesRepository,
            googleSheetsClient = googleSheetsClient,
            peopleRepository = peopleRepository,
            ioDispatcher = ioDispatcher,
            readGate = sheetsReadGate
        )
    }

    override val getOpenDebtsForPersonUseCase: GetOpenDebtsForPersonUseCase by lazy {
        GetOpenDebtsForPersonUseCase(
            preferencesRepository = preferencesRepository,
            googleSheetsClient = googleSheetsClient,
            ioDispatcher = ioDispatcher
        )
    }
}

/**
 * Factory function to construct an [AppContainer] instance.
 */
fun AppContainer(
    applicationContext: Context,
    tokenProvider: TokenProvider? = null
): AppContainer = DefaultAppContainer(applicationContext, tokenProvider)
